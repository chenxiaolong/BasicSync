// SPDX-FileCopyrightText: 2026 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

package stbridge

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"syscall"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/fs"
	"github.com/syncthing/syncthing/lib/ignore/ignoreresult"
)

func TestSafePath(t *testing.T) {
	for _, i := range []struct {
		path     string
		expected string
		unsafe   bool
	}{
		{path: "", expected: ""},
		{path: ".", expected: ""},
		{path: "/", expected: ""},
		{path: "a", expected: "a"},
		{path: "/a", expected: "a"},
		{path: ".////./.", expected: ""},
		{path: "..", unsafe: true},
		{path: "../..", unsafe: true},
		{path: "a/../b", expected: "b"},
		{path: "a/../..", unsafe: true},
	} {
		result, err := safePath(i.path)
		if i.unsafe {
			if err == nil {
				t.Errorf("unsafe path was accepted: %q", i.path)
			}
		} else {
			if err != nil {
				t.Error(err)
			} else if result != i.expected {
				t.Errorf("invalid safe path: %q != %q", result, i.expected)
			}
		}
	}
}

func TestSafePathSplit(t *testing.T) {
	for _, i := range []struct {
		path   string
		parent string
		base   string
		unsafe bool
	}{
		{path: "", parent: "", base: ""},
		{path: ".", parent: "", base: ""},
		{path: "/", parent: "", base: ""},
		{path: "..", unsafe: true},
		{path: "/a", parent: "", base: "a"},
		{path: "a/", parent: "", base: "a"},
		{path: "a/b", parent: "a", base: "b"},
		{path: "a/////b/////c", parent: "a/b", base: "c"},
	} {
		parent, base, err := safePathSplit(i.path)
		if i.unsafe {
			if err == nil {
				t.Errorf("unsafe path was accepted: %q", i.path)
			}
		} else {
			if err != nil {
				t.Error(err)
			} else if parent != i.parent {
				t.Errorf("%q: invalid parent: %q != %q", i.path, parent, i.parent)
			} else if base != i.base {
				t.Errorf("%q: invalid base: %q != %q", i.path, base, i.base)
			}
		}
	}
}

func TestSafePathJoin(t *testing.T) {
	for _, i := range []struct {
		paths    []string
		expected string
		unsafe   bool
	}{
		{paths: []string{}, expected: ""},
		{paths: []string{".", "", "/", "///"}, expected: ""},
		{paths: []string{"/a"}, expected: "a"},
		{paths: []string{"/a//", "b///../..", "./c"}, expected: "c"},
		{paths: []string{"a", "b", "../../c/../.."}, unsafe: true},
	} {
		result, err := safePathJoin(i.paths...)
		if i.unsafe {
			if err == nil {
				t.Errorf("unsafe path was accepted: %+v", i.paths)
			}
		} else {
			if err != nil {
				t.Error(err)
			} else if result != i.expected {
				t.Errorf("%+v: invalid joined path: %q != %q", i.paths, result, i.expected)
			}
		}
	}
}

func TestIsAlwaysSeekable(t *testing.T) {
	if !isAlwaysSeekable("content://com.android.externalstorage.documents/tree/primary%3Atest") {
		t.Errorf("external storage provider not detected")
	}
	if isAlwaysSeekable("content://com.chiller3.rsaf.documents/tree/remote%3Atest") {
		t.Errorf("external storage provider incorrectly detected")
	}
}

type mockSafClient struct {
	queryTreeRootsJson      func() (string, error)
	queryChildDocumentsJson func(documentUri string) (string, error)
	queryDocumentJson       func(documentUri string) (string, error)
	openDocument            func(documentUri, mode string) (int, error)
	createDocument          func(parentDocumentUri, mimeType, name string) (string, error)
	renameDocument          func(documentUri, name string) (string, error)
	deleteDocument          func(documentUri string) error
	moveDocument            func(sourceDocumentUri, sourceParentDocumentUri, targetParentDocumentUri string) (string, error)
	observeDocument         func(documentUri string, changeListener SafChangeListener) (SafObserver, error)
}

