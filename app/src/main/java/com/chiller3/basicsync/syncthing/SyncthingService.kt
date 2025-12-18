/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.syncthing

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Proxy
import android.net.Uri
import android.os.BatteryManager
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
import kotlin.math.roundToInt

class SyncthingService : Service(), SyncthingStatusReceiver,
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = SyncthingService::class.java.simpleName

        val ACTION_AUTO_MODE = "${SyncthingService::class.java.canonicalName}.auto_mode"
        val ACTION_MANUAL_MODE = "${SyncthingService::class.java.canonicalName}.manual_mode"
        val ACTION_START = "${SyncthingService::class.java.canonicalName}.start"
        val ACTION_STOP = "${SyncthingService::class.java.canonicalName}.stop"
        val ACTION_RENOTIFY = "${SyncthingService::class.java.canonicalName}.renotify"

        // We can't query config_lowBatteryWarningLevel due to Android's hidden API restrictions, so
        // just use AOSP's default.
        private const val LOW_BATTERY_LIMIT = 20

        fun createIntent(context: Context, action: String?) =
            Intent(context, SyncthingService::class.java).apply {
                this.action = action
            }

        private fun getProxyInfo(): ProxyInfo {
            val proxyHost = System.getProperty("http.proxyHost")
            val proxyPort = System.getProperty("http.proxyPort")
            val proxy = if (!proxyHost.isNullOrEmpty() && !proxyPort.isNullOrEmpty()) {
                "$proxyHost:$proxyPort"
            } else {
                ""
            }
            val noProxy = (System.getProperty("http.nonProxyHosts") ?: "").replace('|', ',')

            return ProxyInfo(proxy, noProxy)
        }
    }

    private data class ProxyInfo(
        val proxy: String,
        val noProxy: String,
    )

    enum class RunState {
        RUNNING,
        NOT_RUNNING,
        STARTING,
        STOPPING,
        IMPORTING,
        EXPORTING,
    }

    data class ServiceState(
        val shouldRun: Boolean,
        val isRunning: Boolean,
        val manualMode: Boolean,
        val preRunAction: PreRunAction?,
    ) {
        val runState: RunState
            get() = if (preRunAction != null) {
                when (preRunAction) {
                    is PreRunAction.Import -> RunState.IMPORTING
                    is PreRunAction.Export -> RunState.EXPORTING
                }
            } else if (isRunning) {
                if (shouldRun) {
                    RunState.RUNNING
                } else {
                    RunState.STOPPING
                }
            } else {
                if (shouldRun) {
                    RunState.STARTING
                } else {
                    RunState.NOT_RUNNING
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

    sealed interface PreRunAction {
        fun perform(context: Context)

        data class Import(val uri: Uri) : PreRunAction {
            override fun perform(context: Context) {
                @SuppressLint("Recycle")
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Failed to open for reading: $uri")

                // stbridge will own the fd.
                Stbridge.importConfiguration(fd.detachFd().toLong(), uri.toString())
            }
        }

        data class Export(val uri: Uri) : PreRunAction {
            override fun perform(context: Context) {
                @SuppressLint("Recycle")
                val fd = context.contentResolver.openFileDescriptor(uri, "wt")
                    ?: throw IOException("Failed to open for writing: $uri")

                // stbridge will own the fd.
                Stbridge.exportConfiguration(fd.detachFd().toLong(), uri.toString())
            }
        }
    }

    private lateinit var prefs: Preferences
    private lateinit var notifications: Notifications
    private lateinit var connectivityManager: ConnectivityManager
    private val runnerThread = Thread(::runner)

    private val stateLock = Object()

    @GuardedBy("stateLock")
    private var lastServiceState: ServiceState? = null
    @GuardedBy("stateLock")
    private var forceShowNotification = false

    @GuardedBy("stateLock")
    private var networkConnected = false
    @GuardedBy("stateLock")
    private var networkSufficient = false
    @GuardedBy("stateLock")
    private var batterySufficient = false

    @GuardedBy("stateLock")
    private var runningProxyInfo: ProxyInfo? = null
    @GuardedBy("stateLock")
    private var deviceProxyInfo = getProxyInfo()

    private val autoShouldRun: Boolean
        @GuardedBy("stateLock")
        get() = networkConnected
                && (!prefs.requireUnmeteredNetwork || networkSufficient)
                && (!prefs.requireSufficientBattery || batterySufficient)

    private val shouldRun: Boolean
        @GuardedBy("stateLock")
        get() = if (prefs.isManualMode) {
            prefs.manualShouldRun
        } else {
            autoShouldRun
        }

    @GuardedBy("stateLock")
    private val preRunActions = mutableListOf<PreRunAction>()

    @GuardedBy("stateLock")
    private var currentPreRunAction: PreRunAction? = null

    @GuardedBy("stateLock")
    private var syncthingApp: SyncthingApp? = null

    private val isRunning: Boolean
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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network connected")

            synchronized(stateLock) {
                networkConnected = true

                stateChanged()
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network disconnected")

            synchronized(stateLock) {
                networkConnected = false

                stateChanged()
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            synchronized(stateLock) {
                networkSufficient = if (prefs.requireUnmeteredNetwork) {
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
                } else {
                    true
                }

                Log.d(TAG, "Network is unmetered: $networkSufficient")

                stateChanged()
            }
        }
    }

    private val batteryStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
                    || intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val scaledLevel = (level * 100 / scale.toFloat()).roundToInt()

            Log.d(TAG, "Battery state changed: present=$present, charging=$isCharging, level=$scaledLevel")

            synchronized(stateLock) {
                batterySufficient = !present || isCharging || scaledLevel >= LOW_BATTERY_LIMIT

                stateChanged()
            }
        }
    }

    private val proxyChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Proxy settings changed")

            synchronized(stateLock) {
                deviceProxyInfo = getProxyInfo()

                // The service needs to be restarted for changes to take effect. The hack we do to
                // set the proxy on the golang side can't be made thread-safe.
                stateChanged()
            }
        }
    }

    @GuardedBy("stateLock")
    private val listeners = HashSet<ServiceListener>()

    override fun onCreate() {
        super.onCreate()

        prefs = Preferences(this)
        prefs.registerListener(this)

        setLogLevel()

        notifications = Notifications(this)

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        registerReceiver(batteryStatusReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(proxyChangeReceiver, IntentFilter(Proxy.PROXY_CHANGE_ACTION))

        runnerThread.start()
    }

    override fun onDestroy() {
        super.onDestroy()

        prefs.unregisterListener(this)

        connectivityManager.unregisterNetworkCallback(networkCallback)

        unregisterReceiver(batteryStatusReceiver)
        unregisterReceiver(proxyChangeReceiver)

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

        when (key) {
            Preferences.PREF_MANUAL_MODE,
            Preferences.PREF_MANUAL_SHOULD_RUN,
            Preferences.PREF_REQUIRE_UNMETERED_NETWORK,
            Preferences.PREF_REQUIRE_SUFFICIENT_BATTERY -> stateChanged()
            Preferences.PREF_DEBUG_MODE -> setLogLevel()
        }
    }

    private fun setLogLevel() {
        val level = if (prefs.isDebugMode) { "DEBUG" } else { "INFO" }
        Log.d(TAG, "Setting Syncthing log level to $level")

        Stbridge.setLogLevel(level)
    }

    private fun stateChanged() {
        synchronized(stateLock) {
            val notificationState = ServiceState(
                shouldRun = shouldRun,
                isRunning = isRunning,
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
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }

                ServiceCompat.startForeground(this, Notifications.ID_PERSISTENT, notification, type)

                lastServiceState = notificationState
            }

            triggerRunnerLoopLocked()
        }
    }

    @GuardedBy("stateLock")
    private fun triggerRunnerLoopLocked() {
        val app = syncthingApp

        if (runningProxyInfo != deviceProxyInfo
                || preRunActions.isNotEmpty()
                || isRunning != shouldRun) {
            if (app != null) {
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
                while (preRunActions.isEmpty() && !shouldRun) {
                    stateLock.wait()
                }

                actions.addAll(preRunActions)
                preRunActions.clear()

                runningProxyInfo = deviceProxyInfo
                proxyInfo = deviceProxyInfo
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

        fun importConfiguration(uri: Uri) {
            synchronized(stateLock) {
                Log.d(TAG, "Scheduling configuration import: $uri")

                preRunActions.add(PreRunAction.Import(uri))
                triggerRunnerLoopLocked()
            }
        }

        fun exportConfiguration(uri: Uri) {
            synchronized(stateLock) {
                Log.d(TAG, "Scheduling configuration export: $uri")

                preRunActions.add(PreRunAction.Export(uri))
                triggerRunnerLoopLocked()
            }
        }
    }
}
