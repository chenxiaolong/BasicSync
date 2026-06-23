// SPDX-FileCopyrightText: 2026 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

package stbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"maps"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"weak"

	"github.com/syncthing/syncthing/lib/fs"
	"github.com/syncthing/syncthing/lib/protocol"
)

var (
	implLogInvocationEnable  = false
	implLogInvocationExclude = []string{
		"updateChildrenLocked",
		"addChildLocked",
		"removeChildLocked",
		"getInfo",
		"getChildren",
		"Resolve",
		"DirNames",
		"getOrCreateChild",
		"startWatchingLocked",
		"stopWatchingLocked",
		"Stat",
	}
	implLogStackTraceEnable  = false
	implLogStackTraceInclude = []string{
		"OpenFile",
		"Rename",
	}
)

func implShouldLog(name string) (bool, bool) {
	logInvocation := implLogInvocationEnable && !slices.Contains(implLogInvocationExclude, name)
	logStackTrace := implLogStackTraceEnable && slices.Contains(implLogStackTraceInclude, name)

	return logInvocation, logStackTrace
}

func implLogf(name string, format string, args ...any) {
	logInvocation, logStackTrace := implShouldLog(name)

	if logInvocation {
		msg := fmt.Sprintf(format, args...)
		log.Printf("[IMPL] %s: %s", name, msg)
	}

	if logStackTrace {
		for line := range strings.SplitSeq(string(debug.Stack()), "\n") {
			if line != "" {
				log.Printf("[IMPL] %s: Stack: %v", name, line)
			}
		}
	}
}

const safPathSeparator = "/"

func rawPathComponents(path string) []string {
	result := []string{}

	for component := range strings.SplitSeq(path, safPathSeparator) {
		if component == "" || component == "." {
			continue
		} else {
			result = append(result, component)
		}
	}

	return result
}

func rawPath(path string) string {
	return strings.Join(rawPathComponents(path), safPathSeparator)
}

func rawPathJoin(paths ...string) string {
	return rawPath(strings.Join(paths, safPathSeparator))
}

func safePathComponents(path string) ([]string, error) {
	result := []string{}

	for component := range strings.SplitSeq(path, safPathSeparator) {
		if component == "" || component == "." {
			continue
		} else if component == ".." {
			if len(result) == 0 {
				return nil, fmt.Errorf("unsafe path: %q", path)
			}

			result = result[:len(result)-1]
		} else {
			result = append(result, component)
		}
	}

	return result, nil
}

func safePath(path string) (string, error) {
	components, err := safePathComponents(path)
	if err != nil {
		return "", err
	}

	return strings.Join(components, safPathSeparator), nil
}

func safePathSplit(path string) (string, string, error) {
	components, err := safePathComponents(path)
	if err != nil {
		return "", "", err
	}

	if len(components) == 0 {
		return "", "", nil
	} else if len(components) == 1 {
		return "", components[0], nil
	} else {
		last := len(components) - 1
		parent := strings.Join(components[:last], safPathSeparator)
		return parent, components[last], nil
	}
}

func safePathJoin(paths ...string) (string, error) {
	return safePath(strings.Join(paths, safPathSeparator))
}

func ensureSafeName(name string) error {
	if name == ".." || strings.Contains(name, safPathSeparator) {
		return fmt.Errorf("unsafe filename: %q", name)
	}

	return nil
}

func isAlwaysSeekable(uri string) bool {
	url, err := url.Parse(uri)
	if err != nil {
		return false
	}

	return url.Scheme == "content" && url.Host == "com.android.externalstorage.documents"
}

func encodeTreeUri(treeUri string) string {
	// See explanation in newSafFilesystem().
	return url.QueryEscape(treeUri)
}

type SafChangeListener interface {
	OnChange()
}

type safChangeListenerWrapper struct {
	onChange func()
}

func (l *safChangeListenerWrapper) OnChange() {
	l.onChange()
}

var _ SafChangeListener = (*safChangeListenerWrapper)(nil)

type SafObserver interface {
	Cancel()
}

type SafClient interface {
	ToTreeDocumentUri(treeUri string) (string, error)

	QueryTreeRootsJson() (string, error)

	QueryChildDocumentsJson(documentUri string) (string, error)

	QueryDocumentJson(documentUri string) (string, error)

	OpenDocument(documentUri, mode string) (int, error)

	CreateDocument(parentDocumentUri, mimeType, name string) (string, error)

	RenameDocument(documentUri, name string) (string, error)

	DeleteDocument(documentUri string) error

	MoveDocument(sourceDocumentUri, sourceParentDocumentUri, targetParentDocumentUri string) (string, error)

	ObserveDocument(documentUri string, changeListener SafChangeListener) (SafObserver, error)
}

// We report all SAF errors as file not found errors. DocumentsProvider
// implementations can technically throw more Parcelable exceptions, but that is
// not guaranteed to be possible by the SAF API.
type notFoundWrapper struct {
	err error
}

func (nfw *notFoundWrapper) Error() string {
	return nfw.err.Error()
}

func (nfw *notFoundWrapper) Unwrap() error {
	return os.ErrNotExist
}

var _ error = (*notFoundWrapper)(nil)

func wrapAsNotFound(err error) error {
	return &notFoundWrapper{err: err}
}

func queryTreeRoots(client SafClient) ([]string, error) {
	result, err := client.QueryTreeRootsJson()
	if err != nil {
		return nil, wrapAsNotFound(err)
	}

	var uris []string

	if err = json.Unmarshal([]byte(result), &uris); err != nil {
		return nil, err
	}

	return uris, nil
}

func queryChildDocuments(client SafClient, documentUri string) ([]safFileInfo, error) {
	result, err := client.QueryChildDocumentsJson(documentUri)
	if err != nil {
		return nil, wrapAsNotFound(err)
	}

	var children []safFileInfo

	if err = json.Unmarshal([]byte(result), &children); err != nil {
		return nil, err
	}

	return children, nil
}

func queryDocument(client SafClient, documentUri string) (safFileInfo, error) {
	result, err := client.QueryDocumentJson(documentUri)
	if err != nil {
		return safFileInfo{}, wrapAsNotFound(err)
	}

	var info safFileInfo

	if err = json.Unmarshal([]byte(result), &info); err != nil {
		return safFileInfo{}, err
	}

	return info, nil
}

