/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

abstract class ServiceBaseViewModel(application: Application) : AndroidViewModel(application),
    ServiceConnection, SyncthingService.ServiceListener {
    protected var binder: SyncthingService.ServiceBinder? = null

    private val _runState = MutableStateFlow<SyncthingService.RunState?>(null)
    val runState = _runState.asStateFlow()

    private val _guiInfo = MutableStateFlow<SyncthingService.GuiInfo?>(null)
    val guiInfo = _guiInfo.asStateFlow()

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
    }

    override fun onRunStateChanged(
        state: SyncthingService.RunState,
        guiInfo: SyncthingService.GuiInfo?,
    ) {
        _runState.update { state }
        _guiInfo.update { guiInfo }
    }

    override fun onPreRunActionResult(
        preRunAction: SyncthingService.PreRunAction,
        exception: Exception?,
    ) {}
}
