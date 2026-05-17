// SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

package stbridge

// #include <android/api-level.h>
import "C"

import (
	// This package's init() MUST run first.
	_ "stbridge/pidfdhack"

	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"iter"
	"log"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	_ "unsafe"

	_ "golang.org/x/mobile/event/key"
	"golang.org/x/sys/unix"

	_ "github.com/syncthing/syncthing/cmd/syncthing/cli"
	"github.com/syncthing/syncthing/lib/build"
	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/events"
	"github.com/syncthing/syncthing/lib/fs"
	"github.com/syncthing/syncthing/lib/locations"
	"github.com/syncthing/syncthing/lib/model"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/rand"
	"github.com/syncthing/syncthing/lib/svcutil"
	"github.com/syncthing/syncthing/lib/syncthing"

	"github.com/cuhsat/go-zip/pkg/zip"
)

var stLock sync.Mutex

func Version() string {
	return build.Version
}

func cleanOldFiles() {
	// We only clean up a subset of what upstream syncthing does since the
	// initial release started with 2.x and certain features aren't enabled.
	globs := map[string]time.Duration{
		"panic-*.log":      7 * 24 * time.Hour,
		"config.xml.v*":    30 * 24 * time.Hour,
		"support-bundle-*": 30 * 24 * time.Hour,
	}

	configFs := fs.NewFilesystem(fs.FilesystemTypeBasic, locations.GetBaseDir(locations.ConfigBaseDir))

	for glob, dur := range globs {
		entries, err := configFs.Glob(glob)
		if err != nil {
			log.Printf("Failed to match glob: %q: %v", glob, err)
			continue
		}

		for _, entry := range entries {
			info, err := configFs.Lstat(entry)
			if err != nil {
				log.Printf("Failed to stat config: %q: %v", entry, err)
				continue
			}

			if time.Since(info.ModTime()) <= dur {
				log.Printf("Skipped deleting old config: %q", entry)
				continue
			}

			if err = configFs.RemoveAll(entry); err != nil {
				log.Printf("Failed to delete old config: %q: %v", entry, err)
				continue
			}

			log.Printf("Deleted old config: %q: %v", entry, err)
		}
	}
}

func readPemCert(path string) (*x509.Certificate, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read certificate: %q: %w", path, err)
	}

	var cert *x509.Certificate

	for len(data) > 0 {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			break
		}
		if block.Type != "CERTIFICATE" || len(block.Headers) != 0 {
			continue
		}

		certBytes := block.Bytes
		c, err := x509.ParseCertificate(certBytes)
		if err != nil {
			return nil, fmt.Errorf("failed to parse certificate: %q: %w", path, err)
		} else if cert != nil {
			return nil, fmt.Errorf("multiple certificates in file: %q: %w", path, err)
		}

		cert = c
	}

	if cert == nil {
		return nil, fmt.Errorf("no certificates in file: %q: %w", path, err)
	}

	return cert, nil
}

//go:linkname resetProxyConfig net/http.resetProxyConfig
func resetProxyConfig()

// This is not thread-safe and should only be called by run().
func applyProxySettings(proxy string, no_proxy string) {
	if len(proxy) > 0 {
		log.Printf("Setting proxy to %q", proxy)

		os.Setenv("http_proxy", proxy)
		os.Setenv("https_proxy", proxy)
	} else {
		log.Print("Clearing proxy settings")

		os.Unsetenv("http_proxy")
		os.Unsetenv("https_proxy")
	}

	if len(no_proxy) > 0 {
		log.Printf("Setting no_proxy to %q", no_proxy)

		os.Setenv("no_proxy", no_proxy)
	} else {
		log.Print("Clearing no_proxy settings")

		os.Unsetenv("no_proxy")
	}

	resetProxyConfig()
}

//go:linkname slogutilSetDefaultLevel github.com/syncthing/syncthing/internal/slogutil.SetDefaultLevel
func slogutilSetDefaultLevel(level slog.Level)

// This is thread-safe because syncthing's internal levelTracker.SetDefault() is
// thread-safe.
func SetLogLevel(level string) error {
	var slogLevel slog.Level

	if err := slogLevel.UnmarshalText([]byte(level)); err != nil {
		return fmt.Errorf("invalid log level: %q", level)
	}

	slogutilSetDefaultLevel(slogLevel)

	return nil
}

