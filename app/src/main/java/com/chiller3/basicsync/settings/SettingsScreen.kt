/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.basicsync.BuildConfig
import com.chiller3.basicsync.Logcat
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.binding.stbridge.Stbridge
import com.chiller3.basicsync.extension.formattedString
import com.chiller3.basicsync.syncthing.BlockedReason
import com.chiller3.basicsync.syncthing.SyncthingService
import com.chiller3.basicsync.ui.AppScreen
import com.chiller3.basicsync.ui.BetterSegmentedShapes
import com.chiller3.basicsync.ui.Preference
import com.chiller3.basicsync.ui.PreferenceCategory
import com.chiller3.basicsync.ui.PreferenceColumn
import com.chiller3.basicsync.ui.PreferencesChangedEffect
import com.chiller3.basicsync.ui.SplitSwitchPreference
import com.chiller3.basicsync.ui.SwitchPreference
import com.chiller3.basicsync.ui.betterSegmentedShapes
import com.chiller3.basicsync.ui.theme.AppTheme
import kotlinx.coroutines.flow.update
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.EnumSet

private const val TAG = "SettingsScreen"

private const val BACKUP_MIMETYPE = "application/zip"

private val BACKUP_DATE_FORMATTER = DateTimeFormatterBuilder()
    .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
    .appendValue(ChronoField.DAY_OF_MONTH, 2)
    .appendLiteral('_')
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
    .toFormatter()