func (msc *mockSafClient) ToTreeDocumentUri(treeUri string) (string, error) {
	return treeUri, nil
}

func (msc *mockSafClient) QueryTreeRootsJson() (string, error) {
	return msc.queryTreeRootsJson()
}

func (msc *mockSafClient) QueryChildDocumentsJson(documentUri string) (string, error) {
	return msc.queryChildDocumentsJson(documentUri)
}

func (msc *mockSafClient) QueryDocumentJson(documentUri string) (string, error) {
	return msc.queryDocumentJson(documentUri)
}

func (msc *mockSafClient) OpenDocument(documentUri, mode string) (int, error) {
	return msc.openDocument(documentUri, mode)
}

func (msc *mockSafClient) CreateDocument(parentDocumentUri, mimeType, name string) (string, error) {
	return msc.createDocument(parentDocumentUri, mimeType, name)
}

func (msc *mockSafClient) RenameDocument(documentUri, name string) (string, error) {
	return msc.renameDocument(documentUri, name)
}

func (msc *mockSafClient) DeleteDocument(documentUri string) error {
	return msc.deleteDocument(documentUri)
}

func (msc *mockSafClient) MoveDocument(sourceDocumentUri, sourceParentDocumentUri, targetParentDocumentUri string) (string, error) {
	return msc.moveDocument(sourceDocumentUri, sourceParentDocumentUri, targetParentDocumentUri)
}

func (msc *mockSafClient) ObserveDocument(documentUri string, changeListener SafChangeListener) (SafObserver, error) {
	return msc.observeDocument(documentUri, changeListener)
}

var _ SafClient = (*mockSafClient)(nil)

type mockMatcher struct{}

func (m *mockMatcher) Match(name string) ignoreresult.R {
	return ignoreresult.NotIgnored
}

var _ fs.Matcher = (*mockMatcher)(nil)

func newTestClient() (*mockSafClient, *safOpts) {
	client := &mockSafClient{}
	opts := &safOpts{
		client:        client,
		cacheDuration: 1 * time.Hour,
	}

	return client, opts
}

var safNotExpired = time.Now().Add(1 * time.Hour).Unix()

func treeRootsJson(uris ...string) string {
	bytes, err := json.Marshal(uris)
	if err != nil {
		log.Fatalf("failed to encode tree URIs as JSON: %v", err)
	}

	return string(bytes)
}

func fileInfoJson(info *safFileInfo) string {
	bytes, err := json.Marshal(info)
	if err != nil {
		log.Fatalf("failed to encode info as JSON: %v", err)
	}

	return string(bytes)
}

func fileInfoListJson(infos ...*safFileInfo) string {
	bytes, err := json.Marshal(infos)
	if err != nil {
		log.Fatalf("failed to encode info list as JSON: %v", err)
	}

	return string(bytes)
}

func TestGetInfo(t *testing.T) {
	newInfo := safFileInfo{
		Uri:    "uri://0",
		Name_:  "0",
		Size_:  1,
		Mtime:  1,
		IsDir_: false,
	}
	node := &safNode{
		uri:            "uri://0",
		name:           "0",
		size:           0,
		mtime:          0,
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   &safWatchManager{},
	}

	calls := 0

	client, opts := newTestClient()
	client.queryDocumentJson = func(documentUri string) (string, error) {
		calls++
		return fileInfoJson(&newInfo), nil
	}

	info, err := node.getInfo(opts)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Errorf("queryDocumentJson was not called when expired")
	}
	if *info != newInfo {
		t.Errorf("info does not match: %+v != %+v", info, newInfo)
	}
	if node.children != nil || node.childrenExpiry != safExpired {
		t.Errorf("children did not get invalidated: %+v: %v", node.children, node.childrenExpiry)
	}

	info, err = node.getInfo(opts)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Errorf("queryDocumentJson was called when not expired")
	}
	if *info != newInfo {
		t.Errorf("info does not match: %+v != %+v", info, newInfo)
	}
}