type safOpts struct {
	client        SafClient
	cacheDuration time.Duration
}

// A file or directory entry within the SAF VFS tree. This intentionally does
// not store [safOpts] nor use [time.Time] to keep the struct size smaller.
// [safFileInfo] is also not used because we do not want two sources of truth
// for whether this node is a file or directory.
//
// The lock for multiple nodes can be held at the same time, but the lock order
// is always from parent to child to prevent deadlocks. A node will never be
// moved to a different part of the tree.
//
// We intentionally don't store a safFileInfo here because we don't want two
// sources of truth for whether this node is a file or directory.
type safNode struct {
	lock           sync.Mutex
	parent         *safNode // Immutable, no lock required.
	uri            string   // Immutable, no lock required.
	name           string   // Immutable, no lock required.
	size           int64
	mtime          int64
	infoExpiry     int64
	children       map[string]*safNode
	childrenExpiry int64
	observer       SafObserver
	watchManager   *safWatchManager // Immutable, no lock required.
}

const (
	safExpired = math.MinInt64

	safMimeTypeDir  = "vnd.android.document/directory"
	safMimeTypeFile = "application/octet-stream"
)

func makeChildren(isDir bool) map[string]*safNode {
	if isDir {
		return map[string]*safNode{}
	} else {
		return nil
	}
}

func (sn *safNode) path() string {
	components := []string{}
	current := sn

	for current.parent != nil {
		components = append(components, current.name)

		current = current.parent
	}

	slices.Reverse(components)

	// The name field has already been checked by ensureSafeName().
	return rawPathJoin(components...)
}

func (sn *safNode) cachedInfoLocked() safFileInfo {
	return safFileInfo{
		Uri:    sn.uri,
		Name_:  sn.name,
		Size_:  sn.size,
		Mtime:  sn.mtime,
		IsDir_: sn.children != nil,
	}
}

// Update the children cache for this node. This takes ownership of the map.
//
// Specifying an actual map will convert a file node to a directory node and
// specifying nil will convert a directory node to a file node. Every child in
// the new map that doesn't exist in the old map with the same name will have
// its watcher non-recursively started. Every child in the old map that doesn't
// exist in the new map with the same name will have its watchers recursively
// stopped.
func (sn *safNode) updateChildrenLocked(opts *safOpts, children map[string]*safNode, expiry int64) {
	implLogf("updateChildrenLocked", "sn=%q, children=%+v, expiry=%v", sn.uri, children, expiry)

	for name, newChild := range children {
		if newChild != sn.children[name] {
			newChild.StartWatching(opts)
		}
	}

	for name, oldChild := range sn.children {
		if oldChild != children[name] {
			oldChild.StopWatchingRecursively()
		}
	}

	sn.children = children
	sn.childrenExpiry = expiry
}

// Add a child to the children cache. This takes ownership of the child node.
//
// If this node is not a directory, [syscall.ENOTDIR] will be returned and no
// changes will have been made aside from recursively stopping watchers on the
// child (because this function took ownership of it). If the new child replaces
// an existing child, the existing child will also have its watchers recursively
// stopped.
//
// The new child's watcher will be non-recursively started if the child is
// successfully added.
func (sn *safNode) addChildLocked(opts *safOpts, name string, child *safNode) error {
	implLogf("addChildLocked", "sn=%q, name=%q, child=%+v", sn.uri, name, child)

	if sn.children == nil {
		child.StopWatchingRecursively()
		return fmt.Errorf("%q: %w", sn.uri, syscall.ENOTDIR)
	}

	changed := true

	if oldChild, ok := sn.children[name]; ok {
		if oldChild == child {
			changed = false
		} else {
			oldChild.StopWatchingRecursively()
		}
	}
	sn.children[name] = child

	if changed {
		child.StartWatching(opts)
	}

	return nil
}

// Remove a child from the children cache.
//
// If the child exists, all watchers in its subtree are recursively stopped.
//
// If notify is false, the watcher will not be notified. This is useful during
// renames within the same directory where addChild() is about to notify the
// watcher anyway.
func (sn *safNode) removeChildLocked(name string) error {
	implLogf("removeChildLocked", "sn=%q, name=%q", sn.uri, name)

	if sn.children == nil {
		return fmt.Errorf("%q: %w", sn.uri, syscall.ENOTDIR)
	}

	if child, ok := sn.children[name]; ok {
		child.StopWatchingRecursively()
		delete(sn.children, name)
	}

	return nil
}

// Get the current node's metadata, refreshing it if it expired. This returns a
// copy of the internal field.
//
// If the URI or name changes, an error will be returned and the cached metadata
// is left unchanged. If the file type (file vs. directory) changes, the cached
// children will be invalidated.
func (sn *safNode) getInfo(opts *safOpts) (*safFileInfo, error) {
	implLogf("getInfo", "sn=%q", sn.uri)

	sn.lock.Lock()
	defer sn.lock.Unlock()

	now := time.Now()
	if !now.After(time.Unix(sn.infoExpiry, 0)) {
		oldInfo := sn.cachedInfoLocked()
		return &oldInfo, nil
	}

	expiry := now.Add(opts.cacheDuration)

	info := safFileInfo{}

	// Empty URI is the "virtual" root node when using /rest/system/browse.
	if sn.uri == "" {
		info.IsDir_ = true
	} else {
		var err error
		info, err = queryDocument(opts.client, sn.uri)
		if err != nil {
			return nil, err
		}
	}

	if sn.parent == nil {
		// This is the initial query when creating the root node.
		if sn.uri == "" {
			sn.uri = info.Uri
		}
		if sn.name == "" {
			sn.name = info.Name_
		}
	}

	if info.Uri != sn.uri {
		return nil, fmt.Errorf(
			"URI unexpectedly changed: %q -> %q: %w",
			sn.uri, info.Uri, os.ErrInvalid,
		)
	} else if info.Name_ != sn.name {
		return nil, fmt.Errorf(
			"name unexpectedly changed: %q -> %q: %w",
			sn.name, info.Name_, os.ErrInvalid,
		)
	} else if err := ensureSafeName(info.Name_); err != nil {
		return nil, err
	}

	sn.size = info.Size_
	sn.mtime = info.Mtime
	sn.infoExpiry = expiry.Unix()

	if info.IsDir_ != (sn.children != nil) {
		sn.updateChildrenLocked(opts, makeChildren(info.IsDir_), safExpired)
	}

	return &info, nil
}

