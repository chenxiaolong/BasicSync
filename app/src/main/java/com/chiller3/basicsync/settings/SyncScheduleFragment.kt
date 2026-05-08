/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Bundle
import androidx.fragment.app.FragmentResultListener
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.dialog.SyncScheduleDialogFragment
import com.chiller3.basicsync.dialog.SyncScheduleIdleDialogFragment
import com.chiller3.basicsync.view.SplitSwitchPreference

class SyncScheduleFragment : PreferenceBaseFragment(), FragmentResultListener,
    Preference.OnPreferenceClickListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Preferences
    private lateinit var prefSyncSchedule: SplitSwitchPreference
    private lateinit var prefSyncScheduleIdle: SplitSwitchPreference
    private lateinit var prefSyncScheduleBatteryOnly: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_schedule, rootKey)

        val context = requireContext()

        prefs = Preferences(context)

        prefSyncSchedule = findPreference(Preferences.PREF_SYNC_SCHEDULE)!!
        prefSyncSchedule.onPreferenceClickListener = this

        prefSyncScheduleIdle = findPreference(Preferences.PREF_SYNC_SCHEDULE_IDLE)!!
        prefSyncScheduleIdle.onPreferenceClickListener = this

        prefSyncScheduleBatteryOnly = findPreference(Preferences.PREF_SYNC_SCHEDULE_BATTERY_ONLY)!!

        refreshSchedule()

        for (key in arrayOf(
            SyncScheduleDialogFragment.TAG,
            SyncScheduleIdleDialogFragment.TAG,
        )) {
            parentFragmentManager.setFragmentResultListener(key, this, this)
        }
    }

    override fun onResume() {
        super.onResume()

        prefs.registerListener(this)
    }

    override fun onPause() {
        super.onPause()

        prefs.unregisterListener(this)
    }

    private fun formatDurationMs(duration: Int): String =
        if (duration % (60 * 60 * 1000) == 0) {
            val hours = duration / 60 / 60 / 1000
            resources.getQuantityString(R.plurals.duration_hours, hours, hours)
        } else if (duration % (60 * 1000) == 0) {
            val minutes = duration / 60 / 1000
            resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
        } else {
            val seconds = duration / 1000
            resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
        }

    private fun refreshSchedule() {
        val context = requireContext()

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val hasBattery = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true

        val cycleDuration = formatDurationMs(prefs.scheduleCycleMs)
        val syncDuration = formatDurationMs(prefs.scheduleSyncMs)
        val idleDuration = formatDurationMs(prefs.scheduleIdleMs)

        prefSyncSchedule.summary =
            getString(R.string.pref_sync_schedule_desc, cycleDuration, syncDuration)

        prefSyncScheduleIdle.summary =
            getString(R.string.pref_sync_schedule_idle_desc, idleDuration)
        prefSyncScheduleIdle.isEnabled = prefSyncSchedule.isChecked

        prefSyncScheduleBatteryOnly.isVisible = hasBattery
        prefSyncScheduleBatteryOnly.isEnabled = prefSyncSchedule.isChecked
    }

    override fun onFragmentResult(requestKey: String, bundle: Bundle) {
        when (requestKey) {
            SyncScheduleDialogFragment.TAG -> {
                if (bundle.getBoolean(SyncScheduleDialogFragment.RESULT_SUCCESS)) {
                    refreshSchedule()
                }
            }
            SyncScheduleIdleDialogFragment.TAG -> {
                if (bundle.getBoolean(SyncScheduleIdleDialogFragment.RESULT_SUCCESS)) {
                    refreshSchedule()
                }
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            prefSyncSchedule -> {
                SyncScheduleDialogFragment().show(
                    parentFragmentManager.beginTransaction(),
                    SyncScheduleDialogFragment.TAG,
                )
                return true
            }
            prefSyncScheduleIdle -> {
                SyncScheduleIdleDialogFragment().show(
                    parentFragmentManager.beginTransaction(),
                    SyncScheduleIdleDialogFragment.TAG,
                )
                return true
            }
        }

        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Preferences.PREF_SYNC_SCHEDULE, Preferences.PREF_SYNC_SCHEDULE_IDLE -> refreshSchedule()
        }
    }
}