func TestGetChildren(t *testing.T) {
	node := &safNode{
		uri:            "uri://0",
		name:           "0",
		size:           0,
		mtime:          0,
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}
	childFileInfo := safFileInfo{
		Uri:    "uri://1",
		Name_:  "1",
		Size_:  1,
		Mtime:  1,
		IsDir_: false,
	}
	childDirInfo := safFileInfo{
		Uri:    "uri://2",
		Name_:  "2",
		Size_:  2,
		Mtime:  2,
		IsDir_: true,
	}

	calls := 0

	client, opts := newTestClient()
	client.queryChildDocumentsJson = func(documentUri string) (string, error) {
		calls++
		return fileInfoListJson(&childFileInfo, &childDirInfo), nil
	}

	children, err := node.getChildren(opts, false)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Errorf("queryChildDocumentsJson was not called when expired")
	}
	if len(children) != 2 {
		t.Errorf("invalid number of children")
	}
	for _, expectedInfo := range []*safFileInfo{&childFileInfo, &childDirInfo} {
		actualNode := children[expectedInfo.Name_]
		actualInfo := actualNode.cachedInfoLocked()

		if actualInfo != *expectedInfo {
			t.Errorf("child info does not match: %+v != %+v", actualInfo, expectedInfo)
		}
	}
	if node.childrenExpiry == safExpired {
		t.Errorf("children field is still expired")
	}

	oldChildren := children

	children, err = node.getChildren(opts, false)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Errorf("queryChildDocumentsJson was called when not expired")
	}
	if !maps.Equal(children, oldChildren) {
		t.Errorf("children changed: %+v != %+v", children, oldChildren)
	}
}

