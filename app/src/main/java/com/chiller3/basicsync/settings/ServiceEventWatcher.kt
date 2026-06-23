/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chiller3.basicsync.syncthing.SyncthingService

@Composable
fun rememberServiceEventWatcher(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    listener: SyncthingService.ServiceListener,
): ServiceEventWatcher {
    val context = LocalContext.current
    val watcher = remember(lifecycleOwner) { ServiceEventWatcher(listener) }

    DisposableEffect(lifecycleOwner) {
        watcher.startWatching(context)

        onDispose {
            watcher.stopWatching(context)
        }
    }

    return watcher
}

class ServiceEventWatcher internal constructor(
    private val listener: SyncthingService.ServiceListener,
) : ServiceConnection, SyncthingService.ServiceListener {
    private var binder: SyncthingService.ServiceBinder? = null
    private val handler = Handler(Looper.getMainLooper())
    private val onConnect = ArrayDeque<(SyncthingService.ServiceBinder) -> Unit>()

    internal fun startWatching(context: Context) {
        context.bindService(
            SyncthingService.createIntent(context, null),
            this,
            Context.BIND_AUTO_CREATE,
        )
    }

    internal fun stopWatching(context: Context) {
        onBinderGone()
        context.unbindService(this)

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

    override fun onMissingStoragePermissions(internal: Boolean, external: List<Uri>) {
        handler.post { listener.onMissingStoragePermissions(internal, external) }
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