// Get the current node's list of children if it is a directory, refreshing the
// list if it expired. This returns a copy of the internal field.
//
// If this node is not a directory and allowFiles is false, an error wrapping
// [syscall.ENOTDIR] is returned. The cached metadata info is used to determine
// if the node is a directory. [safNode.getInfoLocked] is never called. Because
// SAF returns the metadata of all the children, the children will have
// non-expired info fields, but expired (and empty/nil) children fields.
func (sn *safNode) getChildren(opts *safOpts, allowFiles bool) (map[string]*safNode, error) {
	implLogf("getChildren", "sn=%q, allowFiles=%v", sn.uri, allowFiles)

	sn.lock.Lock()
	defer sn.lock.Unlock()

	if sn.children == nil {
		if allowFiles {
			return nil, nil
		} else {
			return nil, fmt.Errorf("%q: %w", sn.uri, syscall.ENOTDIR)
		}
	}

	now := time.Now()
	if !now.After(time.Unix(sn.childrenExpiry, 0)) {
		return maps.Clone(sn.children), nil
	}

	expiry := now.Add(opts.cacheDuration)
	children := map[string]*safNode{}

	if sn.uri == "" {
		// This is the "virtual" root node when using /rest/system/browse. We
		// create children for each of the persisted tree URIs and they are the
		// actual root nodes used when initializing a filesystem with them as
		// the root URI. These nodes are attached the virtual root node in one
		// direction only: parent->child, not child->parent.
		treeUris, err := queryTreeRoots(opts.client)
		if err != nil {
			return nil, err
		}

		for _, treeUri := range treeUris {
			child, err := newCachedSafTreeFromUri(opts, treeUri)
			if err != nil {
				return nil, err
			}

			children[encodeTreeUri(treeUri)] = child
		}
	} else {
		childInfos, err := queryChildDocuments(opts.client, sn.uri)
		if err != nil {
			return nil, err
		}

		for _, childInfo := range childInfos {
			if err = ensureSafeName(childInfo.Name_); err != nil {
				return nil, err
			}
			if _, ok := children[childInfo.Name_]; ok {
				return nil, fmt.Errorf(
					"duplicate child %q in %q: %w",
					childInfo.Name_, sn.uri, os.ErrInvalid,
				)
			}

			// Try to avoid throwing away the whole subtree if possible.
			child, ok := sn.children[childInfo.Name_]
			if ok && child.uri == childInfo.Uri {
				child.lock.Lock()
				child.size = childInfo.Size_
				child.mtime = childInfo.Mtime
				child.infoExpiry = expiry.Unix()
				if (child.children != nil) != childInfo.IsDir_ {
					child.updateChildrenLocked(opts, makeChildren(childInfo.IsDir_), safExpired)
				}
				child.lock.Unlock()
			} else {
				child = &safNode{
					parent:         sn,
					uri:            childInfo.Uri,
					name:           childInfo.Name_,
					size:           childInfo.Size_,
					mtime:          childInfo.Mtime,
					infoExpiry:     expiry.Unix(),
					children:       makeChildren(childInfo.IsDir_),
					childrenExpiry: safExpired,
					watchManager:   sn.watchManager,
				}
			}

			children[childInfo.Name_] = child
		}
	}

	sn.updateChildrenLocked(opts, children, expiry.Unix())

	return children, nil
}

// Get the child node referenced by the specified relative path. ".." components
// are permitted in the path if they don't result in escaping the current node.
// Evaluating ".." is done lexicographically without regard for whether the
// preceding component is a directory or not.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, but excluding, the child.
func (sn *safNode) Resolve(opts *safOpts, name string) (*safNode, error) {
	implLogf("Resolve", "sn=%q, name=%q", sn.uri, name)

	components, err := safePathComponents(name)
	if err != nil {
		return nil, err
	}

	current := sn

	for _, component := range components {
		children, err := current.getChildren(opts, false)
		if err != nil {
			return nil, err
		}

		child, ok := children[component]
		if !ok {
			return nil, fmt.Errorf(
				"%q not a child of %q: %w",
				component, current.uri, os.ErrNotExist,
			)
		}

		current = child
	}

	return current, nil
}

// Get the list of child names if this node is a directory.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, and including, the child.
func (sn *safNode) DirNames(opts *safOpts, name string) ([]string, error) {
	implLogf("DirNames", "sn=%q, name=%q", sn.uri, name)

	dir, err := sn.Resolve(opts, name)
	if err != nil {
		return nil, err
	}

	children, err := dir.getChildren(opts, false)
	if err != nil {
		return nil, err
	}

	names := make([]string, 0, len(children))

	for name := range children {
		names = append(names, name)
	}

	return names, nil
}

type creationMode int

const (
	nodeCanExist creationMode = iota
	nodeMustExist
	nodeMustNotExist
)

// Depending on the specified [creationMode], get the existing child and/or
// create it if it is missing.
//
// If a new node is created, it will have non-expired info and children fields.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, but excluding, the child.
func (sn *safNode) getOrCreateChild(
	opts *safOpts,
	name string,
	isDir bool,
	mode creationMode,
) (*safNode, bool, error) {
	implLogf("getOrCreateChild", "sn=%q, name=%q, isDir=%v, mode=%v", sn.uri, name, isDir, mode)

	parentPath, childName, err := safePathSplit(name)
	if err != nil {
		return nil, false, err
	}

	parent, err := sn.Resolve(opts, parentPath)
	if err != nil {
		return nil, false, err
	}

	isNew := false

	child, err := parent.Resolve(opts, childName)
	if err != nil {
		if mode == nodeMustExist || !errors.Is(err, os.ErrNotExist) {
			return nil, false, err
		}

		var mimeType string
		if isDir {
			mimeType = safMimeTypeDir
		} else {
			mimeType = safMimeTypeFile
		}

		uri, err := opts.client.CreateDocument(parent.uri, mimeType, childName)
		if err != nil {
			return nil, false, wrapAsNotFound(err)
		}

		now := time.Now()
		expiry := now.Add(opts.cacheDuration)

		child = &safNode{
			parent:         parent,
			uri:            uri,
			name:           childName,
			size:           0,
			mtime:          now.UnixMilli(), // Close enough.
			infoExpiry:     expiry.Unix(),
			children:       makeChildren(isDir),
			childrenExpiry: expiry.Unix(),
			watchManager:   sn.watchManager,
		}

		parent.lock.Lock()
		defer parent.lock.Unlock()

		if err = parent.addChildLocked(opts, childName, child); err != nil {
			return nil, false, err
		}

		isNew = true
	} else if mode == nodeMustNotExist {
		return nil, false, fmt.Errorf(
			"%q already exists in %q: %w",
			childName, parent.uri, os.ErrExist,
		)
	}

	return child, isNew, nil
}