func TestGetRoots(t *testing.T) {
	node := &safNode{
		uri:            "",
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}
	treeInfo1 := safFileInfo{
		Uri:    "uri://1",
		IsDir_: true,
	}
	treeInfo2 := safFileInfo{
		Uri:    "uri://2",
		IsDir_: true,
	}

	client, opts := newTestClient()
	client.queryTreeRootsJson = func() (string, error) {
		return treeRootsJson("uri://1", "uri://2"), nil
	}
	client.queryDocumentJson = func(documentUri string) (string, error) {
		return fileInfoJson(&safFileInfo{
			Uri:    documentUri,
			IsDir_: true,
		}), nil
	}
	client.queryChildDocumentsJson = func(documentUri string) (string, error) {
		if documentUri != "uri://1" && documentUri != "uri://2" {
			t.Errorf("querying children for invalid URI: %q", documentUri)
		}

		return fileInfoListJson(), nil
	}

	children, err := node.getChildren(opts, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(children) != 2 {
		t.Errorf("invalid number of children")
	}
	for _, expectedInfo := range []*safFileInfo{&treeInfo1, &treeInfo2} {
		actualNode := children[encodeTreeUri(expectedInfo.Uri)]
		actualInfo := actualNode.cachedInfoLocked()

		if actualInfo != *expectedInfo {
			t.Errorf("child info does not match: %+v != %+v", actualInfo, expectedInfo)
		}
	}
	if node.childrenExpiry == safExpired {
		t.Errorf("children field is still expired")
	}
}

func TestResolveSelf(t *testing.T) {
	node := &safNode{
		uri:            "0",
		infoExpiry:     safExpired,
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}

	_, opts := newTestClient()

	for _, isDir := range []bool{true, false} {
		if isDir {
			node.children = makeChildren(true)
		} else {
			node.children = nil
		}

		for _, path := range []string{"", ".", "/", "a////../././."} {
			resolved, err := node.Resolve(opts, path)
			if err != nil {
				t.Fatal(err)
			}
			if resolved != node {
				t.Errorf("%q did not return self: %+v != %+v", path, resolved, node)
			}
		}

		for _, path := range []string{"..", "/..", "a///////b/../c/../../.."} {
			resolved, err := node.Resolve(opts, path)
			if err == nil {
				t.Errorf("%q did not fail: %+v", path, resolved)
			}
		}
	}
}

func TestResolveChild(t *testing.T) {
	node := &safNode{
		uri:            "0",
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}

	client, opts := newTestClient()
	client.queryChildDocumentsJson = func(documentUri string) (string, error) {
		depth, _ := strconv.ParseInt(filepath.Base(documentUri), 10, 0)
		if depth > 2 {
			return fileInfoListJson(), nil
		}

		childName := strconv.Itoa(int(depth + 1))

		return fileInfoListJson(&safFileInfo{
			Uri:    childName,
			Name_:  childName,
			IsDir_: depth < 2,
		}), nil
	}

	for _, path := range []string{"1", "1/2", "1/2/3"} {
		child, err := node.Resolve(opts, path)
		if err != nil {
			t.Fatal(err)
		}
		if child.name != filepath.Base(path) {
			t.Errorf("%q returned incorrect child: %+v", path, child)
		}
	}

	child, err := node.Resolve(opts, "1/2/3/4")
	if !errors.Is(err, syscall.ENOTDIR) {
		t.Errorf("did not fail with ENOTDIR: %+v: %+v", child, err)
	}

	child, err = node.Resolve(opts, "1/2/4")
	if !errors.Is(err, os.ErrNotExist) {
		t.Errorf("did not fail with ENOENT: %+v: %+v", child, err)
	}
}

func TestDirNames(t *testing.T) {
	node := &safNode{
		name:           "0",
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}

	client, opts := newTestClient()
	client.queryChildDocumentsJson = func(documentUri string) (string, error) {
		count, _ := strconv.ParseInt(filepath.Base(documentUri), 10, 0)
		children := []*safFileInfo{}

		for i := range count {
			childName := strconv.Itoa(int(i))

			children = append(children, &safFileInfo{
				Uri:    childName,
				Name_:  childName,
				IsDir_: false,
			})
		}

		return fileInfoListJson(children...), nil
	}

	for count := range 3 {
		expected := []string{}
		for i := range count {
			expected = append(expected, strconv.Itoa(i))
		}

		node.uri = strconv.Itoa(count)
		node.childrenExpiry = safExpired

		dirNames, err := node.DirNames(opts, ".")
		if err != nil {
			t.Fatal(err)
		}

		slices.Sort(dirNames)

		if !slices.Equal(dirNames, expected) {
			t.Errorf("invalid child names: %+v != %+v", dirNames, expected)
		}
	}
}

func TestMkdirMissing(t *testing.T) {
	node := &safNode{
		uri:            "0",
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   &safWatchManager{},
	}

	client, opts := newTestClient()
	client.createDocument = func(parentDocumentUri, mimeType, name string) (string, error) {
		if mimeType != safMimeTypeDir {
			t.Errorf("invalid MIME type: %q", mimeType)
		}
		return name, nil
	}

	child, err := node.Mkdir(opts, "1")
	if err != nil {
		t.Fatal(err)
	}
	if child.name != "1" {
		t.Errorf("invalid child name: %+v", child)
	}
	if _, ok := node.children[child.name]; !ok {
		t.Errorf("missing child in children: %+v", node)
	}
}

func TestMkdirExists(t *testing.T) {
	watchManager := &safWatchManager{}
	node := &safNode{
		uri:            "0",
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"] = &safNode{
		parent:         node,
		uri:            "1",
		name:           "1",
		infoExpiry:     safNotExpired,
		childrenExpiry: safExpired,
		watchManager:   watchManager,
	}

	client, opts := newTestClient()
	client.createDocument = func(parentDocumentUri, mimeType, name string) (string, error) {
		if mimeType != safMimeTypeDir {
			t.Errorf("invalid MIME type: %q", mimeType)
		}
		return name, nil
	}

	for _, path := range []string{".", "1"} {
		child, err := node.Mkdir(opts, path)
		if !errors.Is(err, os.ErrExist) {
			t.Errorf("%q: did not fail with EEXIST: %+v: %+v", path, child, err)
		}
	}
}

func TestMkdirAll(t *testing.T) {
	watchManager := &safWatchManager{}
	node := &safNode{
		uri:            "0",
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"] = &safNode{
		parent:         node,
		uri:            "1",
		name:           "1",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}

	client, opts := newTestClient()
	client.createDocument = func(parentDocumentUri, mimeType, name string) (string, error) {
		if mimeType != safMimeTypeDir {
			t.Errorf("invalid MIME type: %q", mimeType)
		}
		return name, nil
	}

	child, isNew, err := node.MkdirAll(opts, "1/2")
	if err != nil {
		t.Fatal(err)
	}
	if !isNew {
		t.Errorf("did not report child as new: %+v", child)
	}
	if child.name != "2" {
		t.Errorf("invalid child name: %+v", child)
	}

	oldChild := child

	child, isNew, err = node.MkdirAll(opts, "1/2")
	if err != nil {
		t.Fatal(err)
	}
	if isNew {
		t.Errorf("child reported as new: %+v", child)
	}
	if child != oldChild {
		t.Errorf("child changed: %p (%+v) != %p (%+v)", child, child, oldChild, oldChild)
	}
}

func TestOpenFileMissing(t *testing.T) {
	node := &safNode{
		uri:            "0",
		infoExpiry:     safExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   &safWatchManager{},
	}

	client, opts := newTestClient()
	client.createDocument = func(parentDocumentUri, mimeType, name string) (string, error) {
		if mimeType != safMimeTypeFile {
			t.Errorf("invalid MIME type: %q", mimeType)
		}
		return name, nil
	}
	client.openDocument = func(documentUri, mode string) (int, error) {
		if mode != "rwt" {
			t.Errorf("invalid mode: %q", mode)
		}
		return syscall.Dup(syscall.Stdout)
	}

	file, err := node.OpenFile(opts, "1", os.O_RDWR|os.O_CREATE|os.O_TRUNC)
	if err != nil {
		t.Fatal(err)
	}
	file.Close()

	file, err = node.OpenFile(opts, "2", os.O_RDONLY)
	if !errors.Is(err, os.ErrNotExist) {
		if file != nil {
			file.Close()
		}
		t.Errorf("did not fail with ENOENT: %+v: %+v", file, err)
	}
}

func TestOpenFileExists(t *testing.T) {
	node := &safNode{
		uri:            "0",
		infoExpiry:     safExpired,
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}

	client, opts := newTestClient()
	client.createDocument = func(parentDocumentUri, mimeType, name string) (string, error) {
		if mimeType != safMimeTypeDir {
			t.Errorf("invalid MIME type: %q", mimeType)
		}
		return name, nil
	}
	client.openDocument = func(documentUri, mode string) (int, error) {
		if mode != "rw" {
			t.Errorf("invalid mode: %q", mode)
		}
		return syscall.Dup(syscall.Stdin)
	}

	file, err := node.OpenFile(opts, ".", os.O_RDWR|os.O_CREATE)
	if err != nil {
		t.Fatal(err)
	}
	file.Close()

	file, err = node.OpenFile(opts, ".", os.O_RDWR|os.O_CREATE|os.O_EXCL)
	if !errors.Is(err, os.ErrExist) {
		if file != nil {
			file.Close()
		}
		t.Errorf("did not fail with EEXIST: %+v", err)
	}
}

func TestRemove(t *testing.T) {
	watchManager := &safWatchManager{}
	node := &safNode{
		uri:            "0",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"] = &safNode{
		parent:         node,
		uri:            "1",
		name:           "1",
		infoExpiry:     safNotExpired,
		childrenExpiry: safExpired,
		watchManager:   watchManager,
	}

	client, opts := newTestClient()
	client.deleteDocument = func(documentUri string) error {
		if documentUri != "1" {
			t.Errorf("deleting incorrect file: %q", documentUri)
		}
		return nil
	}

	err := node.Remove(opts, ".")
	if !errors.Is(err, syscall.ENOTEMPTY) {
		t.Errorf("did not fail with ENOTEMPTY: %+v", err)
	}

	err = node.Remove(opts, "1")
	if err != nil {
		t.Fatal(err)
	}

	err = node.Remove(opts, "2")
	if !errors.Is(err, os.ErrNotExist) {
		t.Errorf("did not fail with ENOENT: %+v", err)
	}
}

func TestRemoveAll(t *testing.T) {
	watchManager := &safWatchManager{}
	node := &safNode{
		uri:            "0",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"] = &safNode{
		parent:         node,
		uri:            "1",
		name:           "1",
		infoExpiry:     safNotExpired,
		childrenExpiry: safExpired,
		watchManager:   watchManager,
	}

	client, opts := newTestClient()
	client.deleteDocument = func(documentUri string) error {
		if documentUri != "1" {
			t.Errorf("deleting incorrect file: %q", documentUri)
		}
		return nil
	}

	if err := node.RemoveAll(opts, "1"); err != nil {
		t.Error(err)
	}
	if err := node.RemoveAll(opts, "1"); err != nil {
		t.Error(err)
	}
	if err := node.RemoveAll(opts, ""); !errors.Is(err, os.ErrInvalid) {
		t.Errorf("deleting root did not fail with EINVAL: %+v", err)
	}
}

func TestRename(t *testing.T) {
	watchManager := &safWatchManager{}
	node := &safNode{
		uri:            "0",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"] = &safNode{
		parent:         node,
		uri:            "1",
		name:           "1",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"].children["a"] = &safNode{
		parent:         node.children["1"],
		uri:            "a",
		name:           "a",
		infoExpiry:     safNotExpired,
		childrenExpiry: safExpired,
		watchManager:   watchManager,
	}
	node.children["2"] = &safNode{
		parent:         node,
		uri:            "2",
		name:           "2",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safExpired,
		watchManager:   watchManager,
	}

	client, opts := newTestClient()
	client.moveDocument = func(sourceDocumentUri, sourceParentDocumentUri, targetParentDocumentUri string) (string, error) {
		if sourceDocumentUri != "a" {
			t.Errorf("invalid source: %q", sourceDocumentUri)
		}
		if sourceParentDocumentUri != "1" {
			t.Errorf("invalid source parent: %q", sourceParentDocumentUri)
		}
		if targetParentDocumentUri != "2" {
			t.Errorf("invalid target parent: %q", targetParentDocumentUri)
		}
		return sourceDocumentUri, nil
	}
	client.renameDocument = func(documentUri, name string) (string, error) {
		if documentUri != "a" {
			t.Errorf("invalid document: %q", documentUri)
		}
		if name != "b" {
			t.Errorf("invalid name: %q", name)
		}
		return name, nil
	}

	oldChild := node.children["1"].children["a"]

	child, err := node.Rename(opts, "1/a", "2/b")
	if err != nil {
		t.Fatal(err)
	}
	if child == oldChild {
		t.Errorf("child did not change despite URI changing")
	}

	if _, err := node.Rename(opts, "", "3"); !errors.Is(err, os.ErrInvalid) {
		t.Errorf("renaming root did not fail with EINVAL: %+v", err)
	}
	if _, err := node.Rename(opts, "1", ""); !errors.Is(err, os.ErrInvalid) {
		t.Errorf("replacing root did not fail with EINVAL: %+v", err)
	}
}

func TestStat(t *testing.T) {
	node := &safNode{
		uri:            "0",
		name:           "1",
		size:           2,
		mtime:          3,
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safExpired,
		watchManager:   &safWatchManager{},
	}

	_, opts := newTestClient()

	info, err := node.Stat(opts, "")
	if err != nil {
		t.Fatal(err)
	}

	if info.Uri != node.uri {
		t.Errorf("invalid URI: %q != %q", info.Uri, node.uri)
	} else if info.Name_ != node.name {
		t.Errorf("invalid name: %q != %q", info.Name_, node.name)
	} else if info.Size_ != node.size {
		t.Errorf("invalid size: %v != %v", info.Size_, node.size)
	} else if info.Mtime != node.mtime {
		t.Errorf("invalid mtime: %v != %v", info.Mtime, node.mtime)
	} else if info.IsDir_ != (node.children != nil) {
		t.Errorf("invalid isDir: %v != %v", info.IsDir_, node.children != nil)
	}
}

type mockObserver struct{}

func (mo *mockObserver) Cancel() {}

func TestWatch(t *testing.T) {
	watchManager := &safWatchManager{}
	node := &safNode{
		uri:            "0",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"] = &safNode{
		parent:         node,
		uri:            "1",
		name:           "1",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}

	client, opts := newTestClient()
	client.observeDocument = func(documentUri string, changeListener SafChangeListener) (SafObserver, error) {
		go func() { changeListener.OnChange() }()
		return &mockObserver{}, nil
	}

	ctx := context.TODO()
	out := make(chan fs.Event)

	if err := watchManager.Start(opts, node, ctx, &mockMatcher{}, out); err != nil {
		t.Fatal(err)
	}

	events := []string{}
	for range 2 {
		events = append(events, (<-out).Name)
	}
	slices.Sort(events)

	expected := []string{"", "1"}

	if !slices.Equal(events, expected) {
		t.Errorf("invalid events: %+v != %+v", events, expected)
	}
	if node.childrenExpiry != safExpired {
		t.Errorf("root's children not expired: %v", node.childrenExpiry)
	}
	if node.children["1"].childrenExpiry != safExpired {
		t.Errorf("child's children not expired: %v", node.children["1"].childrenExpiry)
	}

	if err := watchManager.Stop(node); err != nil {
		t.Fatal(err)
	}

	if node.observer != nil {
		t.Errorf("root's observer still exists: %+v", node.observer)
	}
	if node.children["1"].observer != nil {
		t.Errorf("child's observer still exists: %+v", node.children["1"].observer)
	}
}

func TestGlob(t *testing.T) {
	watchManager := &safWatchManager{}
	node := &safNode{
		uri:            "0",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"] = &safNode{
		parent:         node,
		uri:            "1",
		name:           "1",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}
	node.children["1"].children["a"] = &safNode{
		parent:         node.children["1"],
		uri:            "a",
		name:           "a",
		infoExpiry:     safNotExpired,
		childrenExpiry: safExpired,
		watchManager:   watchManager,
	}
	node.children["2"] = &safNode{
		parent:         node,
		uri:            "2",
		name:           "2",
		infoExpiry:     safNotExpired,
		children:       makeChildren(true),
		childrenExpiry: safNotExpired,
		watchManager:   watchManager,
	}

	_, opts := newTestClient()

	for _, i := range []struct {
		glob     string
		expected []string
	}{
		{glob: "", expected: []string{}},
		{glob: "3", expected: []string{}},
		{glob: "1", expected: []string{"1"}},
		{glob: "*", expected: []string{"1", "2"}},
		{glob: "*/a", expected: []string{"1/a"}},
		{glob: "*/[abc]", expected: []string{"1/a"}},
		{glob: "*/*", expected: []string{"1/a"}},
		{glob: "*/*/*", expected: []string{}},
	} {
		results, err := node.Glob(opts, i.glob)
		if err != nil {
			t.Fatal(err)
		}

		slices.Sort(results)

		if !slices.Equal(results, i.expected) {
			t.Errorf("%q: invalid matches: %+v != %+v", i.glob, results, i.expected)
		}
	}
}
