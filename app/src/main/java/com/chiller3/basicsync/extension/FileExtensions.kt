/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.extension

import android.annotation.SuppressLint
import android.os.Environment
import java.io.File

private val HOME = File("~")
@SuppressLint("SdCardPath")
private val SDCARD = File("/sdcard")
val EXTERNAL_DIR: File = Environment.getExternalStorageDirectory()

fun File.expandTilde(): File =
    if (startsWith(HOME)) {
        File(EXTERNAL_DIR, toRelativeString(HOME))
    } else if (startsWith(SDCARD)) {
        File(EXTERNAL_DIR, toRelativeString(SDCARD))
    } else {
        this
    }

fun File.shortenTilde(): File {
    val relPath = relativeToOrSelf(EXTERNAL_DIR)
    val relPathString = relPath.toString()

    return if (relPathString.isEmpty()) {
        HOME
    } else if (!relPath.isAbsolute) {
        File(HOME, relPathString)
    } else {
        relPath
    }
}