// Create the specified directory. This function will fail with [os.ErrExist] if
// the directory already exists.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, but excluding, the child.
func (sn *safNode) Mkdir(opts *safOpts, name string) (*safNode, error) {
	implLogf("Mkdir", "sn=%q, name=%q", sn.uri, name)

	child, _, err := sn.getOrCreateChild(opts, name, true, nodeMustNotExist)
	return child, err
}

// Create the specified directory along with any missing parent directories.
// This function does not fail if the specified directory already exists.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, but excluding, the child.
func (sn *safNode) MkdirAll(opts *safOpts, name string) (*safNode, bool, error) {
	implLogf("MkdirAll", "sn=%q, name=%q", sn.uri, name)

	components, err := safePathComponents(name)
	if err != nil {
		return nil, false, err
	}

	current := sn
	currentIsNew := false

	for _, component := range components {
		child, isNew, err := current.getOrCreateChild(opts, component, true, nodeCanExist)
		if err != nil {
			return nil, false, err
		}

		current = child
		currentIsNew = currentIsNew || isNew
	}

	return current, currentIsNew, nil
}

// Open or create the specified file. Only the [os.O_RDONLY], [os.O_WRONLY],
// [os.O_RDWR], [os.O_CREATE], [os.O_TRUNC], and [os.O_EXCL] flags are
// supported.
//
// [os.O_RDONLY] and [os.O_WRONLY] only result in read-only and write-only
// underlying file descriptors when the SAF authority is Android's builtin
// external storage provider. For all other SAF authorities, the underlying file
// descriptor is opened with [os.O_RDWR] to guarantee that Android provides a
// seekable file descriptor.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, but excluding, the child.
func (sn *safNode) OpenFile(opts *safOpts, name string, flags int) (*safFile, error) {
	implLogf("OpenFile", "sn=%q, name=%q, flags=%#x", sn.uri, name, flags)

	var creationMode creationMode
	if flags&os.O_CREATE != 0 {
		if flags&os.O_EXCL != 0 {
			creationMode = nodeMustNotExist
		} else {
			creationMode = nodeCanExist
		}
	} else {
		creationMode = nodeMustExist
	}

	child, _, err := sn.getOrCreateChild(opts, name, false, creationMode)
	if err != nil {
		return nil, err
	}

	safMode := "rw"
	if flags&os.O_RDWR == 0 && isAlwaysSeekable(child.uri) {
		if flags&os.O_WRONLY != 0 {
			safMode = "w"
		} else {
			safMode = "r"
		}
	}

	// android-10.0.0_r1 is the first Android version that guaranteed that
	// O_TRUNC is only passed when 't' is present for "w" vs "wt" [1]. However,
	// "rw" has never truncated since the very beginning [2].
	//
	// [1] https://android.googlesource.com/platform/frameworks/base/+/63280e06fc64672ab36d14f852b13df2274cc328%5E!/
	// [2] https://android.googlesource.com/platform/frameworks/base/+/9066cfe9886ac131c34d59ed0e2d287b0e3c0087%5E!/
	if flags&os.O_TRUNC != 0 {
		safMode += "t"
	}

	fd, err := opts.client.OpenDocument(child.uri, safMode)
	if err != nil {
		return nil, wrapAsNotFound(err)
	}

	return &safFile{
		opts:     opts,
		file:     os.NewFile(uintptr(fd), name),
		canRead:  flags&os.O_WRONLY == 0,
		canWrite: flags&(os.O_WRONLY|os.O_RDWR) != 0,
		node:     child,
	}, nil
}

// Remove the specified file. The path must exist. If the path refers to a
// directory, then it must be empty as well.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, and including, the child.
func (sn *safNode) Remove(opts *safOpts, name string) error {
	implLogf("Remove", "sn=%q, name=%q", sn.uri, name)

	child, err := sn.Resolve(opts, name)
	if err != nil {
		return err
	}

	// SAF only supports recursive deletes, so we need to manually check for
	// directory emptiness.
	children, err := child.getChildren(opts, true)
	if err != nil {
		return err
	}

	if len(children) > 0 {
		return fmt.Errorf("%q: %w", child.uri, syscall.ENOTEMPTY)
	}

	return sn.RemoveAll(opts, name)
}

// Recursively delete the specified file. The path does not need to exist.
//
// This function will trigger a refresh of expired children fields for every
// node starting from this node up to, but excluding, the child.
func (sn *safNode) RemoveAll(opts *safOpts, name string) error {
	implLogf("RemoveAll", "sn=%q, name=%q", sn.uri, name)

	child, err := sn.Resolve(opts, name)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		} else {
			return err
		}
	}

	if child == sn {
		return fmt.Errorf("cannot delete self: %q: %w", child.uri, os.ErrInvalid)
	}

	if err = opts.client.DeleteDocument(child.uri); err != nil {
		return wrapAsNotFound(err)
	}

	child.parent.lock.Lock()
	defer child.parent.lock.Unlock()

	if err = child.parent.removeChildLocked(child.name); err != nil {
		return err
	}

	return nil
}

