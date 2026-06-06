/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.ui.AppScreen
import com.chiller3.basicsync.ui.BetterSegmentedShapes
import com.chiller3.basicsync.ui.PreferenceCategory
import com.chiller3.basicsync.ui.PreferenceColumn
import com.chiller3.basicsync.ui.SplitSwitchPreference
import com.chiller3.basicsync.ui.SwitchPreference
import com.chiller3.basicsync.ui.theme.AppTheme

@Composable
fun SyncScheduleScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val prefs = remember { Preferences(context) }
    var reloadPrefs by remember { mutableIntStateOf(0) }
    val syncSchedule = remember(reloadPrefs) { prefs.syncSchedule }
    val syncScheduleIdle = remember(reloadPrefs) { prefs.syncScheduleIdle }
    val syncScheduleBatteryOnly = remember(reloadPrefs) { prefs.syncScheduleBatteryOnly }
    val scheduleCycleMs = remember(reloadPrefs) { prefs.scheduleCycleMs }
    val scheduleSyncMs = remember(reloadPrefs) { prefs.scheduleSyncMs }
    val scheduleIdleMs = remember(reloadPrefs) { prefs.scheduleIdleMs }

    val hasBattery = remember {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
    }

    AppScreen(
        title = { Text(text = stringResource(R.string.pref_sync_schedule_settings_name)) },
        onBack = onBack,
    ) { params ->
        SyncScheduleContent(
            syncSchedule = syncSchedule,
            syncScheduleIdle = syncScheduleIdle,
            syncScheduleBatteryOnly = syncScheduleBatteryOnly,
            scheduleCycleMs = scheduleCycleMs,
            scheduleSyncMs = scheduleSyncMs,
            scheduleIdleMs = scheduleIdleMs,
            hasBattery = hasBattery,
            onSyncScheduleChange = { enabled ->
                prefs.syncSchedule = enabled
                reloadPrefs++
            },
            onSyncScheduleIdleChange = { enabled ->
                prefs.syncScheduleIdle = enabled
                reloadPrefs++
            },
            onSyncScheduleBatteryOnlyChange = { enabled ->
                prefs.syncScheduleBatteryOnly = enabled
                reloadPrefs++
            },
            onScheduleCycleSyncMsChange = { cycleMs, syncMs ->
                prefs.scheduleCycleMs = cycleMs
                prefs.scheduleSyncMs = syncMs
                prefs.clampSyncScheduleDurations(true)
                reloadPrefs++
            },
            onScheduleIdleMsChange = { idleMs ->
                prefs.scheduleIdleMs = idleMs
                prefs.clampSyncScheduleDurations(true)
                reloadPrefs++
            },
            contentPadding = params.contentPadding,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SyncScheduleContent(
    syncSchedule: Boolean,
    syncScheduleIdle: Boolean,
    syncScheduleBatteryOnly: Boolean,
    scheduleCycleMs: Int,
    scheduleSyncMs: Int,
    scheduleIdleMs: Int,
    hasBattery: Boolean,
    onSyncScheduleChange: (Boolean) -> Unit,
    onSyncScheduleIdleChange: (Boolean) -> Unit,
    onSyncScheduleBatteryOnlyChange: (Boolean) -> Unit,
    onScheduleCycleSyncMsChange: (Int, Int) -> Unit,
    onScheduleIdleMsChange: (Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var showSyncScheduleDialog by rememberSaveable { mutableStateOf(false) }
    var showSyncScheduleIdleDialog by rememberSaveable { mutableStateOf(false) }

    PreferenceColumn(contentPadding = contentPadding) {
        item(key = "run_conditions") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_run_conditions)) },
            )
        }

        item(key = "sync_schedule") {
            SplitSwitchPreference(
                onClick = { showSyncScheduleDialog = true },
                checked = syncSchedule,
                onCheckedChange = onSyncScheduleChange,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_sync_schedule_name)) },
                summary = { Text(text = syncScheduleSummary(scheduleCycleMs, scheduleSyncMs)) },
            )
        }

        item(key = "sync_schedule_idle") {
            SplitSwitchPreference(
                onClick = { showSyncScheduleIdleDialog = true },
                checked = syncScheduleIdle,
                onCheckedChange = onSyncScheduleIdleChange,
                enabled = syncSchedule,
                shapes = if (hasBattery) {
                    BetterSegmentedShapes.middle()
                } else {
                    BetterSegmentedShapes.bottom()
                },
                title = { Text(text = stringResource(R.string.pref_sync_schedule_idle_name)) },
                summary = { Text(text = syncScheduleIdleSummary(scheduleIdleMs)) },
            )
        }

        if (hasBattery) {
            item(key = "sync_schedule_battery_only") {
                SwitchPreference(
                    checked = syncScheduleBatteryOnly,
                    onCheckedChange = onSyncScheduleBatteryOnlyChange,
                    enabled = syncSchedule,
                    shapes = BetterSegmentedShapes.bottom(),
                    title = { Text(text = stringResource(R.string.pref_sync_schedule_battery_only_name)) },
                    summary = { Text(text = stringResource(R.string.pref_sync_schedule_battery_only_desc)) },
                )
            }
        }
    }

    if (showSyncScheduleDialog) {
        SyncScheduleDialog(
            initialCycleMs = scheduleCycleMs,
            initialSyncMs = scheduleSyncMs,
            onSelect = { cycleMs, syncMs ->
                onScheduleCycleSyncMsChange(cycleMs, syncMs)
                showSyncScheduleDialog = false
            },
            onDismiss = {
                showSyncScheduleDialog = false
            },
        )
    }

    if (showSyncScheduleIdleDialog) {
        SyncScheduleIdleDialog(
            initialIdleMs = scheduleIdleMs,
            onSelect = { idleMs ->
                onScheduleIdleMsChange(idleMs)
                showSyncScheduleIdleDialog = false
            },
            onDismiss = {
                showSyncScheduleIdleDialog = false
            },
        )
    }
}