func InitDirs(filesDir string, cacheDir string) error {
	stLock.Lock()
	defer stLock.Unlock()

	configDir := filepath.Join(filesDir, "syncthing")
	if err := locations.SetBaseDir(locations.ConfigBaseDir, configDir); err != nil {
		return fmt.Errorf("failed to set config directory: %w", err)
	} else if err := locations.SetBaseDir(locations.DataBaseDir, configDir); err != nil {
		return fmt.Errorf("failed to set data directory: %w", err)
	}
	log.Print(locations.PrettyPaths())

	// Older Android versions do not set TMPDIR, causing bionic and the go
	// runtime to default to /data/local/tmp, which we can't write to.
	//
	// https://android.googlesource.com/platform/frameworks/base/+/d5ccb038f69193fb63b5169d7adc5da19859c9d8%5E%21/
	if _, ok := os.LookupEnv("TMPDIR"); !ok {
		os.Setenv("TMPDIR", cacheDir)
	}

	return nil
}

func guiHostPort(c *config.Configuration) (string, int, error) {
	if c.GUI.Network() != "tcp" {
		return "", 0, fmt.Errorf("non-TCP GUI address: %q", c.GUI.RawAddress)
	}

	guiHost, guiPortStr, err := net.SplitHostPort(c.GUI.RawAddress)
	if err != nil {
		return "", 0, fmt.Errorf("invalid GUI address: %q: %w", c.GUI.RawAddress, err)
	}

	guiPort, err := strconv.Atoi(guiPortStr)
	if err != nil {
		return "", 0, fmt.Errorf("invalid GUI port: %q: %w", guiPortStr, err)
	}

	return guiHost, guiPort, nil
}

//go:linkname configGetFreePort github.com/syncthing/syncthing/lib/config.getFreePort
func configGetFreePort(host string, ports ...int) (int, error)

func tryPreserveGuiHostPort(c *config.Configuration) error {
	const defaultPort = 8384

	guiHost, guiPort, err := guiHostPort(c)
	if err != nil {
		log.Printf("Resetting GUI address: %v", err)
		guiHost = "127.0.0.1"
		guiPort = defaultPort
	}

	guiPort, err = configGetFreePort(guiHost, guiPort, defaultPort)
	if err != nil {
		return fmt.Errorf("failed to find free port for GUI: %w", err)
	}

	c.GUI.RawAddress = net.JoinHostPort(guiHost, strconv.Itoa(guiPort))

	return nil
}

type SyncthingApp struct {
	app     *syncthing.App
	cfg     config.Wrapper
	guiCert *x509.Certificate
}

func (app *SyncthingApp) StopAsync() {
	go app.app.Stop(svcutil.ExitSuccess)
}

func (app *SyncthingApp) IsConnectAllowed() bool {
	return app.cfg.Options().ConnectAllowed
}

func (app *SyncthingApp) SetConnectAllowed(allowed bool) error {
	_, err := app.cfg.Modify(func(cfg *config.Configuration) {
		cfg.Options.ConnectAllowed = allowed
	})
	if err != nil {
		return fmt.Errorf("failed to set connect allowed state: %v: %w", allowed, err)
	}

	return nil
}

func (app *SyncthingApp) GuiAddress() string {
	return app.cfg.GUI().URL()
}

func (app *SyncthingApp) GuiUser() string {
	return app.cfg.GUI().User
}

func (app *SyncthingApp) GuiApiKey() string {
	return app.cfg.GUI().APIKey
}

func (app *SyncthingApp) GuiTlsCert() []byte {
	return app.guiCert.Raw
}

type SyncthingStatusReceiver interface {
	OnSyncthingStarted(app *SyncthingApp)

	OnSyncthingStopped(app *SyncthingApp)

	// Can be sent before OnSyncthingStarted, but not after OnSyncthingStopped.
	// The list of paths is separated by a '\0' byte because gomobile does not
	// support passing a list of strings to the JVM. This works for arbitrary
	// paths on Linux.
	OnConflictsUpdated(paths0Sep string)

	OnAlertsUpdated(count int32)

	OnBusyFoldersUpdated(count int32)

	OnConnectedDevicesUpdated(count int32)
}

