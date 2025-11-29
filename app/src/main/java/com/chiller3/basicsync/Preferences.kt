/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
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
        const val PREF_REQUIRE_UNMETERED_NETWORK = "require_unmetered_network"
        const val PREF_REQUIRE_SUFFICIENT_BATTERY = "require_sufficient_battery"

        // Main UI actions only.
        const val PREF_INHIBIT_BATTERY_OPT = "inhibit_battery_opt"
        const val PREF_ALLOW_NOTIFICATIONS = "allow_notifications"
        const val PREF_LOCAL_STORAGE_ACCESS = "local_storage_access"
        const val PREF_DISABLE_APP_HIBERNATION = "disable_app_hibernation"
        const val PREF_OPEN_WEB_UI = "open_web_ui"
        const val PREF_IMPORT_CONFIGURATION = "import_configuration"
        const val PREF_EXPORT_CONFIGURATION = "export_configuration"
        const val PREF_SERVICE_STATUS = "service_status"
        const val PREF_AUTO_MODE = "auto_mode"
        const val PREF_VERSION = "version"
        const val PREF_SAVE_LOGS = "save_logs"

        // Not associated with a UI preference.
        const val PREF_DEBUG_MODE = "debug_mode"
        const val PREF_MANUAL_MODE = "manual_mode"
        const val PREF_MANUAL_SHOULD_RUN = "manual_should_run"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun registerListener(listener: OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var requireUnmeteredNetwork: Boolean
        get() = prefs.getBoolean(PREF_REQUIRE_UNMETERED_NETWORK, true)
        set(enabled) = prefs.edit { putBoolean(PREF_REQUIRE_UNMETERED_NETWORK, enabled) }

    var requireSufficientBattery: Boolean
        get() = prefs.getBoolean(PREF_REQUIRE_SUFFICIENT_BATTERY, true)
        set(enabled) = prefs.edit { putBoolean(PREF_REQUIRE_SUFFICIENT_BATTERY, enabled) }

    var isDebugMode: Boolean
        get() = prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    var isManualMode: Boolean
        get() = prefs.getBoolean(PREF_MANUAL_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_MANUAL_MODE, enabled) }

    var manualShouldRun: Boolean
        get() = prefs.getBoolean(PREF_MANUAL_SHOULD_RUN, false)
        set(enabled) = prefs.edit { putBoolean(PREF_MANUAL_SHOULD_RUN, enabled) }
}
