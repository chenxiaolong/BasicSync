/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.chiller3.basicsync.syncthing.SyncthingService

class ServiceEventWatcher(
    context: Context,
    private val listener: SyncthingService.ServiceListener,
) : DefaultLifecycleObserver,
    ServiceConnection, SyncthingService.ServiceListener {
    private val appContext = context.applicationContext
    private var binder: SyncthingService.ServiceBinder? = null
    private val handler = Handler(Looper.getMainLooper())
    private val onConnect = ArrayDeque<(SyncthingService.ServiceBinder) -> Unit>()

    override fun onStart(owner: LifecycleOwner) {
        appContext.bindService(
            SyncthingService.createIntent(appContext, null),
            this,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        onBinderGone()
        appContext.unbindService(this)

        handler.removeCallbacksAndMessages(null)
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val serviceBinder = (service as SyncthingService.ServiceBinder)
        serviceBinder.registerListener(this)
        binder = serviceBinder

        val iter = onConnect.iterator()
        while (iter.hasNext()) {
            val block = iter.next()
            iter.remove()

            block(serviceBinder)
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        onBinderGone()
        listener.onExitRequested()
    }

    private fun onBinderGone() {
        binder?.unregisterListener(this)
        binder = null
    }

    override fun onExitRequested() {
        handler.post { listener.onExitRequested() }
    }

    override fun onRunStateChanged(
        state: SyncthingService.ServiceState,
        guiInfo: SyncthingService.GuiInfo?,
    ) {
        handler.post { listener.onRunStateChanged(state, guiInfo) }
    }

    override fun onPreRunActionResult(
        preRunAction: SyncthingService.PreRunAction,
        exception: Exception?,
    ) {
        handler.post { listener.onPreRunActionResult(preRunAction, exception) }
    }

    override fun onConflictsUpdated(conflicts: List<String>) {
        handler.post { listener.onConflictsUpdated(conflicts) }
    }

    fun runWithBinder(block: (SyncthingService.ServiceBinder) -> Unit) {
        val binder = binder

        if (binder != null) {
            block(binder)
        } else {
            onConnect.add(block)
        }
    }
}