// db.DB is internal, so it's unnameable and we can't create a function that
// accepts one. We only need the AllLocalFiles() function though, so just pass
// that as a function pointer instead.
type allLocalFilesFunc func(
	folder string,
	device protocol.DeviceID,
) (iter.Seq[protocol.FileInfo], func() error)

func isConflict(name string) bool {
	return strings.Contains(filepath.Base(name), ".sync-conflict-")
}

type conflictsInfo struct {
	byFolder    map[string]map[string]struct{}
	folderPaths map[string]string
}

func dispatchConflicts(
	conflictsInfo *conflictsInfo,
	receiver SyncthingStatusReceiver,
) {
	var paths0Sep strings.Builder

	for folder, names := range conflictsInfo.byFolder {
		folderPath := conflictsInfo.folderPaths[folder]

		for name, _ := range names {
			if paths0Sep.Len() > 0 {
				paths0Sep.WriteRune('\u0000')
			}
			paths0Sep.WriteString(filepath.Join(folderPath, name))
		}
	}

	receiver.OnConflictsUpdated(paths0Sep.String())
}

// The only type of alerts we currently don't track are those associated
// slogutil.ErrorRecorder because it's a pain to deal with more internal types.
type alertsInfo struct {
	needsRestart   bool
	pendingDevices map[string]struct{}
	pendingFolders map[string]struct{}
	watcherErrors  map[string]string
}

func dispatchAlerts(
	alertsInfo *alertsInfo,
	receiver SyncthingStatusReceiver,
) {
	count := 0

	if alertsInfo.needsRestart {
		count += 1
	}

	count += len(alertsInfo.pendingDevices)
	count += len(alertsInfo.pendingFolders)
	count += len(alertsInfo.watcherErrors)

	receiver.OnAlertsUpdated(int32(count))
}

// The scanning states are intentionally excluded because we only want mutating
// operations to interrupt the idle timer.
var busyEvents = []string{
	model.FolderSyncWaiting.String(),
	model.FolderSyncPreparing.String(),
	model.FolderSyncing.String(),
	model.FolderCleaning.String(),
	model.FolderCleanWaiting.String(),
	model.FolderStarting.String(),
}

func dispatchBusyFolders(
	folderStates map[string]string,
	receiver SyncthingStatusReceiver,
) {
	busyCount := int32(0)

	for _, state := range folderStates {
		if slices.Contains(busyEvents, state) {
			busyCount += 1
		}
	}

	receiver.OnBusyFoldersUpdated(busyCount)
}

func dispatchConnectedDevices(
	devicesConnected map[string]struct{},
	receiver SyncthingStatusReceiver,
) {
	receiver.OnConnectedDevicesUpdated(int32(len(devicesConnected)))
}

