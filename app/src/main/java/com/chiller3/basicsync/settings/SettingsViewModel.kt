/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.basicsync.Logcat
import com.chiller3.basicsync.binding.stbridge.Stbridge
import com.chiller3.basicsync.extension.toSingleLineString
import com.chiller3.basicsync.syncthing.SyncthingService
import go.error
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportExportMode {
    IMPORT,
    EXPORT,
}

data class ImportExportState(
    val mode: ImportExportMode,
    val uri: Uri,
    val password: SyncthingService.Password,
    val status: Status,
) {
    enum class Status {
        NEED_PASSWORD,
        READY_TO_RUN,
        IN_PROGRESS,
    }
}

class SettingsViewModel : ViewModel() {
    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName

        private fun isBadPasswordError(exception: Exception): Boolean =
            exception is error && exception.error() == Stbridge.zipErrorWrongPassword()
    }

    private val _alerts = MutableStateFlow<List<SettingsAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    val serviceState = MutableStateFlow<SyncthingService.ServiceState?>(null)

    val conflicts = MutableStateFlow<List<String>?>(null)

    private val _importExportState = MutableStateFlow<ImportExportState?>(null)
    val importExportState = _importExportState.asStateFlow()

    fun onPreRunActionResult(
        preRunAction: SyncthingService.PreRunAction,
        exception: Exception?,
    ) {
        val (success, failure) = when (preRunAction) {
            is SyncthingService.PreRunAction.Import ->
                SettingsAlert.ImportSucceeded to SettingsAlert::ImportFailed
            is SyncthingService.PreRunAction.Export ->
                SettingsAlert.ExportSucceeded to SettingsAlert::ExportFailed
        }

        if (exception != null && isBadPasswordError(exception)) {
            Log.w(TAG, "Incorrect password", exception)

            _importExportState.update {
                it!!.copy(status = ImportExportState.Status.NEED_PASSWORD)
            }
        } else {
            val alert = exception?.toSingleLineString()?.let(failure) ?: success

            _alerts.update { it + alert }
            _importExportState.update { null }
        }
    }

    fun startImportExport(mode: ImportExportMode, uri: Uri) {
        if (importExportState.value != null) {
            throw IllegalStateException("Import/export already started")
        }

        // Prompt for password immediately when exporting.
        val status = when (mode) {
            ImportExportMode.IMPORT -> ImportExportState.Status.READY_TO_RUN
            ImportExportMode.EXPORT -> ImportExportState.Status.NEED_PASSWORD
        }

        _importExportState.update {
            ImportExportState(mode, uri, SyncthingService.Password(""), status)
        }
    }

    fun setImportExportPassword(password: SyncthingService.Password) {
        if (importExportState.value == null) {
            throw IllegalStateException("Import/export not started")
        }

        _importExportState.update {
            it!!.copy(
                password = password,
                status = ImportExportState.Status.READY_TO_RUN,
            )
        }
    }

    fun setRunningImportExport() {
        val state = importExportState.value
            ?: throw IllegalStateException("Import/export not started")
        if (state.status != ImportExportState.Status.READY_TO_RUN) {
            throw IllegalStateException("Import/export is not ready to run: ${state.status}")
        }

        _importExportState.update {
            it!!.copy(status = ImportExportState.Status.IN_PROGRESS)
        }
    }

    fun cancelPendingImportExport() {
        val state = importExportState.value
            ?: throw IllegalStateException("Import/export not started")
        if (state.status == ImportExportState.Status.IN_PROGRESS) {
            throw IllegalStateException("Cannot cancel in progress import/export")
        }

        val alert = when (state.mode) {
            ImportExportMode.IMPORT -> SettingsAlert.ImportCancelled
            ImportExportMode.EXPORT -> SettingsAlert.ExportCancelled
        }

        _alerts.update { it + alert }
        _importExportState.update { null }
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    fun saveLogs(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Logcat.dump(uri)
                }
                _alerts.update { it + SettingsAlert.LogcatSucceeded(uri) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dump logs to $uri", e)
                _alerts.update { it + SettingsAlert.LogcatFailed(uri, e.toSingleLineString()) }
            }
        }
    }
}
