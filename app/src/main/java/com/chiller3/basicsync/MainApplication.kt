/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync

import android.app.Application
import android.util.Log
import com.chiller3.basicsync.binding.stbridge.Stbridge
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

        Preferences(this).migrate()

        Stbridge.initDirs(
            filesDir.toString(),
            cacheDir.toString(),
            getExternalFilesDir(null)!!.toString(),
        )
    }
}
