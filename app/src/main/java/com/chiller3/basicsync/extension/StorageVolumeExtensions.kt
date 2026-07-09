/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.extension

import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import android.os.storage.StorageVolume
import java.io.File

val StorageVolume.directoryCompat: File?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        directory
    } else {
        when (state) {
            Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY ->
                @SuppressLint("DiscouragedPrivateApi")
                javaClass.getDeclaredMethod("getPathFile").invoke(this) as File?
            else -> null
        }
    }