// Rename the specified path to the new path.
//
// The file at the old path will first be moved to the parent directory of the
// new path while keeping its existing name. Then, it will be renamed to the
// specified new name. During both of these steps, if a file with the same name
// already exists in the directory, the behavior is unspecified. SAF providers
// have no guarantees for how the move and rename operations are implemented.
//
// For both the old and new paths, this function will trigger a refresh of
// expired children fields for every node starting from this node up to, but
// excluding, the child.
func (sn *safNode) Rename(opts *safOpts, oldname string, newname string) (*safNode, error) {
	implLogf("Rename", "sn=%q, oldname=%q, newname=%q", sn.uri, oldname, newname)

	oldParentPath, oldChildName, err := safePathSplit(oldname)
	if err != nil {
		return nil, err
	} else if oldChildName == "" {
		return nil, fmt.Errorf("cannot rename root directory: %q: %w", sn.uri, os.ErrInvalid)
	}

	newParentPath, newChildName, err := safePathSplit(newname)
	if err != nil {
		return nil, err
	} else if newChildName == "" {
		return nil, fmt.Errorf("cannot replace root directory: %q: %w", sn.uri, os.ErrInvalid)
	}

	oldParent, err := sn.Resolve(opts, oldParentPath)
	if err != nil {
		return nil, err
	}

	newParent := oldParent
	if newParentPath != oldParentPath {
		newParent, err = sn.Resolve(opts, newParentPath)
		if err != nil {
			return nil, err
		}
	}

	child, err := oldParent.Resolve(opts, oldChildName)
	if err != nil {
		return nil, err
	}

	if oldParent != newParent {
		newChildUri, err := opts.client.MoveDocument(child.uri, oldParent.uri, newParent.uri)
		if err != nil {
			return nil, wrapAsNotFound(err)
		}

		oldParent.lock.Lock()
		err = oldParent.removeChildLocked(oldChildName)
		oldParent.lock.Unlock()
		if err != nil {
			return nil, err
		}

		child.lock.Lock()
		newChild := &safNode{
			parent:         newParent,
			uri:            newChildUri,
			name:           child.name,
			size:           child.size,
			mtime:          child.mtime,
			infoExpiry:     child.infoExpiry,
			children:       makeChildren(child.children != nil),
			childrenExpiry: safExpired,
			watchManager:   sn.watchManager,
		}
		child.lock.Unlock()

		newParent.lock.Lock()
		err = newParent.addChildLocked(opts, oldChildName, newChild)
		newParent.lock.Unlock()
		if err != nil {
			return nil, err
		}

		child = newChild
	}

	if oldChildName != newChildName {
		newChildUri, err := opts.client.RenameDocument(child.uri, newChildName)
		if err != nil {
			return nil, wrapAsNotFound(err)
		}

		newParent.lock.Lock()
		defer newParent.lock.Unlock()

		if err = newParent.removeChildLocked(oldChildName); err != nil {
			return nil, err
		}

		child.lock.Lock()
		newChild := &safNode{
			parent:         newParent,
			uri:            newChildUri,
			name:           newChildName,
			size:           child.size,
			mtime:          child.mtime,
			infoExpiry:     child.infoExpiry,
			children:       makeChildren(child.children != nil),
			childrenExpiry: safExpired,
			watchManager:   sn.watchManager,
		}
		child.lock.Unlock()

		if err = newParent.addChildLocked(opts, newChildName, newChild); err != nil {
			return nil, err
		}

		child = newChild
	}

	return child, nil
}

// Get the metadata for the specified file. This returns a copy of the internal
// info field.
//
// This function will trigger a refresh of an expired info field of the child
// node and also any expired children fields for every node starting from this
// node up to, but excluding, the child.
func (sn *safNode) Stat(opts *safOpts, name string) (*safFileInfo, error) {
	implLogf("Stat", "sn=%q, name=%q", sn.uri, name)

	child, err := sn.Resolve(opts, name)
	if err != nil {
		return nil, err
	}

	return child.getInfo(opts)
}

func (sn *safNode) startWatchingLocked(opts *safOpts) {
	implLogf("startWatchingLocked", "sn=%q", sn.uri)

	if sn.observer != nil || !sn.watchManager.ShouldWatch() || sn.uri == "" || sn.children == nil {
		return
	}

	listener := safChangeListenerWrapper{
		onChange: func() {
			sn.lock.Lock()
			sn.childrenExpiry = safExpired
			sn.lock.Unlock()

			sn.watchManager.OnChange(sn.path())
		},
	}

	observer, err := opts.client.ObserveDocument(sn.uri, &listener)
	if err != nil {
		log.Printf("failed to start watcher: %q: %v", sn.uri, err)
	} else {
		sn.observer = observer
		sn.watchManager.IncCount()
	}
}

func (sn *safNode) stopWatchingLocked() {
	implLogf("stopWatchingLocked", "sn=%q", sn.uri)

	if sn.observer != nil {
		sn.observer.Cancel()
		sn.observer = nil
		sn.watchManager.DecCount()
	}
}

func (sn *safNode) StartWatching(opts *safOpts) {
	sn.lock.Lock()
	defer sn.lock.Unlock()

	sn.startWatchingLocked(opts)
}

func (sn *safNode) StopWatching() {
	sn.lock.Lock()
	defer sn.lock.Unlock()

	sn.stopWatchingLocked()
}

func (sn *safNode) startWatchingRecursivelyLocked(opts *safOpts) {
	sn.startWatchingLocked(opts)

	for _, child := range sn.children {
		child.lock.Lock()
		child.startWatchingRecursivelyLocked(opts)
		child.lock.Unlock()
	}
}

func (sn *safNode) stopWatchingRecursivelyLocked() {
	sn.stopWatchingLocked()

	for _, child := range sn.children {
		child.lock.Lock()
		child.stopWatchingRecursivelyLocked()
		child.lock.Unlock()
	}
}

func (sn *safNode) StartWatchingRecursively(opts *safOpts) {
	sn.lock.Lock()
	defer sn.lock.Unlock()

	sn.startWatchingRecursivelyLocked(opts)
}

func (sn *safNode) StopWatchingRecursively() {
	sn.lock.Lock()
	defer sn.lock.Unlock()

	sn.stopWatchingRecursivelyLocked()
}

