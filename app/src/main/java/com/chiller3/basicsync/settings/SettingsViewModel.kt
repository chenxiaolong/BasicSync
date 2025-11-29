/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.chiller3.basicsync.Logcat
import com.chiller3.basicsync.extension.toSingleLineString
import com.chiller3.basicsync.syncthing.SyncthingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : ServiceBaseViewModel(application) {
    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName
    }

    private val _alerts = MutableStateFlow<List<SettingsAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    override fun onPreRunActionResult(
        preRunAction: SyncthingService.PreRunAction,
        exception: Exception?,
    ) {
        val (success, failure) = when (preRunAction) {
            is SyncthingService.PreRunAction.Import ->
                SettingsAlert.ImportSucceeded to SettingsAlert::ImportFailed
            is SyncthingService.PreRunAction.Export ->
                SettingsAlert.ExportSucceeded to SettingsAlert::ExportFailed
        }

        val alert = exception?.toSingleLineString()?.let(failure) ?: success

        _alerts.update { it + alert }
    }

    fun importConfiguration(uri: Uri) {
        binder!!.importConfiguration(uri)
    }

    fun exportConfiguration(uri: Uri) {
        binder!!.exportConfiguration(uri)
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
