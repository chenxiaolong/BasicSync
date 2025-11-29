/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.chiller3.basicsync.BuildConfig
import com.chiller3.basicsync.Logcat
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.binding.stbridge.Stbridge
import com.chiller3.basicsync.dialog.MessageDialogFragment
import com.chiller3.basicsync.extension.formattedString
import com.chiller3.basicsync.syncthing.SyncthingService
import com.chiller3.basicsync.view.LongClickablePreference
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField

class SettingsFragment : PreferenceBaseFragment(), Preference.OnPreferenceClickListener,
    LongClickablePreference.OnPreferenceLongClickListener, Preference.OnPreferenceChangeListener,
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = SettingsFragment::class.java.simpleName

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
    }

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var prefs: Preferences
    private lateinit var categoryPermissions: PreferenceCategory
    private lateinit var categoryConfiguration: PreferenceCategory
    private lateinit var categoryDebug: PreferenceCategory
    private lateinit var prefInhibitBatteryOpt: Preference
    private lateinit var prefAllowNotifications: Preference
    private lateinit var prefLocalStorageAccess: Preference
    private lateinit var prefDisableAppHibernation: Preference
    private lateinit var prefOpenWebUi: Preference
    private lateinit var prefImportConfiguration: Preference
    private lateinit var prefExportConfiguration: Preference
    private lateinit var prefServiceStatus: SwitchPreferenceCompat
    private lateinit var prefAutoMode: SwitchPreferenceCompat
    private lateinit var prefRequireSufficientBattery: SwitchPreferenceCompat
    private lateinit var prefVersion: LongClickablePreference
    private lateinit var prefSaveLogs: Preference

    private var appHibernationEnabled: Boolean? = null

    private val requestInhibitBatteryOpt =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }
    private val requestPermissionRequired =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.all { it.value }) {
                // Resend service notification so that the user can actually interact with the
                // service.
                startSyncthingService(SyncthingService.ACTION_RENOTIFY)

                refreshPermissions()
            } else {
                startActivity(Permissions.getAppInfoIntent(requireContext()))
            }
        }
    private val requestDisableAppHibernation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshAppHibernation()
        }
    private val requestSafImportConfiguration =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                viewModel.importConfiguration(uri)
            }
        }
    private val requestSafExportConfiguration =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIMETYPE)) { uri ->
            uri?.let {
                viewModel.exportConfiguration(uri)
            }
        }
    private val requestSafSaveLogs =
        registerForActivityResult(ActivityResultContracts.CreateDocument(Logcat.MIMETYPE)) { uri ->
            uri?.let {
                viewModel.saveLogs(it)
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_root, rootKey)

        val context = requireContext()

        startSyncthingService(SyncthingService.ACTION_RENOTIFY)

        prefs = Preferences(context)

        categoryPermissions = findPreference(Preferences.CATEGORY_PERMISSIONS)!!
        categoryConfiguration = findPreference(Preferences.CATEGORY_CONFIGURATION)!!
        categoryDebug = findPreference(Preferences.CATEGORY_DEBUG)!!

        prefInhibitBatteryOpt = findPreference(Preferences.PREF_INHIBIT_BATTERY_OPT)!!
        prefInhibitBatteryOpt.onPreferenceClickListener = this

        prefAllowNotifications = findPreference(Preferences.PREF_ALLOW_NOTIFICATIONS)!!
        prefAllowNotifications.onPreferenceClickListener = this

        prefLocalStorageAccess = findPreference(Preferences.PREF_LOCAL_STORAGE_ACCESS)!!
        prefLocalStorageAccess.onPreferenceClickListener = this

        prefDisableAppHibernation = findPreference(Preferences.PREF_DISABLE_APP_HIBERNATION)!!
        prefDisableAppHibernation.onPreferenceClickListener = this

        prefOpenWebUi = findPreference(Preferences.PREF_OPEN_WEB_UI)!!
        prefOpenWebUi.onPreferenceClickListener = this

        prefImportConfiguration = findPreference(Preferences.PREF_IMPORT_CONFIGURATION)!!
        prefImportConfiguration.onPreferenceClickListener = this

        prefExportConfiguration = findPreference(Preferences.PREF_EXPORT_CONFIGURATION)!!
        prefExportConfiguration.onPreferenceClickListener = this

        prefServiceStatus = findPreference(Preferences.PREF_SERVICE_STATUS)!!
        prefServiceStatus.onPreferenceChangeListener = this

        prefAutoMode = findPreference(Preferences.PREF_AUTO_MODE)!!
        prefAutoMode.onPreferenceChangeListener = this

        prefRequireSufficientBattery = findPreference(Preferences.PREF_REQUIRE_SUFFICIENT_BATTERY)!!

        prefVersion = findPreference(Preferences.PREF_VERSION)!!
        prefVersion.onPreferenceClickListener = this
        prefVersion.onPreferenceLongClickListener = this

        prefSaveLogs = findPreference(Preferences.PREF_SAVE_LOGS)!!
        prefSaveLogs.onPreferenceClickListener = this

        // Call this once first to avoid UI jank from elements shifting. We call it again in
        // onResume() because allowing the permissions does not restart the activity.
        refreshPermissions()

        refreshBattery()
        refreshVersion()
        refreshDebugPrefs()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.runState.collect {
                    prefOpenWebUi.isEnabled = it == SyncthingService.RunState.RUNNING

                    prefImportConfiguration.isEnabled = it != null
                    prefExportConfiguration.isEnabled = it != null

                    prefServiceStatus.isChecked = it == SyncthingService.RunState.RUNNING
                            || it == SyncthingService.RunState.STARTING
                    prefServiceStatus.summary = when (it) {
                        SyncthingService.RunState.RUNNING ->
                            getString(R.string.notification_persistent_running_title)
                        SyncthingService.RunState.NOT_RUNNING ->
                            getString(R.string.notification_persistent_not_running_title)
                        SyncthingService.RunState.STARTING ->
                            getString(R.string.notification_persistent_starting_title)
                        SyncthingService.RunState.STOPPING ->
                            getString(R.string.notification_persistent_stopping_title)
                        SyncthingService.RunState.IMPORTING ->
                            getString(R.string.notification_persistent_importing_title)
                        SyncthingService.RunState.EXPORTING ->
                            getString(R.string.notification_persistent_exporting_title)
                        null -> null
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.alerts.collect {
                    it.firstOrNull()?.let { alert ->
                        onAlert(alert)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        prefs.registerListener(this)

        refreshService()
        refreshPermissions()
        refreshAppHibernation()
    }

    override fun onPause() {
        super.onPause()

        prefs.unregisterListener(this)
    }

    private fun startSyncthingService(action: String) {
        val context = requireContext()

        context.startForegroundService(SyncthingService.createIntent(context, action))
    }

    private fun refreshPermissions() {
        val context = requireContext()

        val inhibitingBatteryOpt = Permissions.isInhibitingBatteryOpt(context)
        prefInhibitBatteryOpt.isVisible = !inhibitingBatteryOpt

        val allowedNotifications = Permissions.have(context, Permissions.NOTIFICATION)
        prefAllowNotifications.isVisible = !allowedNotifications

        val allowedLocalStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Permissions.have(context, Permissions.LEGACY_STORAGE)
        }
        prefLocalStorageAccess.isVisible = !allowedLocalStorage

        // Hide this while loading to avoid jank in the usual case.
        val disabledAppHibernation = appHibernationEnabled != true
        prefDisableAppHibernation.isVisible = !disabledAppHibernation

        categoryPermissions.isVisible =
            !(inhibitingBatteryOpt && allowedNotifications && allowedLocalStorage && disabledAppHibernation)
    }

    private fun refreshAppHibernation() {
        val context = requireContext()

        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
        future.addListener({
            appHibernationEnabled = when (val status = future.get()) {
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

            if (isResumed) {
                refreshPermissions()
            }
        }, context.mainExecutor)
    }

    private fun refreshService() {
        prefServiceStatus.isEnabled = prefs.isManualMode
        prefAutoMode.isChecked = !prefs.isManualMode
    }

    private fun refreshBattery() {
        val context = requireContext()

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val hasBattery = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true

        prefRequireSufficientBattery.isVisible = hasBattery
    }

    private fun refreshVersion() {
        prefVersion.summary = buildString {
            append(BuildConfig.VERSION_NAME)

            append(" (")
            append(BuildConfig.BUILD_TYPE)
            if (prefs.isDebugMode) {
                append("+debugmode")
            }
            append(")\nsyncthing ")

            append(Stbridge.version())
        }
    }

    private fun refreshDebugPrefs() {
        categoryDebug.isVisible = prefs.isDebugMode
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            prefOpenWebUi -> {
                startActivity(Intent(requireContext(), WebUiActivity::class.java))
                return true
            }
            prefInhibitBatteryOpt -> {
                requestInhibitBatteryOpt.launch(
                    Permissions.getInhibitBatteryOptIntent(requireContext()))
                return true
            }
            prefAllowNotifications -> {
                requestPermissionRequired.launch(Permissions.NOTIFICATION)
                return true
            }
            prefLocalStorageAccess -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        "package:${BuildConfig.APPLICATION_ID}".toUri(),
                    )

                    startActivity(intent)
                } else {
                    requestPermissionRequired.launch(Permissions.LEGACY_STORAGE)
                }

                // We rely on onPause() to adjust the switch state when the user comes back from the
                // settings app.
                return true
            }
            prefDisableAppHibernation -> {
                val context = requireContext()

                requestDisableAppHibernation.launch(
                    IntentCompat.createManageUnusedAppRestrictionsIntent(
                        context,
                        context.packageName,
                    )
                )

                return true
            }
            prefImportConfiguration -> {
                requestSafImportConfiguration.launch(arrayOf(BACKUP_MIMETYPE))
                return true
            }
            prefExportConfiguration -> {
                val timestamp = BACKUP_DATE_FORMATTER.format(ZonedDateTime.now())
                val defaultName = "${getString(R.string.app_name_release)}_$timestamp"

                requestSafExportConfiguration.launch(defaultName)
                return true
            }
            prefVersion -> {
                val uri = BuildConfig.PROJECT_URL_AT_COMMIT.toUri()
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            prefSaveLogs -> {
                requestSafSaveLogs.launch(Logcat.FILENAME_DEFAULT)
                return true
            }
        }

        return false
    }

    override fun onPreferenceLongClick(preference: Preference): Boolean {
        when (preference) {
            prefVersion -> {
                prefs.isDebugMode = !prefs.isDebugMode
                refreshVersion()
                refreshDebugPrefs()
                return true
            }
        }

        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference) {
            prefServiceStatus -> {
                val action = if (newValue == true) {
                    SyncthingService.ACTION_START
                } else {
                    SyncthingService.ACTION_STOP
                }

                startSyncthingService(action)

                // The switch state will update once the state actually changes.
            }
            prefAutoMode -> {
                val action = if (newValue == true) {
                    SyncthingService.ACTION_AUTO_MODE
                } else {
                    SyncthingService.ACTION_MANUAL_MODE
                }

                startSyncthingService(action)

                // The switch state will update once the state actually changes.
            }
        }

        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Preferences.PREF_MANUAL_MODE -> refreshService()
        }
    }

    private fun onAlert(alert: SettingsAlert) {
        val msg = when (alert) {
            SettingsAlert.ImportSucceeded -> getString(R.string.alert_import_success)
            SettingsAlert.ExportSucceeded -> getString(R.string.alert_export_success)
            is SettingsAlert.ImportFailed -> getString(R.string.alert_import_failure)
            is SettingsAlert.ExportFailed -> getString(R.string.alert_export_failure)
            is SettingsAlert.LogcatSucceeded ->
                getString(R.string.alert_logcat_success, alert.uri.formattedString)
            is SettingsAlert.LogcatFailed ->
                getString(R.string.alert_logcat_failure, alert.uri.formattedString)
        }

        val details = when (alert) {
            SettingsAlert.ImportSucceeded -> null
            SettingsAlert.ExportSucceeded -> null
            is SettingsAlert.ImportFailed -> alert.error
            is SettingsAlert.ExportFailed -> alert.error
            is SettingsAlert.LogcatSucceeded -> null
            is SettingsAlert.LogcatFailed -> alert.error
        }

        // Give users a chance to read the message. LENGTH_LONG is only 2750ms.
        Snackbar.make(requireView(), msg, 5000)
            .apply {
                if (details != null) {
                    setAction(R.string.action_details) {
                        MessageDialogFragment.newInstance(
                            getString(R.string.dialog_error_details_title),
                            details,
                        ).show(parentFragmentManager.beginTransaction(), MessageDialogFragment.TAG)
                    }
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_CONSECUTIVE) {
                        viewModel.acknowledgeFirstAlert()
                    }
                }
            })
            .show()
    }
}
