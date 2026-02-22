/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.syncthing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Proxy
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.Preferences
import kotlin.math.max
import kotlin.math.roundToInt

enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
}

data class ProxyInfo(
    val proxy: String,
    val noProxy: String,
)

data class DeviceState(
    val isNetworkConnected: Boolean = false,
    val isNetworkUnmetered: Boolean = false,
    val networkType: NetworkType = NetworkType.OTHER,
    val wifiSsid: String? = null,
    val isPluggedIn: Boolean = false,
    val batteryLevel: Int = 0,
    val isBatterySaver: Boolean = false,
    val isInTimeWindow: Boolean = false,
    val proxyInfo: ProxyInfo = ProxyInfo("", ""),
) {
    companion object {
        private val TAG = DeviceState::class.java.simpleName

        val PREFS = arrayOf(
            Preferences.PREF_REQUIRE_UNMETERED_NETWORK,
            Preferences.PREF_NETWORK_ALLOW_WIFI,
            Preferences.PREF_NETWORK_ALLOW_CELLULAR,
            Preferences.PREF_NETWORK_ALLOW_ETHERNET,
            Preferences.PREF_NETWORK_ALLOW_OTHER,
            Preferences.PREF_ALLOWED_WIFI_NETWORKS,
            Preferences.PREF_RUN_ON_BATTERY,
            Preferences.PREF_MIN_BATTERY_LEVEL,
            Preferences.PREF_RESPECT_BATTERY_SAVER,
        )

        fun normalizeSsid(ssid: String): String? =
            if (ssid.length >= 2 && ssid[0] == '"' && ssid[ssid.length - 1] == '"') {
                // UTF-8 SSID.
                ssid.substring(1, ssid.length - 1)
            } else if (ssid != WifiManager.UNKNOWN_SSID && ssid.isNotEmpty()) {
                // Non-UTF-8 SSID expressed as a hex string.
                ssid
            } else {
                null
            }

        fun ssidMatches(allowed: String, ssid: String?): Boolean =
            allowed == ssid?.let(::normalizeSsid)
    }

    fun canRun(prefs: Preferences): Boolean {
        if (!isNetworkConnected) {
            Log.d(TAG, "Blocked due to lack of network connectivity")
            return false
        }

        if (prefs.requireUnmeteredNetwork && !isNetworkUnmetered) {
            Log.d(TAG, "Blocked due to unmetered network requirement")
            return false
        }

        val networkAllowed = when (networkType) {
            NetworkType.WIFI -> prefs.networkAllowWifi
            NetworkType.CELLULAR -> prefs.networkAllowCellular
            NetworkType.ETHERNET -> prefs.networkAllowEthernet
            NetworkType.OTHER -> prefs.networkAllowOther
        }
        if (!networkAllowed) {
            Log.d(TAG, "Blocked due to disallowed network interface type: $networkType")
            return false
        }

        val allowedWifiNetworks = prefs.allowedWifiNetworks
        if (networkType == NetworkType.WIFI && allowedWifiNetworks.isNotEmpty()
                && allowedWifiNetworks.none { ssidMatches(it, wifiSsid) }) {
            Log.d(TAG, "Blocked due to disallowed network: ssid=$wifiSsid")
            return false
        }

        if (!isPluggedIn) {
            val runOnBattery = prefs.runOnBattery
            val minBatteryLevel = prefs.minBatteryLevel

            if (!runOnBattery) {
                Log.d(TAG, "Blocked due to battery power source")
                return false
            } else if (batteryLevel < minBatteryLevel) {
                Log.d(TAG, "Blocked due to low battery level: $batteryLevel < $minBatteryLevel")
                return false
            }
        }

        if (prefs.respectBatterySaver && isBatterySaver) {
            Log.d(TAG, "Blocked due to battery saver mode")
            return false
        }

        if (!isInTimeWindow) {
            Log.d(TAG, "Blocked due to execution time window")
            return false
        }

        Log.d(TAG, "Permitted to run")
        return true
    }
}