func (sn *safNode) glob(
	opts *safOpts,
	currentPath string,
	patternComponents,
	results []string,
) ([]string, error) {
	if len(patternComponents) == 0 {
		if len(currentPath) > 0 {
			results = append(results, currentPath)
		}

		return results, nil
	}

	children, err := sn.getChildren(opts, true)
	if err != nil {
		// Filesystem errors are ignored.
		return results, nil
	}

	for name, child := range children {
		matches, err := filepath.Match(patternComponents[0], name)
		if err != nil {
			return results, err
		}

		if matches {
			newResults, err := child.glob(
				opts,
				rawPathJoin(currentPath, name),
				patternComponents[1:],
				results,
			)
			results = newResults
			if err != nil {
				return results, err
			}
		}
	}

	return results, nil
}

// Return all paths that match the specified pattern. Like [filepath.Glob],
// filesystem errors are ignored. Only errors related to the input path or
// pattern being invalid will be returned.
//
// All returned paths are relative to this current node, not the root node.
//
// This function will trigger a refresh of expired children fields for every
// node matching components in the pattern up to, but excluding, the child.
func (sn *safNode) Glob(opts *safOpts, pattern string) ([]string, error) {
	implLogf("Glob", "sn=%q, pattern=%q", sn.uri, pattern)

	// Syncthing's default BasicFilesystem just uses filepath.Glob(), which does
	// not support recursive globs (**). We can match one component at a time.
	components, err := safePathComponents(pattern)
	if err != nil {
		return nil, err
	}

	return sn.glob(opts, "", components, []string{})
}

// Create a new root node from a SAF URI.
//
// The info and children fields will be immediately refreshed.
func newSafRootFromUri(opts *safOpts, uri string) (*safNode, error) {
	implLogf("newSafRootFromUri", "uri=%q", uri)

	if uri != "" {
		var err error
		uri, err = opts.client.ToTreeDocumentUri(uri)
		if err != nil {
			return nil, err
		}
	}

	node := &safNode{
		uri:            uri,
		infoExpiry:     safExpired,
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}

	if _, err := node.getInfo(opts); err != nil {
		return nil, err
	} else if _, err := node.getChildren(opts, true); err != nil {
		return nil, err
	}

	return node, nil
}

type safTreeCacheKey struct {
	opts safOpts
	uri  string
}

var safTreeCache sync.Map // map[safTreeCacheKey]weak.Pointer[safNode]

// Using the approach from https://go.dev/blog/cleanups-and-weak. Multiple SAF
// nodes may be created (and then discarded) when racing during the first
// multithreaded uncached access. However, this is better than just locking a
// regular map so that slow node construction for one URI does not block
// construction of a node for a different URI.
func newCachedSafTreeFromUri(opts *safOpts, uri string) (*safNode, error) {
	var key = safTreeCacheKey{opts: *opts, uri: uri}
	var node *safNode

	for {
		value, ok := safTreeCache.Load(key)
		if !ok {
			if node == nil {
				var err error
				node, err = newSafRootFromUri(opts, uri)
				if err != nil {
					return nil, err
				}
			}

			wp := weak.Make(node)
			var loaded bool
			value, loaded = safTreeCache.LoadOrStore(key, wp)
			if !loaded {
				runtime.AddCleanup(node, func(key safTreeCacheKey) {
					safTreeCache.CompareAndDelete(key, wp)
				}, key)

				return node, nil
			}
		}

		if mf := value.(weak.Pointer[safNode]).Value(); mf != nil {
			return mf, nil
		}

		safTreeCache.CompareAndDelete(key, value)
	}
}

type safWatchManager struct {
	lock   sync.Mutex
	ctx    context.Context
	ignore fs.Matcher
	out    chan fs.Event
	count  atomic.Int64
}

func (swm *safWatchManager) ShouldWatch() bool {
	swm.lock.Lock()
	defer swm.lock.Unlock()

	return swm.ctx != nil
}

func (swm *safWatchManager) Start(
	opts *safOpts,
	root *safNode,
	ctx context.Context,
	ignore fs.Matcher,
	out chan fs.Event,
) error {
	swm.lock.Lock()
	if swm.ctx != nil {
		swm.lock.Unlock()
		return errors.New("watcher already started")
	}
	swm.ctx = ctx
	swm.ignore = ignore
	swm.out = out
	swm.lock.Unlock()

	root.StartWatchingRecursively(opts)

	return nil
}

func (swm *safWatchManager) Stop(root *safNode) error {
	swm.lock.Lock()
	if swm.ctx == nil {
		swm.lock.Unlock()
		return errors.New("watcher already stopped")
	}
	swm.ctx = nil
	swm.ignore = nil
	swm.out = nil
	swm.lock.Unlock()

	root.StopWatchingRecursively()

	return nil
}

func (swm *safWatchManager) OnChange(path string) {
	implLogf("OnChange", "path=%q", path)

	swm.lock.Lock()
	ctx := swm.ctx
	ignore := swm.ignore
	out := swm.out
	swm.lock.Unlock()

	if ignore.Match(path).IsIgnored() {
		return
	}

	// It's impossible to have anything more granular than this with SAF. At
	// least FileSystemProvider's inotify event mask in DirectoryObserver is
	// relatively sane, so we shouldn't get frequent unwanted events.
	select {
	case out <- fs.Event{
		Name: path,
		Type: fs.NonRemove,
	}:
	// To avoid hanging on cancel due to unbuffered channel.
	case <-ctx.Done():
	}
}

func (swm *safWatchManager) IncCount() {
	count := swm.count.Add(1)
	implLogf("IncCount", "newCount=%v", count)
}

func (swm *safWatchManager) DecCount() {
	count := swm.count.Add(-1)
	implLogf("DecCount", "newCount=%v", count)
}

const filesystemTypeSaf fs.FilesystemType = "saf"

type safFileInfo struct {
	Uri    string `json:"uri"`
	Name_  string `json:"name"`
	Size_  int64  `json:"size"`
	Mtime  int64  `json:"mtime"`
	IsDir_ bool   `json:"is_dir"`
}

var _ fs.FileInfo = (*safFileInfo)(nil)

func (sfi *safFileInfo) Name() string {
	return sfi.Name_
}