func eventLoop(
	ctx context.Context,
	stopped chan struct{},
	evLogger events.Logger,
	cfgWrapper config.Wrapper,
	conflictsInfo *conflictsInfo,
	alertsInfo *alertsInfo,
	receiver SyncthingStatusReceiver,
) {
	defer close(stopped)

	sub := evLogger.Subscribe(
		events.LocalChangeDetected |
			events.RemoteChangeDetected |
			events.PendingDevicesChanged |
			events.PendingFoldersChanged |
			events.FolderWatchStateChanged |
			events.StateChanged |
			events.DeviceConnected |
			events.DeviceDisconnected |
			events.ConfigSaved,
	)
	defer sub.Unsubscribe()

	devicesConnected := map[string]struct{}{}
	folderStates := map[string]string{}

	for {
		select {
		case evt := <-sub.C():
			switch evt.Type {
			// We do not use LocalIndexUpdated because it does not distinguish
			// between added/modified and removed. Also, emitDiskChangeEvents()
			// already skips files where FileInfo.IsInvalid() returns true, so
			// we don't need to manually filter out ignored paths.
			case events.LocalChangeDetected, events.RemoteChangeDetected:
				data := evt.Data.(map[string]string)
				folderID := data["folder"]
				path := data["path"]

				if !isConflict(path) {
					continue
				}

				if data["action"] == "deleted" {
					delete(conflictsInfo.byFolder[folderID], path)
				} else {
					if _, ok := conflictsInfo.byFolder[folderID]; !ok {
						conflictsInfo.byFolder[folderID] = map[string]struct{}{}
					}
					conflictsInfo.byFolder[folderID][path] = struct{}{}
				}

				dispatchConflicts(conflictsInfo, receiver)

			case events.PendingDevicesChanged:
				if data, ok := evt.Data.(map[string][]interface{}); ok {
					for _, device := range data["added"] {
						deviceID := device.(map[string]string)["deviceID"]

						alertsInfo.pendingDevices[deviceID] = struct{}{}
					}
				} else if data, ok := evt.Data.(map[string]interface{}); ok {
					devices := data["removed"].([]map[string]string)

					for _, device := range devices {
						deviceID := device["deviceID"]

						delete(alertsInfo.pendingDevices, deviceID)
					}
				}

				dispatchAlerts(alertsInfo, receiver)

			case events.PendingFoldersChanged:
				data := evt.Data.(map[string]interface{})

				if added, ok := data["added"]; ok {
					// This is ugly and slow, but better than using
					// unsafe.Pointer to cast to model.updatedPendingFolder.
					rawJson, err := json.Marshal(added)
					if err != nil {
						log.Printf("Failed to serialize JSON: %+v: %v", added, err)
						continue
					}

					folders := []map[string]interface{}{}
					err = json.Unmarshal(rawJson, &folders)
					if err != nil {
						log.Printf("Failed to deserialize JSON: %q: %v", string(rawJson), err)
						continue
					}

					for _, folder := range folders {
						folderID := folder["folderID"].(string)

						alertsInfo.pendingFolders[folderID] = struct{}{}
					}
				}

				if removed, ok := data["removed"]; ok {
					folders := removed.([]map[string]string)

					for _, folder := range folders {
						folderID := folder["folderID"]

						delete(alertsInfo.pendingFolders, folderID)
					}
				}

				dispatchAlerts(alertsInfo, receiver)

			case events.FolderWatchStateChanged:
				data := evt.Data.(map[string]interface{})
				folderID := data["folder"].(string)

				if errMsg, ok := data["to"]; ok {
					alertsInfo.watcherErrors[folderID] = errMsg.(string)
				} else {
					delete(alertsInfo.watcherErrors, folderID)
				}

				dispatchAlerts(alertsInfo, receiver)

			case events.StateChanged:
				data := evt.Data.(map[string]interface{})
				folder := data["folder"].(string)
				state := data["to"].(string)

				folderStates[folder] = state

				dispatchBusyFolders(folderStates, receiver)

			case events.DeviceConnected:
				data := evt.Data.(map[string]string)
				deviceID := data["id"]

				devicesConnected[deviceID] = struct{}{}

				dispatchConnectedDevices(devicesConnected, receiver)

			case events.DeviceDisconnected:
				data := evt.Data.(map[string]string)
				deviceID := data["id"]

				delete(devicesConnected, deviceID)

				dispatchConnectedDevices(devicesConnected, receiver)

			// When a folder is deleted, we need to manually clear out the
			// corresponding conflicts because we will not receive deletion
			// events for them.
			case events.ConfigSaved:
				cfg := evt.Data.(config.Configuration)

				clear(conflictsInfo.folderPaths)

				for _, folder := range cfg.Folders {
					// Never fails on Android.
					conflictsInfo.folderPaths[folder.ID], _ = fs.ExpandTilde(folder.Path)
				}

				for key := range conflictsInfo.byFolder {
					if _, ok := conflictsInfo.folderPaths[key]; !ok {
						delete(conflictsInfo.byFolder, key)
					}
				}

				for key := range folderStates {
					if _, ok := conflictsInfo.folderPaths[key]; !ok {
						delete(folderStates, key)
					}
				}

				dispatchConflicts(conflictsInfo, receiver)
				dispatchBusyFolders(folderStates, receiver)
				// Unlike folders, we don't need to remove deleted devices from
				// devicesConnected. We'll always receive a disconnection event
				// when connections are closed during deletion.

				// A config.Wrapper is needed to determine if a restart is
				// required. config.Configuration does not contain enough info.
				// We do not need to worry about TOCTOU here because once the
				// flag is set, it cannot ever be unset.
				alertsInfo.needsRestart = cfgWrapper.RequiresRestart()
				dispatchAlerts(alertsInfo, receiver)

			default:
				log.Printf("Unexpected event: %+v", evt)
			}

		case <-ctx.Done():
			return
		}
	}
}

