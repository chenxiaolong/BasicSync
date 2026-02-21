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
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import androidx.core.app.ServiceCompat
import com.chiller3.basicsync.Notifications
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.binding.stbridge.Stbridge
import com.chiller3.basicsync.binding.stbridge.SyncthingApp
import com.chiller3.basicsync.binding.stbridge.SyncthingStartupConfig
import com.chiller3.basicsync.binding.stbridge.SyncthingStatusReceiver
import java.io.IOException

class SyncthingService : Service(), SyncthingStatusReceiver, DeviceStateListener,
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = SyncthingService::class.java.simpleName

        private val STATE_CHANGE_PREFS = arrayOf(
            Preferences.PREF_MANUAL_MODE,
            Preferences.PREF_MANUAL_SHOULD_RUN,
            Preferences.PREF_KEEP_ALIVE,
        )

        val ACTION_AUTO_MODE = "${SyncthingService::class.java.canonicalName}.auto_mode"
        val ACTION_MANUAL_MODE = "${SyncthingService::class.java.canonicalName}.manual_mode"
        val ACTION_START = "${SyncthingService::class.java.canonicalName}.start"
        val ACTION_STOP = "${SyncthingService::class.java.canonicalName}.stop"
        val ACTION_RENOTIFY = "${SyncthingService::class.java.canonicalName}.renotify"

        fun createIntent(context: Context, action: String?) =
            Intent(context, SyncthingService::class.java).apply {
                this.action = action
            }

        fun start(context: Context, action: String?) {
            context.startForegroundService(createIntent(context, action))
        }
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

        val webUiAvailable: Boolean
            get() = this == RUNNING || this == PAUSED || this == PAUSING
    }

    data class ServiceState(
        val keepAlive: Boolean,
        val shouldRun: Boolean,
        val isStarted: Boolean,
        val isActive: Boolean,
        val manualMode: Boolean,
        val preRunAction: PreRunAction?,
    ) {
        val runState: RunState
            get() = if (preRunAction != null) {
                when (preRunAction) {
                    is PreRunAction.Import -> RunState.IMPORTING
                    is PreRunAction.Export -> RunState.EXPORTING
                }
            } else if (isStarted) {
                if (isActive) {
                    if (shouldRun) {
                        RunState.RUNNING
                    } else if (keepAlive) {
                        RunState.PAUSING
                    } else {
                        RunState.STOPPING
                    }
                } else {
                    if (shouldRun) {
                        RunState.STARTING
                    } else if (keepAlive) {
                        RunState.PAUSED
                    } else {
                        RunState.STOPPING
                    }
                }
            } else {
                if (isActive) {
                    throw IllegalArgumentException("Service active, but not running?")
                } else {
                    if (shouldRun) {
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
                        add(ACTION_AUTO_MODE)

                        if (shouldRun) {
                            add(ACTION_STOP)
                        } else {
                            add(ACTION_START)
                        }
                    } else {
                        add(ACTION_MANUAL_MODE)
                    }
                }
            }
    }

    data class Password(val value: String) {
        override fun toString(): String = "<password>"
    }

    sealed interface PreRunAction {
        fun perform(context: Context)

        data class Import(val uri: Uri, val password: Password?) : PreRunAction {
            override fun perform(context: Context) {
                @SuppressLint("Recycle")
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Failed to open for reading: $uri")

                // stbridge will own the fd.
                Stbridge.importConfiguration(
                    fd.detachFd().toLong(),
                    uri.toString(),
                    password?.value ?: "",
                )
            }
        }

        data class Export(val uri: Uri, val password: Password?) : PreRunAction {
            override fun perform(context: Context) {
                @SuppressLint("Recycle")
                val fd = context.contentResolver.openFileDescriptor(uri, "wt")
                    ?: throw IOException("Failed to open for writing: $uri")

                // stbridge will own the fd.
                Stbridge.exportConfiguration(
                    fd.detachFd().toLong(),
                    uri.toString(),
                    password?.value ?: "",
                )
            }
        }
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
    @GuardedBy("stateLock")
    private var forceShowNotification = false

    private lateinit var deviceStateTracker: DeviceStateTracker
    @GuardedBy("stateLock")
    private var deviceState = DeviceState()
    @GuardedBy("stateLock")
    private var runningProxyInfo: ProxyInfo? = null

    private val autoShouldRun: Boolean
        @GuardedBy("stateLock")
        get() = deviceState.canRun(prefs)

    private val shouldRun: Boolean
        @GuardedBy("stateLock")
        get() = if (prefs.isManualMode) {
            prefs.manualShouldRun
        } else {
            autoShouldRun
        }

    private val shouldStart: Boolean
        @GuardedBy("stateLock")
        get() = prefs.keepAlive || shouldRun

    @GuardedBy("stateLock")
    private val preRunActions = mutableListOf<PreRunAction>()

    @GuardedBy("stateLock")
    private var currentPreRunAction: PreRunAction? = null

    @GuardedBy("stateLock")
    private var syncthingApp: SyncthingApp? = null

    private val isStarted: Boolean
        @GuardedBy("stateLock")
        get() = syncthingApp != null

    private val isActive: Boolean
        @GuardedBy("stateLock")
        get() = if (prefs.keepAlive) {
            syncthingApp?.isConnectAllowed ?: false
        } else {
            isStarted
        }

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

    override fun onCreate() {
        super.onCreate()

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

        prefs.unregisterListener(this)

        deviceStateTracker.unregisterListener(this)

        Log.d(TAG, "Exiting")
    }

    override fun onBind(intent: Intent?): IBinder = ServiceBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        when (intent?.action) {
            ACTION_AUTO_MODE -> prefs.isManualMode = false
            ACTION_MANUAL_MODE -> {
                // Keep the current state since the user has no way to know what the previously
                // saved state is anyway.
                prefs.manualShouldRun = autoShouldRun
                prefs.isManualMode = true
            }
            ACTION_START -> prefs.manualShouldRun = true
            ACTION_STOP -> prefs.manualShouldRun = false
            ACTION_RENOTIFY -> synchronized(stateLock) {
                forceShowNotification = true
            }
            null -> {}
            else -> Log.w(TAG, "Ignoring unrecognized intent: $intent")
        }

        stateChanged()

        return START_NOT_STICKY
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.d(TAG, "Preference $key changed")

        // We have to switch foreground service and network callback types when location becomes
        // needed or no longer needed.
        if (key == Preferences.PREF_ALLOWED_WIFI_NETWORKS) {
            synchronized(stateLock) {
                forceShowNotification = true
            }
        }

        when (key) {
            in STATE_CHANGE_PREFS, in DeviceState.PREFS -> stateChanged()
            Preferences.PREF_DEBUG_MODE -> setLogLevel()
        }
    }

    override fun onDeviceStateChanged(state: DeviceState) {
        synchronized(stateLock) {
            deviceState = state
            stateChanged()
        }
    }

    private fun setLogLevel() {
        val level = if (prefs.isDebugMode) { "DEBUG" } else { "INFO" }
        Log.d(TAG, "Setting Syncthing log level to $level")

        Stbridge.setLogLevel(level)
    }

    private fun stateChanged() {
        synchronized(stateLock) {
            handleStateChangeLocked()

            val notificationState = ServiceState(
                keepAlive = prefs.keepAlive,
                shouldRun = shouldRun,
                isStarted = isStarted,
                isActive = isActive,
                manualMode = prefs.isManualMode,
                preRunAction = currentPreRunAction,
            )

            val wasChanged = notificationState != lastServiceState

            if (wasChanged || forceShowNotification) {
                forceShowNotification = false

                if (wasChanged) {
                    val runState = notificationState.runState
                    val guiInfo = guiInfo

                    for (listener in listeners) {
                        listener.onRunStateChanged(runState, guiInfo)
                    }
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
        val needFullRestart = runningProxyInfo != deviceState.proxyInfo || preRunActions.isNotEmpty()

        if (needFullRestart || isStarted != shouldStart || isActive != shouldRun) {
            if (!needFullRestart && app != null && prefs.keepAlive) {
                Log.d(TAG, "Keep alive enabled; changing connect allowed to $shouldRun")
                app.isConnectAllowed = shouldRun
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

            synchronized(stateLock) {
                while (preRunActions.isEmpty() && !shouldStart) {
                    Log.d(TAG, "Nothing to do; sleeping")
                    stateLock.wait()
                }

                actions.addAll(preRunActions)
                preRunActions.clear()

                runningProxyInfo = deviceState.proxyInfo
                proxyInfo = deviceState.proxyInfo
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
                        for (listener in listeners) {
                            listener.onPreRunActionResult(action, exception)
                        }

                        currentPreRunAction = null
                        stateChanged()
                    }
                }

                // Check again if we should run.
                continue
            }

            try {
                Stbridge.run(SyncthingStartupConfig().apply {
                    filesDir = this@SyncthingService.filesDir.toString()
                    deviceModel = Build.MODEL
                    proxy = proxyInfo.proxy
                    noProxy = proxyInfo.noProxy
                    receiver = this@SyncthingService
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

    @WorkerThread
    override fun onSyncthingStart(app: SyncthingApp) {
        Log.i(TAG, "Syncthing successfully started")

        synchronized(stateLock) {
            syncthingApp = app

            stateChanged()
        }
    }

    @WorkerThread
    override fun onSyncthingStop(app: SyncthingApp) {
        Log.i(TAG, "Syncthing is about to stop")

        synchronized(stateLock) {
            syncthingApp = null

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
        fun onRunStateChanged(state: RunState, guiInfo: GuiInfo?)

        fun onPreRunActionResult(preRunAction: PreRunAction, exception: Exception?)
    }

    inner class ServiceBinder : Binder() {
        fun registerListener(listener: ServiceListener) {
            synchronized(stateLock) {
                Log.d(TAG, "Registering listener: $listener")

                if (!listeners.add(listener)) {
                    Log.w(TAG, "Listener was already registered: $listener")
                }

                listener.onRunStateChanged(lastServiceState!!.runState, guiInfo)
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

        fun importConfiguration(uri: Uri, password: Password?) {
            synchronized(stateLock) {
                Log.d(TAG, "Scheduling configuration import: $uri")

                preRunActions.add(PreRunAction.Import(uri, password))
                handleStateChangeLocked()
            }
        }

        fun exportConfiguration(uri: Uri, password: Password?) {
            synchronized(stateLock) {
                Log.d(TAG, "Scheduling configuration export: $uri")

                preRunActions.add(PreRunAction.Export(uri, password))
                handleStateChangeLocked()
            }
        }
    }
}
