/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Preferences(context: Context) {
    companion object {
        const val CATEGORY_PERMISSIONS = "permissions"
        const val CATEGORY_CONFIGURATION = "configuration"
        const val CATEGORY_DEBUG = "debug"

        // Main preferences.
        const val PREF_AUTO_MODE = "auto_mode"
        const val PREF_RUN_ON_BATTERY = "run_on_battery"
        const val PREF_MIN_BATTERY_LEVEL = "min_battery_level"
        const val PREF_RESPECT_BATTERY_SAVER = "respect_battery_saver"
        const val PREF_SYNC_SCHEDULE = "sync_schedule"
        const val PREF_KEEP_ALIVE = "keep_alive"

        // Main UI actions only.
        const val PREF_INHIBIT_BATTERY_OPT = "inhibit_battery_opt"
        const val PREF_ALLOW_NOTIFICATIONS = "allow_notifications"
        const val PREF_LOCAL_STORAGE_ACCESS = "local_storage_access"
        const val PREF_DISABLE_APP_HIBERNATION = "disable_app_hibernation"
        const val PREF_OPEN_WEB_UI = "open_web_ui"
        const val PREF_IMPORT_CONFIGURATION = "import_configuration"
        const val PREF_EXPORT_CONFIGURATION = "export_configuration"
        const val PREF_SERVICE_STATUS = "service_status"
        const val PREF_NETWORK_CONDITIONS = "network_conditions"
        const val PREF_VERSION = "version"
        const val PREF_SAVE_LOGS = "save_logs"

        // Not associated with a UI preference.
        const val PREF_DEBUG_MODE = "debug_mode"
        const val PREF_MANUAL_MODE = "manual_mode"
        const val PREF_MANUAL_SHOULD_RUN = "manual_should_run"
        const val PREF_SCHEDULE_CYCLE_MS = "schedule_cycle_ms"
        const val PREF_SCHEDULE_SYNC_MS = "schedule_sync_ms"

        // Network condition preferences.
        const val CATEGORY_ALLOWED_WIFI_NETWORKS = "allowed_wifi_networks"
        const val CATEGORY_AVAILABLE_WIFI_NETWORKS = "available_wifi_networks"
        const val PREF_REQUIRE_UNMETERED_NETWORK = "require_unmetered_network"
        const val PREF_NETWORK_ALLOW_WIFI = "network_allow_wifi"
        const val PREF_NETWORK_ALLOW_CELLULAR = "network_allow_cellular"
        const val PREF_NETWORK_ALLOW_ETHERNET = "network_allow_ethernet"
        const val PREF_NETWORK_ALLOW_OTHER = "network_allow_other"
        const val PREF_ALLOW_PRECISE_LOCATION = "allow_precise_location"
        const val PREF_ALLOW_BACKGROUND_LOCATION = "allow_background_location"
        const val PREF_ADD_NEW_NETWORK = "add_new_network"
        const val PREF_ALLOWED_WIFI_NETWORKS = "allowed_wifi_networks"

        // Legacy preferences.
        private const val PREF_REQUIRE_SUFFICIENT_BATTERY = "require_sufficient_battery"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun registerListener(listener: OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var runOnBattery: Boolean
        get() = prefs.getBoolean(PREF_RUN_ON_BATTERY, true)
        set(enabled) = prefs.edit { putBoolean(PREF_RUN_ON_BATTERY, enabled) }

    var minBatteryLevel: Int
        get() = prefs.getInt(PREF_MIN_BATTERY_LEVEL, 20)
        set(level) = prefs.edit { putInt(PREF_MIN_BATTERY_LEVEL, level) }

    var respectBatterySaver: Boolean
        get() = prefs.getBoolean(PREF_RESPECT_BATTERY_SAVER, true)
        set(enabled) = prefs.edit { putBoolean(PREF_RESPECT_BATTERY_SAVER, enabled) }

    var keepAlive: Boolean
        get() = prefs.getBoolean(PREF_KEEP_ALIVE, true)
        set(enabled) = prefs.edit { putBoolean(PREF_KEEP_ALIVE, enabled) }

    var requireUnmeteredNetwork: Boolean
        get() = prefs.getBoolean(PREF_REQUIRE_UNMETERED_NETWORK, true)
        set(enabled) = prefs.edit { putBoolean(PREF_REQUIRE_UNMETERED_NETWORK, enabled) }

    var networkAllowWifi: Boolean
        get() = prefs.getBoolean(PREF_NETWORK_ALLOW_WIFI, true)
        set(enabled) = prefs.edit { putBoolean(PREF_NETWORK_ALLOW_WIFI, enabled) }

    var networkAllowCellular: Boolean
        get() = prefs.getBoolean(PREF_NETWORK_ALLOW_CELLULAR, true)
        set(enabled) = prefs.edit { putBoolean(PREF_NETWORK_ALLOW_CELLULAR, enabled) }

    var networkAllowEthernet: Boolean
        get() = prefs.getBoolean(PREF_NETWORK_ALLOW_ETHERNET, true)
        set(enabled) = prefs.edit { putBoolean(PREF_NETWORK_ALLOW_ETHERNET, enabled) }

    var networkAllowOther: Boolean
        get() = prefs.getBoolean(PREF_NETWORK_ALLOW_OTHER, true)
        set(enabled) = prefs.edit { putBoolean(PREF_NETWORK_ALLOW_OTHER, enabled) }

    var allowedWifiNetworks: Set<String>
        get() = prefs.getStringSet(PREF_ALLOWED_WIFI_NETWORKS, emptySet())!!
        set(networks) = prefs.edit { putStringSet(PREF_ALLOWED_WIFI_NETWORKS, networks) }

    var syncSchedule: Boolean
        get() = prefs.getBoolean(PREF_SYNC_SCHEDULE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_SYNC_SCHEDULE, enabled) }

    var scheduleCycleMs: Int
        get() = prefs.getInt(PREF_SCHEDULE_CYCLE_MS, 60 * 60 * 1000)
        set(ms) = prefs.edit { putInt(PREF_SCHEDULE_CYCLE_MS, ms) }

    var scheduleSyncMs: Int
        get() = prefs.getInt(PREF_SCHEDULE_SYNC_MS, 5 * 60 * 1000)
        set(ms) = prefs.edit { putInt(PREF_SCHEDULE_SYNC_MS, ms) }

    var isDebugMode: Boolean
        get() = prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    var isManualMode: Boolean
        get() = prefs.getBoolean(PREF_MANUAL_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_MANUAL_MODE, enabled) }

    var manualShouldRun: Boolean
        get() = prefs.getBoolean(PREF_MANUAL_SHOULD_RUN, false)
        set(enabled) = prefs.edit { putBoolean(PREF_MANUAL_SHOULD_RUN, enabled) }

    fun migrate() {
        if (prefs.contains(PREF_REQUIRE_SUFFICIENT_BATTERY)) {
            if (!prefs.getBoolean(PREF_REQUIRE_SUFFICIENT_BATTERY, true)) {
                minBatteryLevel = 0
            }

            prefs.edit { remove(PREF_REQUIRE_SUFFICIENT_BATTERY) }
        }
    }
}