func startEventLoop(
	ctx context.Context,
	stopped chan struct{},
	evLogger events.Logger,
	cfg config.Wrapper,
	allLocalFiles allLocalFilesFunc,
	receiver SyncthingStatusReceiver,
) error {
	conflictsInfo := conflictsInfo{
		byFolder:    map[string]map[string]struct{}{},
		folderPaths: map[string]string{},
	}

	// Find the initial set of conflicts from the database before starting the
	// service. Any newly added or deleted conflicts will be reported by the
	// service via events, so we should always have a consistent view of the
	// world.
	for _, folder := range cfg.FolderList() {
		dbFiles, errFn := allLocalFiles(folder.ID, protocol.LocalDeviceID)
		for dbFile := range dbFiles {
			if !isConflict(dbFile.Name) || dbFile.IsDeleted() || dbFile.IsInvalid() {
				continue
			}

			if _, ok := conflictsInfo.byFolder[folder.ID]; !ok {
				conflictsInfo.byFolder[folder.ID] = map[string]struct{}{}
			}
			conflictsInfo.byFolder[folder.ID][dbFile.Name] = struct{}{}
		}
		if err := errFn(); err != nil {
			return fmt.Errorf("failed to query database for: %q: %w", folder.ID, err)
		}

		// Never fails on Android.
		conflictsInfo.folderPaths[folder.ID], _ = fs.ExpandTilde(folder.Path)
	}

	dispatchConflicts(&conflictsInfo, receiver)

	alertsInfo := alertsInfo{
		needsRestart:   cfg.RequiresRestart(),
		pendingDevices: map[string]struct{}{},
		pendingFolders: map[string]struct{}{},
		watcherErrors:  map[string]string{},
	}

	dispatchAlerts(&alertsInfo, receiver)

	go eventLoop(
		ctx,
		stopped,
		evLogger,
		cfg,
		&conflictsInfo,
		&alertsInfo,
		receiver,
	)

	return nil
}

type SyncthingStartupConfig struct {
	DeviceModel string
	Proxy       string
	NoProxy     string
	Receiver    SyncthingStatusReceiver
}

