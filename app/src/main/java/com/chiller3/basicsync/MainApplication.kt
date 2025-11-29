/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import java.io.File

class MainApplication : Application() {
    companion object {
        private val TAG = MainApplication::class.java.simpleName
    }

    override fun onCreate() {
        super.onCreate()

        Logcat.init(this)

        val oldCrashHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val logcatFile = File(getExternalFilesDir(null), Logcat.FILENAME_CRASH)
                Log.e(TAG, "Saving logcat to $logcatFile due to uncaught exception in $t", e)
                Logcat.dump(logcatFile)
            } finally {
                oldCrashHandler?.uncaughtException(t, e)
            }
        }

        Notifications(this).updateChannels()

        // Enable Material You colors
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