func (sfi *safFileInfo) Mode() fs.FileMode {
	if sfi.IsDir_ {
		return fs.FileMode(os.ModeDir | 0o750)
	} else {
		return fs.FileMode(0o640)
	}
}

func (sfi *safFileInfo) Size() int64 {
	return sfi.Size_
}

func (sfi *safFileInfo) ModTime() time.Time {
	return time.UnixMilli(sfi.Mtime)
}

func (sfi *safFileInfo) IsDir() bool {
	return sfi.IsDir_
}

func (sfi *safFileInfo) Sys() interface{} {
	return nil
}

func (sfi *safFileInfo) IsRegular() bool {
	return !sfi.IsDir_
}

func (sfi *safFileInfo) IsSymlink() bool {
	// SAF is incapable of representing anything besides regular files and directories.
	return false
}

func (sfi *safFileInfo) Owner() int {
	// SAF has no concept of ownership.
	return -1
}

func (sfi *safFileInfo) Group() int {
	// SAF has no concept of ownership.
	return -1
}

// An opened SAF file descriptor.
type safFile struct {
	opts     *safOpts
	file     *os.File
	canRead  bool
	canWrite bool
	node     *safNode
}

var _ fs.File = (*safFile)(nil)

func (sf *safFile) Close() error {
	return sf.file.Close()
}

func (sf *safFile) Read(p []byte) (int, error) {
	if !sf.canRead {
		return 0, os.ErrInvalid
	}

	return sf.file.Read(p)
}

func (sf *safFile) ReadAt(p []byte, off int64) (int, error) {
	if !sf.canRead {
		return 0, os.ErrInvalid
	}

	return sf.file.ReadAt(p, off)
}

func (sf *safFile) Seek(offset int64, whence int) (int64, error) {
	return sf.file.Seek(offset, whence)
}

func (sf *safFile) Write(p []byte) (int, error) {
	if !sf.canWrite {
		return 0, os.ErrInvalid
	}

	return sf.file.Write(p)
}

func (sf *safFile) WriteAt(p []byte, off int64) (int, error) {
	if !sf.canWrite {
		return 0, os.ErrInvalid
	}

	return sf.file.WriteAt(p, off)
}

func (sf *safFile) Name() string {
	// This is the path that was passed to safFilesystem.Open(). It is purely
	// informational.
	return sf.file.Name()
}

func (sf *safFile) Truncate(size int64) error {
	if !sf.canWrite {
		return os.ErrInvalid
	}

	// This will only ever work for AOSP's FileSystemProvider. Any other SAF
	// provider that provides a seekable file descriptor is going to be using
	// StorageManager.openProxyFileDescriptor(), which does not support
	// ftruncate().
	return sf.file.Truncate(size)
}

func (sf *safFile) Stat() (fs.FileInfo, error) {
	return sf.node.Stat(sf.opts, "")
}

func (sf *safFile) Sync() error {
	return sf.file.Sync()
}

type safFilesystem struct {
	opts     *safOpts
	treeUri  string
	rootPath string
	root     *safNode
	options  []fs.Option
}

var _ fs.Filesystem = (*safFilesystem)(nil)

var (
	errPermissionsNotSupported  = errors.New("SAF does not support file permissions")
	errOwnershipNotSupported    = errors.New("SAF does not support file ownership")
	errTimestampSetNotSupported = errors.New("SAF does not support changing timestamps")
	errSymlinksNotSupported     = errors.New("SAF does not support symlinks")
	errWalkNotImplemented       = errors.New("SAF walk not implemented")
	errUsageNotSupported        = errors.New("SAF does not support usage reporting")
)

func (sfs *safFilesystem) Chmod(name string, mode fs.FileMode) error {
	return errPermissionsNotSupported
}

func (sfs *safFilesystem) Lchown(name string, uid, gid string) error {
	return errOwnershipNotSupported
}

func (sfs *safFilesystem) Chtimes(name string, atime time.Time, mtime time.Time) error {
	return errTimestampSetNotSupported
}

func (sfs *safFilesystem) Create(name string) (fs.File, error) {
	return sfs.OpenFile(name, os.O_CREATE|os.O_TRUNC, 0)
}

func (sfs *safFilesystem) CreateSymlink(target, name string) error {
	return errSymlinksNotSupported
}

func (sfs *safFilesystem) DirNames(name string) ([]string, error) {
	return sfs.root.DirNames(sfs.opts, name)
}

func (sfs *safFilesystem) Lstat(name string) (fs.FileInfo, error) {
	// SAF does not support symlinks.
	return sfs.Stat(name)
}

func (sfs *safFilesystem) Mkdir(name string, perm fs.FileMode) error {
	_, err := sfs.root.Mkdir(sfs.opts, name)
	return err
}

func (sfs *safFilesystem) MkdirAll(name string, perm fs.FileMode) error {
	_, _, err := sfs.root.MkdirAll(sfs.opts, name)
	return err
}

func (sfs *safFilesystem) Open(name string) (fs.File, error) {
	return sfs.OpenFile(name, os.O_RDONLY, 0)
}

func (sfs *safFilesystem) OpenFile(name string, flags int, mode fs.FileMode) (fs.File, error) {
	return sfs.root.OpenFile(sfs.opts, name, flags)
}

func (sfs *safFilesystem) ReadSymlink(name string) (string, error) {
	return "", errSymlinksNotSupported
}

func (sfs *safFilesystem) Remove(name string) error {
	return sfs.root.Remove(sfs.opts, name)
}

func (sfs *safFilesystem) RemoveAll(name string) error {
	return sfs.root.RemoveAll(sfs.opts, name)
}

func (sfs *safFilesystem) Rename(oldname, newname string) error {
	_, err := sfs.root.Rename(sfs.opts, oldname, newname)
	return err
}

func (sfs *safFilesystem) Stat(name string) (fs.FileInfo, error) {
	return sfs.root.Stat(sfs.opts, name)
}

func (sfs *safFilesystem) Walk(name string, walkFn fs.WalkFunc) error {
	// Syncthing handles walking in WalkFilesystem. BasicFilesystem doesn't implement this either.
	return errWalkNotImplemented
}

