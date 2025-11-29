/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.syncthing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SyncthingBootReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = SyncthingBootReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                context.startForegroundService(SyncthingService.createIntent(context, null))
            else -> Log.w(TAG, "Ignoring unrecognized intent: $intent")
        }
    }
}
