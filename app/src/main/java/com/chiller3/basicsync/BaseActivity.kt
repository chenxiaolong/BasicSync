/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.chiller3.basicsync.syncthing.SyncthingService

abstract class BaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the service no matter which activity we're in. If the user disables a permission
        // and then returns to the app, they could be on any settings activity.
        SyncthingService.start(this, SyncthingService.ACTION_RENOTIFY)
    }
}
