/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.syncthing

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.chiller3.basicsync.Notifications
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.binding.stbridge.Stbridge
import com.chiller3.basicsync.binding.stbridge.SyncthingApp
import com.chiller3.basicsync.binding.stbridge.SyncthingStartupConfig
import com.chiller3.basicsync.binding.stbridge.SyncthingStatusReceiver
import java.io.IOException
import java.util.EnumSet

class SyncthingService : Service(), SyncthingStatusReceiver, DeviceStateListener,
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = SyncthingService::class.java.simpleName

        private val BLOCKED_REASONS_PREFS = arrayOf(
            Preferences.PREF_MANUAL_MODE,
            Preferences.PREF_MANUAL_SHOULD_RUN,
        )
        private val STATE_CHANGE_PREFS = arrayOf(
            Preferences.PREF_KEEP_ALIVE,
            Preferences.PREF_SHOW_EXIT,
        )

        val ACTION_AUTO_MODE = "${SyncthingService::class.java.canonicalName}.auto_mode"
        val ACTION_MANUAL_MODE = "${SyncthingService::class.java.canonicalName}.manual_mode"
        val ACTION_START = "${SyncthingService::class.java.canonicalName}.start"
        val ACTION_STOP = "${SyncthingService::class.java.canonicalName}.stop"
        val ACTION_RENOTIFY = "${SyncthingService::class.java.canonicalName}.renotify"
        val ACTION_RECHECK = "${SyncthingService::class.java.canonicalName}.restart"
        val ACTION_EXIT = "${SyncthingService::class.java.canonicalName}.exit"

        private val isRunningListeners = HashSet<OnServiceRunningChange>()
        private var isRunning: Boolean = false
            set(value) {
                synchronized(isRunningListeners) {
                    field = value
                    for (listener in isRunningListeners) {
                        listener.onServiceRunningChanged(value)
                    }
                }
            }

        fun registerIsRunningListener(listener: OnServiceRunningChange) {
            synchronized(isRunningListeners) {
                if (!isRunningListeners.add(listener)) {
                    Log.w(TAG, "Listener was already registered: $listener")
                }

                listener.onServiceRunningChanged(isRunning)
            }
        }

        fun unregisterIsRunningListener(listener: OnServiceRunningChange) {
            synchronized(isRunningListeners) {
                if (!isRunningListeners.remove(listener)) {
                    Log.w(TAG, "Listener was never registered: $listener")
                }
            }
        }

        fun createIntent(context: Context, action: String?) =
            Intent(context, SyncthingService::class.java).apply {
                this.action = action
            }

        fun start(context: Context, action: String?) {
            context.startForegroundService(createIntent(context, action))
        }

        private fun unpack0Sep(str0Sep: String): MutableList<String> =
            mutableListOf<String>().apply {
                if (str0Sep.isNotEmpty()) {
                    str0Sep.splitToSequence('\u0000').toCollection(this)
                }
            }

        fun encodeSafUri(uri: Uri, path: String? = null) = buildString {
            append(Uri.encode(uri.toString()))

            if (path != null) {
                append('/')
                append(path)
            }
        }

        fun decodeSafUri(encoded: String): Pair<Uri, String?> {
            val separator = encoded.indexOf('/')
            val (uriEncoded, path) = if (separator < 0) {
                encoded to null
            } else {
                encoded.substring(0, separator) to encoded.substring(separator + 1)
            }
            val uri = Uri.decode(uriEncoded).toUri()

            return uri to path
        }

        fun persistExternalStoragePermissions(context: Context, uri: Uri) {
            // Permissions are released in onCheckStoragePermissions() when unneeded.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    interface OnServiceRunningChange {
        fun onServiceRunningChanged(isRunning: Boolean)
    }

    enum class RunState {
        RUNNING,
        NOT_RUNNING,
        PAUSED,
        STARTING,
        STOPPING,
        PAUSING,
        IMPORTING,
        EXPORTING;

        val showFolderStates: Boolean
            get() = this == RUNNING

        val showBlockedReasons: Boolean
            get() = this == NOT_RUNNING || this == PAUSED

        val webUiAvailable: Boolean
            get() = this == RUNNING || this == PAUSED || this == PAUSING
    }

    data class ServiceState(
        private val keepAlive: Boolean,
        val blockedReasons: EnumSet<BlockedReason>,
        private val isStarted: Boolean,
        private val isResumed: Boolean,
        private val manualMode: Boolean,
        private val allowAutoMode: Boolean,
        private val preRunAction: PreRunAction?,
        private val showExit: Boolean,
        val folderStates: FolderStates,
        val deviceStates: DeviceStates,
    ) {
        private val shouldResume: Boolean
            get() = blockedReasons.isEmpty()

        val runState: RunState
            get() = if (preRunAction != null) {
                when (preRunAction) {
                    is PreRunAction.Import -> RunState.IMPORTING
                    is PreRunAction.Export -> RunState.EXPORTING
                }
            } else if (isStarted) {
                if (isResumed) {
                    if (shouldResume) {
                        RunState.RUNNING
                    } else if (keepAlive) {
                        RunState.PAUSING
                    } else {
                        RunState.STOPPING
                    }
                } else {
                    if (shouldResume) {
                        RunState.STARTING
                    } else if (keepAlive) {
                        RunState.PAUSED
                    } else {
                        RunState.STOPPING
                    }
                }
            } else {
                if (isResumed) {
                    throw IllegalArgumentException("Service is resumed, but is not started?")
                } else {
                    if (shouldResume) {
                        RunState.STARTING
                    } else {
                        RunState.NOT_RUNNING
                    }
                }
            }

        val actions: List<String>
            get() = ArrayList<String>().apply {
                if (preRunAction == null) {
                    if (manualMode) {
                        if (allowAutoMode) {
                            add(ACTION_AUTO_MODE)
                        }

                        if (shouldResume) {
                            add(ACTION_STOP)
                        } else {
                            add(ACTION_START)
                        }
                    } else {
                        add(ACTION_MANUAL_MODE)
                    }

                    if (showExit) {
                        add(ACTION_EXIT)
                    }
                }
            }
    }

    data class Password(val value: String) {
        override fun toString(): String = "<password>"
    }

    sealed interface PreRunAction {
        val needRecheck: Boolean

        fun perform(context: Context)

        data class Import(val uri: Uri, val password: Password) : PreRunAction {
            override val needRecheck = true

            override fun perform(context: Context) {
                @SuppressLint("Recycle")
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Failed to open for reading: $uri")

                // stbridge will own the fd.
                Stbridge.importConfiguration(fd.detachFd().toLong(), uri.toString(), password.value)
            }
        }

        data class Export(val uri: Uri, val password: Password) : PreRunAction {
            override val needRecheck = false

            override fun perform(context: Context) {
                @SuppressLint("Recycle")
                val fd = context.contentResolver.openFileDescriptor(uri, "wt")
                    ?: throw IOException("Failed to open for writing: $uri")

                // stbridge will own the fd.
                Stbridge.exportConfiguration(fd.detachFd().toLong(), uri.toString(), password.value)
            }
        }
    }

    data class FolderStates(
        val idle: Int,
        val scanning: Int,
        val syncing: Int,
        val cleaning: Int,
        val errored: Int,
        val starting: Int,
    ) {
        constructor() : this(
            idle = 0,
            scanning = 0,
            syncing = 0,
            cleaning = 0,
            errored = 0,
            starting = 0,
        )
    }

    data class DeviceStates(
        val connected: Int,
        val syncing: Int,
        val pending: Int,
    ) {
        constructor() : this(
            connected = 0,
            syncing = 0,
            pending = 0,
        )
    }

    private lateinit var prefs: Preferences
    private lateinit var notifications: Notifications
    private val runnerThread = Thread(::runner)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val stateLock = Object()

    @GuardedBy("stateLock")
    private var lastServiceState: ServiceState? = null
    @GuardedBy("stateLock")
    private var lastUseLocation: Boolean = false

    private lateinit var deviceStateTracker: DeviceStateTracker
    @GuardedBy("stateLock")
    private var deviceState = DeviceState()
    @GuardedBy("stateLock")
    private var runningProxyInfo: ProxyInfo? = null

    @GuardedBy("stateLock")
    private var blockedReasons = EnumSet.noneOf(BlockedReason::class.java)

    // We want to check permissions on startup, even if the user currently set Syncthing to be
    // manually stopped.
    @GuardedBy("stateLock")
    private var recheckPermissions = true
    @GuardedBy("stateLock")
    private var missingInternal = false
    @GuardedBy("stateLock")
    private var missingExternal = emptyList<Uri>()

    @GuardedBy("stateLock")
    private var shouldThreadRun = true

    private val shouldResume: Boolean
        @GuardedBy("stateLock")
        get() = shouldThreadRun && blockedReasons.isEmpty()

    private val shouldStart: Boolean
        @GuardedBy("stateLock")
        get() = shouldThreadRun && (prefs.keepAlive || shouldResume)
                && blockedReasons.none { it.blocksStart }

    @GuardedBy("stateLock")
    private val preRunActions = mutableListOf<PreRunAction>()

    @GuardedBy("stateLock")
    private var currentPreRunAction: PreRunAction? = null

    @GuardedBy("stateLock")
    private var syncthingApp: SyncthingApp? = null
    @GuardedBy("stateLock")
    private var syncthingConflicts = emptyList<String>()
        set(conflicts) {
            if (field != conflicts) {
                field = conflicts

                allListeners { it.onConflictsUpdated(conflicts) }

                notifications.sendOrClearConflictsNotification(conflicts)
            }
        }
    @GuardedBy("stateLock")
    private var syncthingAlerts = 0
        set(count) {
            if (field != count) {
                field = count

                notifications.sendOrClearAlertsNotification(count)
            }
        }
    @GuardedBy("stateLock")
    private var syncthingFolderStates = FolderStates()
    @GuardedBy("stateLock")
    private var syncthingDeviceStates = DeviceStates()

    private val isResumed: Boolean
        @GuardedBy("stateLock")
        get() = if (prefs.keepAlive) {
            syncthingApp?.isConnectAllowed ?: false
        } else {
            isStarted
        }

    private val isStarted: Boolean
        @GuardedBy("stateLock")
        get() = syncthingApp != null

    private val guiInfo: GuiInfo?
        @GuardedBy("stateLock")
        get() = syncthingApp?.let {
            GuiInfo(
                address = it.guiAddress(),
                user = it.guiUser(),
                apiKey = it.guiApiKey(),
                cert = it.guiTlsCert(),
            )
        }

    @GuardedBy("stateLock")
    private val listeners = HashSet<ServiceListener>()

    @GuardedBy("stateLock")
    private fun allListeners(block: (ServiceListener) -> Unit) {
        HashSet(listeners).forEach(block)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        prefs = Preferences(this)
        prefs.registerListener(this)

        setLogLevel()

        notifications = Notifications(this)

        deviceStateTracker = DeviceStateTracker(this)
        deviceStateTracker.registerListener(this)

        runnerThread.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false

        // We intentionally don't wait for runnerThread to exit. Syncthing can sometimes take a few
        // seconds to fully exit, which can trigger an ANR warning. This is not a problem because if
        // a new service starts while Syncthing from this service is still shutting down, the global
        // lock in stbridge will just block the startup.
        synchronized(stateLock) {
            shouldThreadRun = false
        }
        stateChanged()

        synchronized(stateLock) {
            listeners.clear()
        }

        prefs.unregisterListener(this)

        deviceStateTracker.unregisterListener(this)

        Log.d(TAG, "Exiting")
    }

    override fun onBind(intent: Intent?): IBinder = ServiceBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        var forceShowNotification = false

        when (intent?.action) {
            ACTION_AUTO_MODE -> {
                if (prefs.allowAutoMode) {
                    prefs.isManualMode = false
                } else {
                    Log.d(TAG, "Ignoring switch to auto mode because auto mode is blocked")
                }
            }
            ACTION_MANUAL_MODE -> {
                // Keep the current state since the user has no way to know what the previously
                // saved state is anyway.
                prefs.manualShouldRun = shouldResume
                prefs.isManualMode = true
            }
            ACTION_START -> {
                // This is reachable in auto mode via remote control.
                prefs.manualShouldRun = true
                prefs.isManualMode = true
            }
            ACTION_STOP -> {
                // This is reachable in auto mode via remote control.
                prefs.manualShouldRun = false
                prefs.isManualMode = true
            }
            ACTION_RENOTIFY -> {
                forceShowNotification = true
            }
            ACTION_RECHECK -> {
                synchronized(stateLock) {
                    recheckPermissions = true
                }
            }
            ACTION_EXIT -> {
                allListeners { it.onExitRequested() }

                stopSelf()
                return START_NOT_STICKY
            }
            null -> {}
            else -> Log.w(TAG, "Ignoring unrecognized intent: $intent")
        }

        stateChanged(forceShowNotification = forceShowNotification)

        return START_STICKY
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.d(TAG, "Preference $key changed")

        var recomputeBlockedReasons = false

        // We have to switch foreground service and network callback types when location becomes
        // needed or no longer needed.
        val forceShowNotification = key == Preferences.PREF_ALLOWED_WIFI_NETWORKS

        when (key) {
            in BLOCKED_REASONS_PREFS, in DeviceState.PREFS -> recomputeBlockedReasons = true
            in STATE_CHANGE_PREFS -> {}
            Preferences.PREF_DEBUG_MODE -> {
                setLogLevel()
                return
            }
            else -> return
        }

        stateChanged(
            recomputeBlockedReasons = recomputeBlockedReasons,
            forceShowNotification = forceShowNotification,
        )
    }

    override fun onDeviceStateChanged(state: DeviceState) {
        synchronized(stateLock) {
            deviceState = state
            stateChanged(recomputeBlockedReasons = true)
        }
    }

    private fun setLogLevel() {
        val level = if (prefs.isDebugMode) { "DEBUG" } else { "INFO" }
        Log.d(TAG, "Setting Syncthing log level to $level")

        Stbridge.setLogLevel(level)
    }

    private fun stateChanged(
        recomputeBlockedReasons: Boolean = false,
        forceShowNotification: Boolean = false,
    ) {
        synchronized(stateLock) {
            if (recomputeBlockedReasons) {
                blockedReasons = deviceState.blockedReasons(this, prefs).apply {
                    if (prefs.isManualMode) {
                        val oldSize = size
                        retainAll { it.blocksStart }

                        Log.d(TAG, "Ignoring ${oldSize - size} non-fatal blocked reason(s) due to manual mode")

                        if (!prefs.manualShouldRun) {
                            add(BlockedReason.MANUAL)
                        }
                    }

                    // We intentionally only check internal storage permissions. See
                    // onCheckStoragePermissions().
                    if (missingInternal && !recheckPermissions) {
                        add(BlockedReason.NO_STORAGE_PERMISSIONS)
                    }
                }
            }

            handleStateChangeLocked()

            if (!shouldThreadRun) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                lastServiceState = null
                return
            }

            val notificationState = ServiceState(
                keepAlive = prefs.keepAlive,
                blockedReasons = blockedReasons,
                isStarted = isStarted,
                isResumed = isResumed,
                manualMode = prefs.isManualMode,
                allowAutoMode = prefs.allowAutoMode,
                preRunAction = currentPreRunAction,
                showExit = prefs.showExit,
                folderStates = syncthingFolderStates,
                deviceStates = syncthingDeviceStates,
            )

            val wasChanged = notificationState != lastServiceState

            if (wasChanged || forceShowNotification) {
                if (wasChanged) {
                    deviceStateTracker.updateBusyFolders(notificationState.folderStates)
                    deviceStateTracker.updateConnectedDevices(notificationState.deviceStates)

                    val guiInfo = guiInfo

                    allListeners { it.onRunStateChanged(notificationState, guiInfo) }
                }

                val notification = notifications.createPersistentNotification(notificationState)
                val useLocation = deviceStateTracker.canUseLocation()
                var type = 0

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && useLocation) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }

                ServiceCompat.startForeground(this, Notifications.ID_PERSISTENT, notification, type)

                if (lastUseLocation != useLocation) {
                    deviceStateTracker.refreshNetworkState()
                    lastUseLocation = useLocation
                }

                lastServiceState = notificationState
            }
        }
    }

    @GuardedBy("stateLock")
    private fun handleStateChangeLocked() {
        val app = syncthingApp

        // The service needs to be restarted for proxy changes to take effect. The hack we do to set
        // the proxy on the golang side can't be made thread-safe.
        val needFullRestart = !shouldThreadRun
                || runningProxyInfo != deviceState.proxyInfo
                || preRunActions.isNotEmpty()
                || recheckPermissions

        if (needFullRestart || isStarted != shouldStart || isResumed != shouldResume) {
            if (!needFullRestart && app != null && prefs.keepAlive) {
                Log.d(TAG, "Keep alive enabled; changing connect allowed to $shouldResume")
                app.isConnectAllowed = shouldResume
            } else if (app != null) {
                Log.d(TAG, "Syncthing is running; stopping service")
                app.stopAsync()
            } else {
                Log.d(TAG, "Syncthing is not running; waking thread")
                stateLock.notify()
            }
        }
    }

    private fun runner() {
        while (true) {
            val actions = ArrayList<PreRunAction>()
            var proxyInfo: ProxyInfo
            var recheckOnly: Boolean

            synchronized(stateLock) {
                while (preRunActions.isEmpty() && !shouldStart && !recheckPermissions) {
                    if (!shouldThreadRun) {
                        Log.d(TAG, "Service is exiting; shutting down")
                        return
                    } else {
                        Log.d(TAG, "Nothing to do; sleeping")
                        stateLock.wait()
                    }
                }

                actions.addAll(preRunActions)
                preRunActions.clear()

                runningProxyInfo = deviceState.proxyInfo
                proxyInfo = deviceState.proxyInfo

                // recheckPermissions is cleared in onCheckStoragePermissions().
                recheckOnly = !shouldStart
            }

            if (actions.isNotEmpty()) {
                for (action in actions) {
                    Log.i(TAG, "Performing pre-run action: $action")

                    synchronized(stateLock) {
                        currentPreRunAction = action
                        stateChanged()
                    }

                    val exception = try {
                        action.perform(this)
                        null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to perform pre-run action: $action", e)
                        e
                    }

                    synchronized(stateLock) {
                        if (action.needRecheck) {
                            recheckPermissions = true
                        }

                        allListeners { it.onPreRunActionResult(action, exception) }

                        currentPreRunAction = null
                        stateChanged()
                    }
                }

                // Check again if we should run.
                continue
            }

            try {
                Stbridge.run(SyncthingStartupConfig().apply {
                    deviceModel = Build.MODEL
                    proxy = proxyInfo.proxy
                    noProxy = proxyInfo.noProxy
                    receiver = this@SyncthingService
                    checkOnly = recheckOnly
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run syncthing", e)

                notifications.sendFailureNotification(e)

                // For now, just switch to manual mode so that we're not stuck in a restart loop.
                // Since Syncthing is not running, this won't result in handleStateChangeLocked()
                // just toggling isConnectAllowed.
                prefs.manualShouldRun = false
                prefs.isManualMode = true

                // stateChanged() will be called by onSharedPreferenceChanged().
            }
        }
    }

    override fun onCheckStoragePermissions(internal0Sep: String, external0Sep: String) {
        val external = mutableSetOf<Uri>()

        for (encoded in unpack0Sep(external0Sep)) {
            try {
                external.add(decodeSafUri(encoded).first)
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring invalid encoded URI: $encoded", e)
            }
        }

        for (persisted in contentResolver.persistedUriPermissions) {
            if (!DocumentsContract.isTreeUri(persisted.uri)) {
                continue
            }

            if (!external.remove(persisted.uri)) {
                Log.d(TAG, "Releasing persisted permission: $persisted")
                try {
                    var flags = 0
                    if (persisted.isReadPermission) {
                        flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    if (persisted.isWritePermission) {
                        flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    }

                    contentResolver.releasePersistableUriPermission(persisted.uri, flags)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release persisted permission: $persisted", e)
                }
            }
        }

        synchronized(stateLock) {
            recheckPermissions = false
            missingInternal = internal0Sep.isNotEmpty() && !Permissions.haveLocalStorage(this)
            missingExternal = external.sorted()

            allListeners {
                it.onMissingStoragePermissions(missingInternal, missingExternal)
            }

            stateChanged(recomputeBlockedReasons = true)

            // We intentionally block on missing internal permissions only. If a SAF URI is
            // inaccessible, Syncthing will completely lose access to it. However, if the local
            // storage permission is denied, folders are still visible, so Syncthing thinks
            // everything was deleted.
            if (missingInternal) {
                throw SecurityException("Missing internal storage permissions")
            }
        }
    }

    @WorkerThread
    override fun onSyncthingStarted(app: SyncthingApp) {
        Log.i(TAG, "Syncthing successfully started")

        synchronized(stateLock) {
            syncthingApp = app

            stateChanged()
        }
    }

    @WorkerThread
    override fun onSyncthingStopped(app: SyncthingApp) {
        Log.i(TAG, "Syncthing successfully stopped")

        synchronized(stateLock) {
            syncthingConflicts = emptyList()
            syncthingAlerts = 0
            syncthingFolderStates = FolderStates()
            syncthingDeviceStates = DeviceStates()
            syncthingApp = null

            stateChanged()
        }
    }

    @WorkerThread
    override fun onConflictsUpdated(paths0Sep: String) {
        val paths = unpack0Sep(paths0Sep).apply { sort() }

        synchronized(stateLock) {
            syncthingConflicts = paths
        }
    }

    @WorkerThread
    override fun onAlertsUpdated(count: Int) {
        synchronized(stateLock) {
            syncthingAlerts = count
        }
    }

    @WorkerThread
    override fun onFolderStatesUpdated(
        idle: Int,
        scanning: Int,
        syncing: Int,
        cleaning: Int,
        errored: Int,
        starting: Int,
    ) {
        synchronized(stateLock) {
            syncthingFolderStates = FolderStates(
                idle = idle,
                scanning = scanning,
                syncing = syncing,
                cleaning = cleaning,
                errored = errored,
                starting = starting,
            )
            stateChanged()
        }
    }

    @WorkerThread
    override fun onDeviceStatesUpdated(connected: Int, syncing: Int, pending: Int) {
        synchronized(stateLock) {
            syncthingDeviceStates = DeviceStates(
                connected = connected,
                syncing = syncing,
                pending = pending,
            )
            stateChanged()
        }
    }

    data class GuiInfo(
        val address: String,
        val user: String,
        val apiKey: String,
        val cert: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as GuiInfo

            if (address != other.address) return false
            if (user != other.user) return false
            if (apiKey != other.apiKey) return false
            if (!cert.contentEquals(other.cert)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + user.hashCode()
            result = 31 * result + apiKey.hashCode()
            result = 31 * result + cert.contentHashCode()
            return result
        }
    }

    interface ServiceListener {
        fun onMissingStoragePermissions(internal: Boolean, external: List<Uri>)

        fun onExitRequested()

        fun onRunStateChanged(state: ServiceState, guiInfo: GuiInfo?)

        fun onPreRunActionResult(preRunAction: PreRunAction, exception: Exception?)

        fun onConflictsUpdated(conflicts: List<String>)
    }

    inner class ServiceBinder : Binder() {
        fun registerListener(listener: ServiceListener) {
            synchronized(stateLock) {
                Log.d(TAG, "Registering listener: $listener")

                if (!listeners.add(listener)) {
                    Log.w(TAG, "Listener was already registered: $listener")
                }

                listener.onMissingStoragePermissions(missingInternal, missingExternal)
                listener.onRunStateChanged(lastServiceState!!, guiInfo)
                listener.onConflictsUpdated(syncthingConflicts)
            }
        }

        fun unregisterListener(listener: ServiceListener) {
            synchronized(stateLock) {
                Log.d(TAG, "Unregistering listener: $listener")

                if (!listeners.remove(listener)) {
                    Log.w(TAG, "Listener was never registered: $listener")
                }
            }
        }

        fun importConfiguration(uri: Uri, password: Password) {
            synchronized(stateLock) {
                Log.d(TAG, "Scheduling configuration import: $uri")

                preRunActions.add(PreRunAction.Import(uri, password))
                handleStateChangeLocked()
            }
        }

        fun exportConfiguration(uri: Uri, password: Password) {
            synchronized(stateLock) {
                Log.d(TAG, "Scheduling configuration export: $uri")

                preRunActions.add(PreRunAction.Export(uri, password))
                handleStateChangeLocked()
            }
        }
    }
}