class DeviceStateTracker(private val context: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = DeviceStateTracker::class.java.simpleName

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

    private var listener: DeviceStateListener? = null
    private val prefs = Preferences(context)
    private val handler = Handler(Looper.myLooper()!!)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private var timeReferenceMs = SystemClock.uptimeMillis()

    private var state: DeviceState = DeviceState(
        isBatterySaver = powerManager.isPowerSaveMode,
        proxyInfo = getProxyInfo(),
    )
        set(value) {
            if (field != value) {
                field = value
                listener?.onDeviceStateChanged(value)
            }
        }

    private fun onNetworkAvailable() {
        Log.d(TAG, "Network connected")

        state = state.copy(isNetworkConnected = true)
    }

    private fun onNetworkLost() {
        Log.d(TAG, "Network disconnected")

        state = state.copy(
            isNetworkConnected = false,
            isNetworkUnmetered = false,
            networkType = NetworkType.OTHER,
            wifiSsid = null,
        )
    }

    private fun onNetworkCapabilitiesChanged(capabilities: NetworkCapabilities) {
        val isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))

        val networkType = if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            NetworkType.WIFI
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            NetworkType.CELLULAR
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            NetworkType.ETHERNET
        } else {
            NetworkType.OTHER
        }

        var wifiSsid: String? = null

        if (canUseLocation()) {
            val transportWifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // This is technically available on API 29 and 30, but transportInfo returns null.
                capabilities.transportInfo as? WifiInfo
            } else {
                null
            }
            // We fall back to the deprecated WifiManager method even for newer versions of Android
            // because transportInfo returns a VpnTransportInfo instance when connected to Wi-Fi + VPN
            // and there's no other way to get the WifiInfo. AOSP still uses this in Settings, SystemUI,
            // and numerous other places.
            @Suppress("DEPRECATION")
            val wifiInfo = transportWifiInfo ?: wifiManager.connectionInfo

            wifiSsid = wifiInfo?.ssid
        }

        Log.d(TAG, "Network details: unmetered=$isUnmetered, type=$networkType, ssid=$wifiSsid")

        state = state.copy(
            isNetworkUnmetered = isUnmetered,
            networkType = networkType,
            wifiSsid = wifiSsid,
        )
    }

    private val networkCallbackWithLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = onNetworkAvailable()

            override fun onLost(network: Network) = onNetworkLost()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = onNetworkCapabilitiesChanged(networkCapabilities)
        }
    } else {
        null
    }

    private val networkCallbackNoLocation = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkAvailable()

        override fun onLost(network: Network) = onNetworkLost()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = onNetworkCapabilitiesChanged(networkCapabilities)
    }

    private val networkCallback: ConnectivityManager.NetworkCallback
        get() = if (canUseLocation() && networkCallbackWithLocation != null) {
            networkCallbackWithLocation
        } else {
            networkCallbackNoLocation
        }

    private var registeredNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val batteryStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val scaledLevel = (level * 100 / scale.toFloat()).roundToInt()

            Log.d(TAG, "Battery state changed: present=$present, plugged=$plugged, level=$scaledLevel")

            state = state.copy(
                isPluggedIn = !present || plugged,
                batteryLevel = scaledLevel,
            )
        }
    }

    private val batterySaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isBatterySaverMode = powerManager.isPowerSaveMode

            Log.d(TAG, "Battery saver mode changed: $isBatterySaverMode")

            state = state.copy(isBatterySaver = isBatterySaverMode)
        }
    }

    private val proxyChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val proxyInfo = getProxyInfo()

            Log.d(TAG, "Proxy settings changed: $proxyInfo")

            state = state.copy(proxyInfo = proxyInfo)
        }
    }

    private fun registerNetworkCallback() {
        if (registeredNetworkCallback != null) {
            throw IllegalStateException("Network callback already registered")
        }

        val networkCallback = networkCallback
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        registeredNetworkCallback = networkCallback
    }

    private fun unregisterNetworkCallback() {
        val callback = registeredNetworkCallback
            ?: throw IllegalStateException("No network callback registered")

        connectivityManager.unregisterNetworkCallback(callback)
        registeredNetworkCallback = null
    }

    private val timeWindowCallback = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)

            var canRun = true

            if (prefs.syncSchedule) {
                val cycleDurationMs = max(prefs.scheduleCycleMs, 1000)
                val syncDurationMs = max(prefs.scheduleSyncMs, 500)

                val now = SystemClock.uptimeMillis()
                val relativeStart = (now - timeReferenceMs) / cycleDurationMs * cycleDurationMs
                val windowStart = timeReferenceMs + relativeStart
                val windowEnd = windowStart + syncDurationMs
                canRun = now in windowStart until windowEnd

                val nextCheckMs = if (canRun) {
                    windowEnd
                } else {
                    windowStart + cycleDurationMs
                }

                Log.d(TAG, "Time window updated: now=$now, window=[$windowStart, $windowEnd)")

                handler.postAtTime(this, nextCheckMs)
            } else {
                Log.d(TAG, "Time schedule is disabled")
            }

            state = state.copy(isInTimeWindow = canRun)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Preferences.PREF_SYNC_SCHEDULE,
            Preferences.PREF_SCHEDULE_CYCLE_MS,
            Preferences.PREF_SCHEDULE_SYNC_MS -> {
                timeWindowCallback.run()
            }
            // PREF_ALLOWED_WIFI_NETWORKS is checked in SyncthingService because we need the service
            // to request the location foreground permission first.
        }
    }

    fun registerListener(listener: DeviceStateListener) {
        if (this.listener != null) {
            throw IllegalStateException("Listener already registered")
        }

        prefs.registerListener(this)
        registerNetworkCallback()
        context.registerReceiver(
            batteryStatusReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        context.registerReceiver(
            batterySaverReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )
        context.registerReceiver(
            proxyChangeReceiver,
            IntentFilter(Proxy.PROXY_CHANGE_ACTION),
        )
        timeWindowCallback.run()

        this.listener = listener

        listener.onDeviceStateChanged(state)
    }

    fun unregisterListener(listener: DeviceStateListener) {
        if (this.listener !== listener) {
            throw IllegalStateException("Unregistering bad listener: $listener != ${this.listener}")
        }

        prefs.unregisterListener(this)
        unregisterNetworkCallback()
        context.unregisterReceiver(batteryStatusReceiver)
        context.unregisterReceiver(batterySaverReceiver)
        context.unregisterReceiver(proxyChangeReceiver)
        handler.removeCallbacks(timeWindowCallback)

        this.listener = null
    }

    // We do not use the location permissions even if we were granted them unless the user has
    // configured allowed Wi-Fi networks.
    fun canUseLocation() = prefs.allowedWifiNetworks.isNotEmpty()
            && Permissions.have(context, Permissions.PRECISE_LOCATION)
            && Permissions.have(context, Permissions.BACKGROUND_LOCATION)

    fun refreshNetworkState() {
        // If the user grants the location permissions while the app is running, we'll need to
        // switch to the location-compatible network callback. However, note that even if we used
        // the location-compatible network callback all the time, Android would still not send us a
        // new event when the permissions are granted.
        unregisterNetworkCallback()
        registerNetworkCallback()
    }
}

interface DeviceStateListener {
    fun onDeviceStateChanged(state: DeviceState)
}