func (sfs *safFilesystem) Watch(
	path string,
	ignore fs.Matcher,
	ctx context.Context,
	ignorePerms bool,
) (<-chan fs.Event, <-chan error, error) {
	outChan := make(chan fs.Event)
	// There are no errors we can ever report.
	errChan := make(chan error)

	// This is only ever called for the root path: "." in monitorWatch() in
	// lib/model/folder.go, so we don't bother complicating the code with
	// supporting multiple watchers or watching subtrees.
	if path != "." {
		return nil, nil, errors.New("this watch implementation only supports the root")
	}

	if err := sfs.root.watchManager.Start(sfs.opts, sfs.root, ctx, ignore, outChan); err != nil {
		return nil, nil, err
	}

	go func() {
		<-ctx.Done()

		if err := sfs.root.watchManager.Stop(sfs.root); err != nil {
			log.Print(err)
		}
	}()

	return outChan, errChan, nil
}

func (sfs *safFilesystem) Hide(name string) error {
	// BasicFilesystem checks that the file exists, but makes it a no-op.
	_, err := sfs.root.Resolve(sfs.opts, name)
	return err
}

func (sfs *safFilesystem) Unhide(name string) error {
	// BasicFilesystem checks that the file exists, but makes it a no-op.
	_, err := sfs.root.Resolve(sfs.opts, name)
	return err
}

func (sfs *safFilesystem) Glob(pattern string) ([]string, error) {
	return sfs.root.Glob(sfs.opts, pattern)
}

func (sfs *safFilesystem) Roots() ([]string, error) {
	if sfs.treeUri == "" {
		return sfs.root.DirNames(sfs.opts, "")
	}

	return []string{encodeTreeUri(sfs.treeUri)}, nil
}

func (sfs *safFilesystem) Usage(name string) (fs.Usage, error) {
	// We can't make use of COLUMN_CAPACITY_BYTES and COLUMN_AVAILABLE_BYTES
	// from the DocumentsProvider's queryRoots() because we don't have
	// permissions to SAF roots URIs.
	return fs.Usage{}, errUsageNotSupported
}

func (sfs *safFilesystem) Type() fs.FilesystemType {
	return filesystemTypeSaf
}

func (sfs *safFilesystem) URI() string {
	// Used in:
	// - lib/config/folderconfiguration.go : ModTimeWindow()
	//   - Assumes that the filesystem is local on Android and passes the URI
	//     value directly to disk.Usage(). This is impossible to work around,
	//     but is non-fatal, so we'll just ignore it.
	// - lib/ignore/ignore.go : loadParseIncludeFile()
	//   - Skipped for custom filesystems.
	// - lib/model/model.go : warnAboutOverwritingProtectedFiles()
	//   - Skipped for custom filesystems.
	// - lib/osutil/osutil.go : RenameOrCopy()
	//   - Used for determining if Rename() can be used across filesystems.
	//     Using the URL-encoded SAF tree URI is fine for this.
	// - lib/versioner/external.go : Archive()
	//   - Used for expanding %FOLDER_PATH%. There's not much we can do here.
	// - lib/versioner/util.go : Archive()
	//   - Used for creating a new fs.Filesystem instance for the .stversions
	//     directory. Uses filepath.Join() to combine the URI with .stversions.

	// rootPath is always constructed with a safe path.
	return rawPathJoin(encodeTreeUri(sfs.treeUri), sfs.rootPath)
}

func (sfs *safFilesystem) Options() []fs.Option {
	return sfs.options
}

func (sfs *safFilesystem) SameFile(fi1, fi2 fs.FileInfo) bool {
	info1, ok := fi1.(*safFileInfo)
	if !ok {
		return false
	}

	info2, ok := fi2.(*safFileInfo)
	if !ok {
		return false
	}

	return info1.Uri == info2.Uri
}

func (sfs *safFilesystem) PlatformData(
	name string,
	withOwnership,
	withXattrs bool,
	xattrFilter fs.XattrFilter,
) (protocol.PlatformData, error) {
	return protocol.PlatformData{}, nil
}

func (sfs *safFilesystem) GetXattr(
	name string,
	xattrFilter fs.XattrFilter,
) ([]protocol.Xattr, error) {
	return nil, fs.ErrXattrsNotSupported
}

func (sfs *safFilesystem) SetXattr(
	path string,
	xattrs []protocol.Xattr,
	xattrFilter fs.XattrFilter,
) error {
	return fs.ErrXattrsNotSupported
}

var globalSafClient SafClient

func SetSafClient(client SafClient) {
	globalSafClient = client
}

func newSafFilesystem(encodedUri string, opts ...fs.Option) (fs.Filesystem, error) {
	client := globalSafClient
	if client == nil {
		return nil, errors.New("no SafClient has been set")
	}

	safOpts := &safOpts{
		client:        client,
		cacheDuration: 300 * time.Second,
	}

	// The URI is <URL-encoded SAF tree URI>[/<relative path>]. This format is
	// picked because Syncthing expects the URI to be usable with filepath
	// functions. We can't ever let the actual SAF tree URI get broken up since
	// it is just an opaque string.
	treeUriEncoded, rootPath, hasPath := strings.Cut(encodedUri, safPathSeparator)

	treeUri, err := url.QueryUnescape(treeUriEncoded)
	if err != nil {
		return nil, err
	}

	root, err := newCachedSafTreeFromUri(safOpts, treeUri)
	if err != nil {
		return nil, err
	}

	// If we're looking at a subpath, we unfortunately need to create it if it's
	// missing. We can't construct SAF URIs out of thin air since they are
	// opaque.
	if hasPath {
		rootPath, err = safePath(rootPath)
		if err != nil {
			return nil, err
		}

		child, err := root.Resolve(safOpts, rootPath)
		if err != nil && errors.Is(err, os.ErrNotExist) {
			child, _, err = root.MkdirAll(safOpts, rootPath)
		}
		if err != nil {
			return nil, err
		}

		child.parent = nil
		root = child
	}

	return &safFilesystem{
		opts:     safOpts,
		treeUri:  treeUri,
		rootPath: rootPath,
		root:     root,
		options:  opts,
	}, nil
}

func init() {
	fs.RegisterFilesystemType(filesystemTypeSaf, newSafFilesystem)
}
