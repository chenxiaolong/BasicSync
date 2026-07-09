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
private val EXTERNAL_DIR: File = Environment.getExternalStorageDirectory()

fun File.expandTilde(): File =
    if (startsWith(HOME)) {
        File(EXTERNAL_DIR, toRelativeString(HOME))
    } else if (startsWith(SDCARD)) {
        File(EXTERNAL_DIR, toRelativeString(SDCARD))
    } else {
        this
    }

fun File.shortenTilde(): File =
    if (startsWith(EXTERNAL_DIR)) {
        File(HOME, toRelativeString(EXTERNAL_DIR))
    } else {
        this
    }