func Run(startup *SyncthingStartupConfig) error {
	stLock.Lock()
	defer stLock.Unlock()

	applyProxySettings(startup.Proxy, startup.NoProxy)

	for _, dir := range []locations.BaseDirEnum{locations.ConfigBaseDir, locations.DataBaseDir} {
		if err := syncthing.EnsureDir(locations.GetBaseDir(dir), 0o700); err != nil {
			return fmt.Errorf("failed to create directory: %q: %v", dir, err)
		}
	}

	cert, err := syncthing.LoadOrGenerateCertificate(
		locations.Get(locations.CertFile),
		locations.Get(locations.KeyFile),
	)
	if err != nil {
		return fmt.Errorf("failed to load or generate certificate: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	evLogger := events.NewLogger()
	go evLogger.Serve(ctx)

	cfg, err := syncthing.LoadConfigAtStartup(locations.Get(locations.ConfigFile), cert, evLogger, false, true)
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}
	go cfg.Serve(ctx)

	waiter, err := cfg.Modify(func(c *config.Configuration) {
		// Try to stick with existing ports, but always allow picking new ones
		// so that running multiple instances of the app (eg. for debugging) is
		// possible. This intentionally does not use c.ProbeFreePorts() because
		// that always resets the listen addresses for the sync protocol.
		if err = tryPreserveGuiHostPort(c); err != nil {
			log.Printf("Failed to set GUI listen address: %v", err)
		}

		// Try to prevent users from locking themselves out.
		c.GUI.Enabled = true

		// Just use HTTPS instead of forcing Android to permit HTTP connections.
		c.GUI.RawUseTLS = true

		// Prevent insecure authentication.
		if len(c.GUI.User) == 0 {
			log.Printf("Setting username to random string")
			c.GUI.User = rand.String(32)
		}
		if len(c.GUI.APIKey) == 0 {
			log.Printf("Setting API key to random string")
			c.GUI.APIKey = rand.String(32)
		}

		// There is no good way to set "X-Api-Key" nor "Authorization: Bearer"
		// in Android's WebView. The only way to pass in additional headers is
		// when calling the initial loadUrl() and basic authentication is the
		// only method that'll persist in the session. We'll force the password
		// to be the API key so that we always know its value.
		if c.GUI.CompareHashedPassword(c.GUI.APIKey) != nil {
			log.Printf("Setting password to API key")
			c.GUI.SetPassword(c.GUI.APIKey)
		}

		// This can't work on Android.
		c.Options.StartBrowser = false

		// Disable crash reports since they are not debuggable by upstream.
		c.Options.CREnabled = false

		// /sdcard does not support permissions.
		c.Defaults.Folder.IgnorePerms = true
		// Reduce CPU usage due to file hashing.
		c.Defaults.Folder.Hashers = 1

		for _, folder := range c.Folders {
			folder.IgnorePerms = true
			folder.Hashers = 1

			c.SetFolder(folder)
		}

		// Set device name to model name.
		device, _, _ := c.Device(cfg.MyID())
		hostname, _ := os.Hostname()
		if device.Name == hostname {
			device.Name = startup.DeviceModel

			c.SetDevice(device)
		}
	})
	if err != nil {
		return fmt.Errorf("failed to override config options: %w", err)
	}
	waiter.Wait()

	err = cfg.Save()
	if err != nil {
		return fmt.Errorf("failed to save overridden config: %w", err)
	}

	dbDeleteRetentionInterval := time.Duration(10920) * time.Hour
	if err := syncthing.TryMigrateDatabase(ctx, dbDeleteRetentionInterval); err != nil {
		return fmt.Errorf("failed to migrate old database: %w", err)
	}

	sdb, err := syncthing.OpenDatabase(locations.Get(locations.Database), dbDeleteRetentionInterval)
	if err != nil {
		return fmt.Errorf("failed to open database: %w", err)
	}

	cleanOldFiles()

	appOpts := syncthing.Options{
		NoUpgrade:             true,
		ProfilerAddr:          "",
		ResetDeltaIdxs:        false,
		DBMaintenanceInterval: time.Duration(8) * time.Hour,
	}

	app, err := syncthing.New(cfg, sdb, evLogger, cert, appOpts)
	if err != nil {
		return fmt.Errorf("failed to initialize syncthing: %w", err)
	}

	eventLoopStopped := make(chan struct{})
	if err = startEventLoop(ctx, eventLoopStopped, evLogger, cfg,
		sdb.AllLocalFiles, startup.Receiver); err != nil {
		return fmt.Errorf("failed to start event loop: %w", err)
	}

	if err = app.Start(); err != nil {
		return fmt.Errorf("failed to start syncthing: %w", app.Error())
	}

	// The GUI TLS certificate generation process is synchronous, so it's
	// guaranteed to exist now.
	guiCert, err := readPemCert(locations.Get(locations.HTTPSCertFile))
	if err != nil {
		return fmt.Errorf("failed to load GUI TLS certificate: %w", err)
	}

	appWrapper := &SyncthingApp{
		app:     app,
		cfg:     cfg,
		guiCert: guiCert,
	}

	startup.Receiver.OnSyncthingStarted(appWrapper)

	status := app.Wait()

	// Ensure we don't send any more events after OnSyncthingStop().
	cancel()
	<-eventLoopStopped

	startup.Receiver.OnSyncthingStopped(appWrapper)

	if status == svcutil.ExitError {
		return fmt.Errorf("failed when stopping syncthing: %w", app.Error())
	}

	return nil
}

func isDatabase(relPath string) bool {
	return strings.HasPrefix(filepath.Clean(relPath), "index-")
}

func ZipErrorWrongPassword() string {
	return zip.ErrPassword.Error()
}

func tryAtomicSwap(path1 string, path2 string) error {
	err := unix.Renameat2(unix.AT_FDCWD, path1, unix.AT_FDCWD, path2, unix.RENAME_EXCHANGE)
	if err == nil || !errors.Is(err, syscall.ENOSYS) {
		return err
	}

	log.Printf("Using non-atomic rename because RENAME_EXCHANGE is not supported: %v", err)

	swapDir, err := os.MkdirTemp(filepath.Dir(path2), "swap")
	if err != nil {
		return err
	}

	err = unix.Rename(path1, swapDir)
	if err != nil {
		return err
	}

	err = unix.Rename(path2, path1)
	if err != nil {
		return err
	}

	return unix.Rename(swapDir, path2)
}

func ImportConfiguration(fd int, name string, password string) error {
	stLock.Lock()
	defer stLock.Unlock()

	file := os.NewFile(uintptr(fd), name)
	if file == nil {
		return fmt.Errorf("failed to open fd: %d", fd)
	}
	defer file.Close()

	fileSize, err := file.Seek(0, io.SeekEnd)
	if err != nil {
		return fmt.Errorf("failed to determine file size: %d: %w", fd, err)
	}

	reader, err := zip.NewReader(file, fileSize)
	if err != nil {
		return err
	}

	tempDir, err := os.MkdirTemp("", "config_import")
	if err != nil {
		return fmt.Errorf("failed to create temp dir: %w", err)
	}
	defer func() {
		if err := os.RemoveAll(tempDir); err != nil {
			log.Printf("failed to delete: %q: %v", tempDir, err)
		}
	}()

	extractEntry := func(f *zip.File) error {
		if f.IsEncrypted() && password != "" {
			f.SetPassword(password)
		}

		entry, err := f.Open()
		if err != nil {
			if err == zip.ErrPassword {
				// We programmatically match on this string.
				return err
			} else {
				return fmt.Errorf("failed to open file entry: %q: %w", f.Name, err)
			}
		}
		defer entry.Close()

		if f.FileInfo().IsDir() {
			return nil
		}

		// We intentionally skip the database to ensure that Syncthing pulls
		// down missing files instead of assuming that they were deleted. The
		// service could take much longer to rescan the folders if all of the
		// data is already present, but this is much safer.
		if isDatabase(f.Name) {
			log.Printf("Skipping: %q", f.Name)
			return nil
		}

		// Join() normalizes the path too.
		path := filepath.Join(tempDir, f.Name)
		if !strings.HasPrefix(path, tempDir+string(os.PathSeparator)) {
			return fmt.Errorf("unsafe entry path: %q", f.Name)
		}

		parent := filepath.Dir(path)
		if err = os.MkdirAll(parent, 0o700); err != nil {
			return fmt.Errorf("failed to create directory: %q: %w", parent, err)
		}

		output, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, f.Mode()&0o700)
		if err != nil {
			return fmt.Errorf("failed to open for writing: %q: %w", path, err)
		}
		defer output.Close()

		if _, err = io.Copy(output, entry); err != nil {
			return fmt.Errorf("failed to write file data: %q: %w", path, err)
		}

		return nil
	}

	for _, f := range reader.File {
		if err := extractEntry(f); err != nil {
			return err
		}
	}

	configDir := locations.GetBaseDir(locations.ConfigBaseDir)

	// Try to atomically swap the active config dir with the temp dir. The temp
	// dir cleanup above will delete the old files.
	err = tryAtomicSwap(tempDir, configDir)
	if err != nil {
		return fmt.Errorf("failed to swap: %q <-> %q: %w", tempDir, configDir, err)
	}

	return nil
}