@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current

    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    val prefs = remember { Preferences(context) }
    var reloadPrefs by remember { mutableIntStateOf(0) }
    val isManualMode = remember(reloadPrefs) { prefs.isManualMode }
    val runOnBattery = remember(reloadPrefs) { prefs.runOnBattery }
    val minBatteryLevel = remember(reloadPrefs) { prefs.minBatteryLevel }
    val respectBatterySaver = remember(reloadPrefs) { prefs.respectBatterySaver }
    val respectAutoSyncData = remember(reloadPrefs) { prefs.respectAutoSyncData }
    val keepAlive = remember(reloadPrefs) { prefs.keepAlive }
    val remoteControl = remember(reloadPrefs) { prefs.remoteControl }
    val allowAutoMode = remember(reloadPrefs) { prefs.allowAutoMode }
    val showExit = remember(reloadPrefs) { prefs.showExit }
    val isDebugMode = remember(reloadPrefs) { prefs.isDebugMode }

    var reloadPerms by remember { mutableIntStateOf(0) }
    val inhibitBatteryOpt = remember(reloadPerms) { Permissions.isInhibitingBatteryOpt(context) }
    val notificationsGranted = remember(reloadPerms) {
        Permissions.have(context, Permissions.NOTIFICATION)
    }
    val localStorageAccess = remember(reloadPerms) { Permissions.haveLocalStorage(context) }
    // Hide this while loading to avoid jank in the usual case.
    var appHibernationDisabled by rememberSaveable { mutableStateOf(true) }

    val hasBattery = remember {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
    }

    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val importExportState by viewModel.importExportState.collectAsStateWithLifecycle()

    val requestInhibitBatteryOpt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (it.resultCode == Activity.RESULT_CANCELED && isTv) {
            viewModel.addAlert(SettingsAlert.TvInhibitBatteryOpt)
        } else {
            reloadPerms++
        }
    }
    val requestPermissionActivity = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Needed for the local storage permission. We intentionally don't check resultCode here
        // because going back to the app after granting the permission results in RESULT_CANCELED.
        SyncthingService.start(context, SyncthingService.ACTION_RENOTIFY)

        reloadPerms++
    }
    val requestPermissionsRequired = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.all { it.value }) {
            // Resend service notification so that the user can actually interact with the service.
            SyncthingService.start(context, SyncthingService.ACTION_RENOTIFY)

            reloadPerms++
        } else {
            requestPermissionActivity.launch(Permissions.getAppInfoIntent(context))
        }
    }
    val requestSafImportConfiguration = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            viewModel.startImportExport(ImportExportMode.IMPORT, it)
        }
    }
    val requestSafExportConfiguration = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIMETYPE),
    ) { uri ->
        uri?.let {
            viewModel.startImportExport(ImportExportMode.EXPORT, it)
        }
    }
    val requestSafSaveLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Logcat.MIMETYPE),
    ) { uri ->
        uri?.let {
            viewModel.saveLogs(it)
        }
    }

    val watcher = rememberServiceEventWatcher(
        listener = object : SyncthingService.ServiceListener {
            override fun onExitRequested() = onExit()

            override fun onRunStateChanged(
                state: SyncthingService.ServiceState,
                guiInfo: SyncthingService.GuiInfo?,
            ) {
                viewModel.serviceState.update { state }
            }

            override fun onPreRunActionResult(
                preRunAction: SyncthingService.PreRunAction,
                exception: Exception?,
            ) {
                viewModel.onPreRunActionResult(preRunAction, exception)
            }

            override fun onConflictsUpdated(conflicts: List<String>) {
                viewModel.conflicts.update { conflicts }
            }
        },
    )

    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = { Text(text = stringResource(R.string.app_name)) },
    ) { params ->
        LaunchedEffect(Unit) {
            viewModel.alerts.collect { alerts ->
                val alert = alerts.firstOrNull() ?: return@collect
                val msg = when (alert) {
                    SettingsAlert.ImportSucceeded ->
                        resources.getString(R.string.alert_import_success)
                    SettingsAlert.ExportSucceeded ->
                        resources.getString(R.string.alert_export_success)
                    is SettingsAlert.ImportFailed ->
                        resources.getString(R.string.alert_import_failure)
                    is SettingsAlert.ExportFailed ->
                        resources.getString(R.string.alert_export_failure)
                    SettingsAlert.ImportCancelled ->
                        resources.getString(R.string.alert_import_cancelled)
                    SettingsAlert.ExportCancelled ->
                        resources.getString(R.string.alert_export_cancelled)
                    is SettingsAlert.LogcatSucceeded ->
                        resources.getString(R.string.alert_logcat_success, alert.uri.formattedString)
                    is SettingsAlert.LogcatFailed ->
                        resources.getString(R.string.alert_logcat_failure, alert.uri.formattedString)
                    SettingsAlert.BrowserNotFound ->
                        resources.getString(R.string.alert_browser_not_found)
                    SettingsAlert.TvInhibitBatteryOpt ->
                        resources.getString(R.string.alert_tv_inhibit_battery_opt)
                }
                val details = when (alert) {
                    SettingsAlert.ImportSucceeded -> null
                    SettingsAlert.ExportSucceeded -> null
                    is SettingsAlert.ImportFailed -> alert.error
                    is SettingsAlert.ExportFailed -> alert.error
                    SettingsAlert.ImportCancelled -> null
                    SettingsAlert.ExportCancelled -> null
                    is SettingsAlert.LogcatSucceeded -> null
                    is SettingsAlert.LogcatFailed -> alert.error
                    SettingsAlert.BrowserNotFound -> null
                    is SettingsAlert.TvInhibitBatteryOpt -> buildString {
                        append(resources.getString(R.string.alert_tv_inhibit_battery_opt_details))
                        append("\n\n")
                        append(alert.COMMAND)
                    }
                }

                // Snack bars are not focusable on TVs, so just show the dialog directly.
                if (isTv) {
                    showErrorDialog = details
                }

                val result = params.snackbarHostState.showSnackbar(
                    message = msg,
                    if (isTv) {
                        null
                    } else {
                        details?.let { resources.getString(R.string.action_details) }
                    },
                    withDismissAction = !isTv,
                )
                viewModel.acknowledgeFirstAlert()

                when (result) {
                    SnackbarResult.Dismissed -> {}
                    SnackbarResult.ActionPerformed -> { showErrorDialog = details }
                }
            }
        }

        showErrorDialog?.let { message ->
            ErrorDetailsDialog(
                message = message,
                onDismiss = { showErrorDialog = null },
                showCopy = !isTv,
            )
        }

        SettingsContent(
            serviceState = serviceState,
            importExportState = importExportState,
            inhibitBatteryOpt = inhibitBatteryOpt,
            notificationsGranted = notificationsGranted,
            localStorageAccess = localStorageAccess,
            appHibernationDisabled = appHibernationDisabled,
            conflicts = conflicts ?: emptyList(),
            isManualMode = isManualMode,
            hasBattery = hasBattery,
            runOnBattery = runOnBattery,
            minBatteryLevel = minBatteryLevel,
            respectBatterySaver = respectBatterySaver,
            respectAutoSyncData = respectAutoSyncData,
            keepAlive = keepAlive,
            remoteControl = remoteControl,
            allowAutoMode = allowAutoMode,
            showExit = showExit,
            isDebugMode = isDebugMode,
            onInhibitBatteryOptGrant = {
                requestInhibitBatteryOpt.launch(Permissions.getInhibitBatteryOptIntent(context))
            },
            onNotificationsGrant = {
                requestPermissionsRequired.launch(Permissions.NOTIFICATION)
            },
            onLocalStorageAccessGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        "package:${BuildConfig.APPLICATION_ID}".toUri(),
                    )

                    requestPermissionActivity.launch(intent)
                } else {
                    requestPermissionsRequired.launch(Permissions.LEGACY_STORAGE)
                }
            },
            onAppHibernationDisable = {
                requestPermissionActivity.launch(
                    IntentCompat.createManageUnusedAppRestrictionsIntent(
                        context,
                        context.packageName,
                    )
                )
            },
            onConflictsOpen = {
                context.startActivity(Intent(context, ConflictsActivity::class.java))
            },
            onWebUiOpen = {
                context.startActivity(Intent(context, WebUiActivity::class.java))
            },
            onConfigurationImport = {
                requestSafImportConfiguration.launch(arrayOf(BACKUP_MIMETYPE))
            },
            onConfigurationExport = {
                val timestamp = BACKUP_DATE_FORMATTER.format(ZonedDateTime.now())
                val defaultName = "${resources.getString(R.string.app_name_release)}_$timestamp"

                requestSafExportConfiguration.launch(defaultName)
            },
            onServiceStatusChange = { enabled ->
                val action = if (enabled) {
                    SyncthingService.ACTION_START
                } else {
                    SyncthingService.ACTION_STOP
                }

                SyncthingService.start(context, action)
            },
            onManualModeChange = { enabled ->
                val action = if (enabled) {
                    SyncthingService.ACTION_MANUAL_MODE
                } else {
                    SyncthingService.ACTION_AUTO_MODE
                }

                SyncthingService.start(context, action)
            },
            onNetworkConditionsOpen = {
                context.startActivity(Intent(context, NetworkConditionsActivity::class.java))
            },
            onRunOnBatteryChange = { enabled ->
                prefs.runOnBattery = enabled
                reloadPrefs++
            },
            onMinBatteryLevelChange = { level ->
                prefs.minBatteryLevel = level
                reloadPrefs++
            },
            onRespectBatterySaverChange = { enabled ->
                prefs.respectBatterySaver = enabled
                reloadPrefs++
            },
            onRespectAutoSyncDataChange = { enabled ->
                prefs.respectAutoSyncData = enabled
                reloadPrefs++
            },
            onSyncScheduleSettingsOpen = {
                context.startActivity(Intent(context, SyncScheduleActivity::class.java))
            },
            onKeepAliveChange = { enabled ->
                prefs.keepAlive = enabled
                reloadPrefs++
            },
            onRemoteControlChange = { enabled ->
                prefs.remoteControl = enabled
                reloadPrefs++
            },
            onAllowAutoModeChange = { enabled ->
                prefs.allowAutoMode = enabled
                reloadPrefs++

                val action = if (enabled) {
                    SyncthingService.ACTION_RENOTIFY
                } else {
                    SyncthingService.ACTION_MANUAL_MODE
                }

                SyncthingService.start(context, action)
            },
            onShowExitChange = { enabled ->
                prefs.showExit = enabled
                reloadPrefs++
            },
            onDebugModeChange = { enabled ->
                prefs.isDebugMode = enabled
                reloadPrefs++
            },
            onSourceRepoOpen = {
                val uri = BuildConfig.PROJECT_URL_AT_COMMIT.toUri()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    viewModel.addAlert(SettingsAlert.BrowserNotFound)
                }
            },
            onSaveLogs = {
                requestSafSaveLogs.launch(Logcat.FILENAME_DEFAULT)
            },
            contentPadding = params.contentPadding,
        )
    }

    importExportState?.let { state ->
        when (state.status) {
            ImportExportState.Status.NEED_PASSWORD -> {
                PasswordDialog(
                    mode = state.mode,
                    onSelect = { password ->
                        viewModel.setImportExportPassword(SyncthingService.Password(password))
                    },
                    onDismiss = {
                        viewModel.cancelPendingImportExport()
                    },
                )
            }
            ImportExportState.Status.READY_TO_RUN -> {
                LaunchedEffect(state.status) {
                    watcher.runWithBinder { binder ->
                        viewModel.setRunningImportExport()

                        when (state.mode) {
                            ImportExportMode.IMPORT ->
                                binder.importConfiguration(state.uri, state.password)
                            ImportExportMode.EXPORT ->
                                binder.exportConfiguration(state.uri, state.password)
                        }
                    }
                }
            }
            ImportExportState.Status.IN_PROGRESS -> {}
        }
    }

    PreferencesChangedEffect(LocalLifecycleOwner.current) { key ->
        if (key == Preferences.PREF_MANUAL_MODE) {
            reloadPrefs++
        }
    }

    DisposableEffect(LocalLifecycleOwner.current, reloadPerms) {
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
        future.addListener({
            val enabled = when (val status = future.get()) {
                UnusedAppRestrictionsConstants.ERROR,
                UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE,
                UnusedAppRestrictionsConstants.DISABLED -> false

                UnusedAppRestrictionsConstants.API_30_BACKPORT,
                UnusedAppRestrictionsConstants.API_30,
                UnusedAppRestrictionsConstants.API_31 -> true

                else -> {
                    Log.w(TAG, "Unrecognized app hibernation status: $status")
                    false
                }
            }

            appHibernationDisabled = !enabled
        }, context.mainExecutor)

        onDispose {
            future.cancel(true)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsContent(
    serviceState: SyncthingService.ServiceState?,
    importExportState: ImportExportState?,
    inhibitBatteryOpt: Boolean,
    notificationsGranted: Boolean,
    localStorageAccess: Boolean,
    appHibernationDisabled: Boolean,
    conflicts: List<String>,
    isManualMode: Boolean,
    hasBattery: Boolean,
    runOnBattery: Boolean,
    minBatteryLevel: Int,
    respectBatterySaver: Boolean,
    respectAutoSyncData: Boolean,
    keepAlive: Boolean,
    remoteControl: Boolean,
    allowAutoMode: Boolean,
    showExit: Boolean,
    isDebugMode: Boolean,
    onInhibitBatteryOptGrant: () -> Unit,
    onNotificationsGrant: () -> Unit,
    onLocalStorageAccessGrant: () -> Unit,
    onAppHibernationDisable: () -> Unit,
    onConflictsOpen: () -> Unit,
    onWebUiOpen: () -> Unit,
    onConfigurationImport: () -> Unit,
    onConfigurationExport: () -> Unit,
    onServiceStatusChange: (Boolean) -> Unit,
    onManualModeChange: (Boolean) -> Unit,
    onNetworkConditionsOpen: () -> Unit,
    onRunOnBatteryChange: (Boolean) -> Unit,
    onMinBatteryLevelChange: (Int) -> Unit,
    onRespectBatterySaverChange: (Boolean) -> Unit,
    onRespectAutoSyncDataChange: (Boolean) -> Unit,
    onSyncScheduleSettingsOpen: () -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onRemoteControlChange: (Boolean) -> Unit,
    onAllowAutoModeChange: (Boolean) -> Unit,
    onShowExitChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onSourceRepoOpen: () -> Unit,
    onSaveLogs: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    data class MissingPermission(
        val key: String,
        val title: String,
        val summary: String,
        val onGrant: () -> Unit,
    )

    val missingPermissions = mutableListOf<MissingPermission>().apply {
        if (!inhibitBatteryOpt) {
            add(MissingPermission(
                key = "inhibit_battery_opt",
                title = stringResource(R.string.pref_inhibit_battery_opt_name),
                summary = stringResource(R.string.pref_inhibit_battery_opt_desc),
                onGrant = onInhibitBatteryOptGrant,
            ))
        }
        if (!notificationsGranted) {
            add(MissingPermission(
                key = "allow_notifications",
                title = stringResource(R.string.pref_allow_notifications_name),
                summary = stringResource(R.string.pref_allow_notifications_desc),
                onGrant = onNotificationsGrant,
            ))
        }
        if (!localStorageAccess) {
            add(MissingPermission(
                key = "local_storage_access",
                title = stringResource(R.string.pref_local_storage_access_name),
                summary = stringResource(R.string.pref_local_storage_access_desc),
                onGrant = onLocalStorageAccessGrant,
            ))
        }
        if (!appHibernationDisabled) {
            add(MissingPermission(
                key = "disable_app_hibernation",
                title = stringResource(R.string.pref_disable_app_hibernation_name),
                summary = stringResource(R.string.pref_disable_app_hibernation_desc),
                onGrant = onAppHibernationDisable,
            ))
        }
    }

    val runState = serviceState?.runState

    var showMinBatteryLevelDialog by rememberSaveable { mutableStateOf(false) }

    PreferenceColumn(contentPadding = contentPadding) {
        if (missingPermissions.isNotEmpty()) {
            item(key = "permissions") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_permissions)) },
                    modifier = Modifier.animateItem(),
                )
            }

            itemsIndexed(missingPermissions, key = { _, m -> m.key }) { index, missing ->
                Preference(
                    onClick = missing.onGrant,
                    shapes = betterSegmentedShapes(index, missingPermissions.size),
                    title = { Text(text = missing.title) },
                    summary = { Text(text = missing.summary) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "configuration") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_configuration)) },
                modifier = Modifier.animateItem(),
            )
        }

        if (conflicts.isNotEmpty()) {
            item(key = "conflicts") {
                val summary = pluralStringResource(
                    R.plurals.pref_conflicts_desc,
                    conflicts.size,
                    conflicts.size,
                )

                Preference(
                    onClick = onConflictsOpen,
                    shapes = BetterSegmentedShapes.top(),
                    title = { Text(text = stringResource(R.string.pref_conflicts_name)) },
                    summary = { Text(text = summary) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "open_web_ui") {
            Preference(
                onClick = onWebUiOpen,
                enabled = runState?.webUiAvailable == true,
                shapes = if (conflicts.isNotEmpty()) {
                    BetterSegmentedShapes.middle()
                } else {
                    BetterSegmentedShapes.top()
                },
                title = { Text(text = stringResource(R.string.pref_open_web_ui_name)) },
                summary = { Text(text = stringResource(R.string.pref_open_web_ui_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "import_configuration") {
            Preference(
                onClick = onConfigurationImport,
                enabled = serviceState != null && importExportState == null,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_import_configuration_name)) },
                summary = { Text(text = stringResource(R.string.pref_import_configuration_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "export_configuration") {
            Preference(
                onClick = onConfigurationExport,
                enabled = serviceState != null && importExportState == null,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_export_configuration_name)) },
                summary = { Text(text = stringResource(R.string.pref_export_configuration_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "run_conditions") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_run_conditions)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "service_status") {
            SwitchPreference(
                checked = runState == SyncthingService.RunState.RUNNING
                        || runState == SyncthingService.RunState.STARTING,
                onCheckedChange = onServiceStatusChange,
                enabled = isManualMode,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_service_status_name)) },
                summary = { serviceStatusSummary(serviceState)?.let { Text(text = it) } },
                modifier = Modifier.animateItem(),
            )
        }

        if (allowAutoMode) {
            item(key = "auto_mode") {
                SwitchPreference(
                    checked = !isManualMode,
                    onCheckedChange = { onManualModeChange(!it) },
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_auto_mode_name)) },
                    summary = { Text(text = stringResource(R.string.pref_auto_mode_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "network_conditions") {
                Preference(
                    onClick = onNetworkConditionsOpen,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_network_conditions_name)) },
                    summary = { Text(text = stringResource(R.string.pref_network_conditions_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            if (hasBattery) {
                item(key = "run_on_battery") {
                    val summary = stringResource(R.string.pref_run_on_battery_desc, minBatteryLevel)

                    SplitSwitchPreference(
                        onClick = { showMinBatteryLevelDialog = true },
                        checked = runOnBattery,
                        onCheckedChange = onRunOnBatteryChange,
                        shapes = BetterSegmentedShapes.middle(),
                        title = { Text(text = stringResource(R.string.pref_run_on_battery_name)) },
                        summary = { Text(text = summary) },
                        modifier = Modifier.animateItem(),
                    )
                }

                item(key = "respect_battery_saver") {
                    SwitchPreference(
                        checked = respectBatterySaver,
                        onCheckedChange = onRespectBatterySaverChange,
                        shapes = BetterSegmentedShapes.middle(),
                        title = { Text(text = stringResource(R.string.pref_respect_battery_saver_name)) },
                        summary = { Text(text = stringResource(R.string.pref_respect_battery_saver_desc)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item(key = "respect_auto_sync_data") {
                SwitchPreference(
                    checked = respectAutoSyncData,
                    onCheckedChange = onRespectAutoSyncDataChange,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_respect_auto_sync_data_name)) },
                    summary = { Text(text = stringResource(R.string.pref_respect_auto_sync_data_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "sync_schedule_settings") {
                Preference(
                    onClick = onSyncScheduleSettingsOpen,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_sync_schedule_settings_name)) },
                    summary = { Text(text = stringResource(R.string.pref_sync_schedule_settings_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "keep_alive") {
            SwitchPreference(
                checked = keepAlive,
                onCheckedChange = onKeepAliveChange,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_keep_alive_name)) },
                summary = { Text(text = stringResource(R.string.pref_keep_alive_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "advanced") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_advanced)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "remote_control") {
            SwitchPreference(
                checked = remoteControl,
                onCheckedChange = onRemoteControlChange,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_remote_control_name)) },
                summary = { Text(text = stringResource(R.string.pref_remote_control_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "allow_auto_mode") {
            SwitchPreference(
                checked = allowAutoMode,
                onCheckedChange = onAllowAutoModeChange,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_allow_auto_mode_name)) },
                summary = { Text(text = stringResource(R.string.pref_allow_auto_mode_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "show_exit") {
            SwitchPreference(
                checked = showExit,
                onCheckedChange = onShowExitChange,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_show_exit_name)) },
                summary = { Text(text = stringResource(R.string.pref_show_exit_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "about") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_about)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "version") {
            Preference(
                onClick = onSourceRepoOpen,
                onLongClick = { onDebugModeChange(!isDebugMode) },
                shapes = BetterSegmentedShapes.single(),
                title = { Text(text = stringResource(R.string.pref_version_name)) },
                summary = { Text(text = versionSummary(isDebugMode)) },
                modifier = Modifier.animateItem(),
            )
        }

        if (isDebugMode) {
            item(key = "debug") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_debug)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "save_logs") {
                Preference(
                    onClick = onSaveLogs,
                    shapes = BetterSegmentedShapes.single(),
                    title = { Text(text = stringResource(R.string.pref_save_logs_name)) },
                    summary = { Text(text = stringResource(R.string.pref_save_logs_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (showMinBatteryLevelDialog) {
        MinBatteryLevelDialog(
            initialLevel = minBatteryLevel,
            onSelect = { level ->
                onMinBatteryLevelChange(level)
                showMinBatteryLevelDialog = false
            },
            onDismiss = {
                showMinBatteryLevelDialog = false
            },
        )
    }
}

@Composable
private fun serviceStatusSummary(state: SyncthingService.ServiceState?) = state?.let {
    buildString {
        val resources = LocalResources.current
        val runState = state.runState

        val statusText = when (runState) {
            SyncthingService.RunState.RUNNING ->
                stringResource(R.string.notification_persistent_running_title)
            SyncthingService.RunState.NOT_RUNNING ->
                stringResource(R.string.notification_persistent_not_running_title)
            SyncthingService.RunState.PAUSED ->
                stringResource(R.string.notification_persistent_paused_title)
            SyncthingService.RunState.STARTING ->
                stringResource(R.string.notification_persistent_starting_title)
            SyncthingService.RunState.STOPPING ->
                stringResource(R.string.notification_persistent_stopping_title)
            SyncthingService.RunState.PAUSING ->
                stringResource(R.string.notification_persistent_pausing_title)
            SyncthingService.RunState.IMPORTING ->
                stringResource(R.string.notification_persistent_importing_title)
            SyncthingService.RunState.EXPORTING ->
                stringResource(R.string.notification_persistent_exporting_title)
        }
        append(statusText)

        if (runState.showBlockedReasons) {
            for (reason in state.blockedReasons) {
                append('\n')
                append(reason.toString(resources))
            }
        }
    }
}

@Composable
private fun versionSummary(isDebugMode: Boolean) = buildString {
    append(BuildConfig.VERSION_NAME)

    append(" (")
    append(BuildConfig.BUILD_TYPE)
    if (isDebugMode) {
        append("+debugmode")
    }
    append(")\nsyncthing ")

    append(Stbridge.version())
}

@SuppressLint("SdCardPath")
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
private fun PreviewSettingsScreen() {
    val serviceState = SyncthingService.ServiceState(
        keepAlive = false,
        blockedReasons = EnumSet.noneOf(BlockedReason::class.java),
        isStarted = true,
        isResumed = true,
        manualMode = false,
        allowAutoMode = true,
        preRunAction = null,
        showExit = false,
        folderStates = SyncthingService.FolderStates(),
        deviceStates = SyncthingService.DeviceStates(),
    )

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.app_name)) },
        ) { params ->
            SettingsContent(
                serviceState = serviceState,
                importExportState = null,
                inhibitBatteryOpt = false,
                notificationsGranted = false,
                localStorageAccess = false,
                appHibernationDisabled = false,
                conflicts = listOf(""),
                isManualMode = false,
                hasBattery = true,
                runOnBattery = true,
                minBatteryLevel = 20,
                respectBatterySaver = true,
                respectAutoSyncData = true,
                keepAlive = false,
                remoteControl = false,
                allowAutoMode = true,
                showExit = false,
                isDebugMode = true,
                onInhibitBatteryOptGrant = {},
                onNotificationsGrant = {},
                onLocalStorageAccessGrant = {},
                onAppHibernationDisable = {},
                onConflictsOpen = {},
                onWebUiOpen = {},
                onConfigurationImport = {},
                onConfigurationExport = {},
                onServiceStatusChange = {},
                onManualModeChange = {},
                onNetworkConditionsOpen = {},
                onRunOnBatteryChange = {},
                onMinBatteryLevelChange = {},
                onRespectBatterySaverChange = {},
                onRespectAutoSyncDataChange = {},
                onSyncScheduleSettingsOpen = {},
                onKeepAliveChange = {},
                onRemoteControlChange = {},
                onAllowAutoModeChange = {},
                onShowExitChange = {},
                onDebugModeChange = {},
                onSourceRepoOpen = {},
                onSaveLogs = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
