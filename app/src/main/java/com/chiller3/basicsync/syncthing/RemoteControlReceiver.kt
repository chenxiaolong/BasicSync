/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.syncthing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chiller3.basicsync.BuildConfig
import com.chiller3.basicsync.Preferences

class RemoteControlReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = RemoteControlReceiver::class.java.simpleName

        private const val ACTION_AUTO_MODE = "${BuildConfig.APPLICATION_ID}.AUTO_MODE"
        private const val ACTION_MANUAL_MODE = "${BuildConfig.APPLICATION_ID}.MANUAL_MODE"
        private const val ACTION_START = "${BuildConfig.APPLICATION_ID}.START"
        private const val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.STOP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!Preferences(context).remoteControl) {
            Log.w(TAG, "Remote control is not allowed")
            return
        }

        val serviceAction = when (intent?.action) {
            ACTION_AUTO_MODE -> SyncthingService.ACTION_AUTO_MODE
            ACTION_MANUAL_MODE -> SyncthingService.ACTION_MANUAL_MODE
            ACTION_START -> SyncthingService.ACTION_START
            ACTION_STOP -> SyncthingService.ACTION_STOP
            else -> {
                Log.w(TAG, "Ignoring unrecognized intent: $intent")
                return
            }
        }

        context.startForegroundService(SyncthingService.createIntent(context, serviceAction))
    }
}