func ExportConfiguration(fd int, name string, password string) error {
	stLock.Lock()
	defer stLock.Unlock()

	file := os.NewFile(uintptr(fd), name)
	if file == nil {
		return fmt.Errorf("failed to open fd: %d", fd)
	}
	defer file.Close()

	writer := zip.NewWriter(file)
	defer writer.Close()

	configDir := locations.GetBaseDir(locations.ConfigBaseDir)

	walker := func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return fmt.Errorf("failed when walking: %q: %w", configDir, err)
		}

		if !info.Mode().IsRegular() {
			return nil
		}

		relPath, err := filepath.Rel(configDir, path)
		if err != nil {
			return fmt.Errorf("failed to compute relative path: %q: %w", path, err)
		}

		if isDatabase(relPath) {
			log.Printf("Skipping: %q", relPath)
			return nil
		}

		input, err := os.Open(path)
		if err != nil {
			return fmt.Errorf("failed to open for reading: %q: %w", path, err)
		}
		defer input.Close()

		var entry io.Writer
		if password == "" {
			entry, err = writer.Create(relPath)
		} else {
			entry, err = writer.Encrypt(relPath, password, zip.AES256Encryption)
		}
		if err != nil {
			return fmt.Errorf("failed to create file entry: %q: %w", relPath, err)
		}

		if _, err = io.Copy(entry, input); err != nil {
			return fmt.Errorf("failed to write file data: %q: %w", relPath, err)
		}

		return nil
	}

	if err := filepath.Walk(configDir, walker); err != nil {
		return fmt.Errorf("failed to walk: %q: %w", configDir, err)
	}

	return nil
}
