/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import com.chiller3.basicsync.syncthing.SyncthingService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

abstract class ServiceBaseViewModel(application: Application) : AndroidViewModel(application),
    ServiceConnection, SyncthingService.ServiceListener {
    protected var binder: SyncthingService.ServiceBinder? = null

    private val _exitRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitRequested = _exitRequested.asSharedFlow()

    private val _serviceState = MutableStateFlow<SyncthingService.ServiceState?>(null)
    val serviceState = _serviceState.asStateFlow()

    private val _guiInfo = MutableStateFlow<SyncthingService.GuiInfo?>(null)
    val guiInfo = _guiInfo.asStateFlow()

    private val _conflicts = MutableStateFlow<List<String>?>(null)
    val conflicts = _conflicts.asStateFlow()

    init {
        val context = getApplication<Application>()

        context.bindService(
            SyncthingService.createIntent(context, null),
            this,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onCleared() {
        val context = getApplication<Application>()

        onBinderGone()
        context.unbindService(this)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = (service as SyncthingService.ServiceBinder).also {
            it.registerListener(this)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        onBinderGone()
    }

    private fun onBinderGone() {
        binder?.unregisterListener(this)
        binder = null
        _exitRequested.tryEmit(Unit)
    }

    override fun onExitRequested() {
        onBinderGone()
    }

    override fun onRunStateChanged(
        state: SyncthingService.ServiceState,
        guiInfo: SyncthingService.GuiInfo?,
    ) {
        _serviceState.update { state }
        _guiInfo.update { guiInfo }
    }

    override fun onPreRunActionResult(
        preRunAction: SyncthingService.PreRunAction,
        exception: Exception?,
    ) {}

    override fun onConflictsUpdated(conflicts: List<String>) {
        _conflicts.update { conflicts }
    }
}