@Composable
private fun formatDurationMs(duration: Int): String =
    if (duration % (60 * 60 * 1000) == 0) {
        val hours = duration / 60 / 60 / 1000
        pluralStringResource(R.plurals.duration_hours, hours, hours)
    } else if (duration % (60 * 1000) == 0) {
        val minutes = duration / 60 / 1000
        pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    } else {
        val seconds = duration / 1000
        pluralStringResource(R.plurals.duration_seconds, seconds, seconds)
    }

@Composable
private fun syncScheduleSummary(scheduleCycleMs: Int, scheduleSyncMs: Int): String {
    val cycleDuration = formatDurationMs(scheduleCycleMs)
    val syncDuration = formatDurationMs(scheduleSyncMs)

    return stringResource(R.string.pref_sync_schedule_desc, cycleDuration, syncDuration)
}

@Composable
private fun syncScheduleIdleSummary(scheduleIdleMs: Int): String {
    val idleDuration = formatDurationMs(scheduleIdleMs)

    return stringResource(R.string.pref_sync_schedule_idle_desc, idleDuration)
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewSyncScheduleScreen() {
    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.pref_sync_schedule_settings_name)) },
            onBack = {},
        ) { params ->
            SyncScheduleContent(
                syncSchedule = true,
                syncScheduleIdle = true,
                syncScheduleBatteryOnly = true,
                scheduleCycleMs = 60 * 60 * 1000,
                scheduleSyncMs = 5 * 60 * 1000,
                scheduleIdleMs = 3 * 60 * 1000,
                hasBattery = true,
                onSyncScheduleChange = {},
                onSyncScheduleIdleChange = {},
                onSyncScheduleBatteryOnlyChange = {},
                onScheduleCycleSyncMsChange = { _, _ -> },
                onScheduleIdleMsChange = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
