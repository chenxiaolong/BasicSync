/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.annotation.SuppressLint
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FolderPickerViewModel : ViewModel() {
    companion object {
        private val HOME = File("~")
        @SuppressLint("SdCardPath")
        private val SDCARD = File("/sdcard")
        val EXTERNAL_DIR: File = Environment.getExternalStorageDirectory()

        private fun expandPath(path: File): File =
            if (path.startsWith(HOME)) {
                File(EXTERNAL_DIR, path.toRelativeString(HOME))
            } else if (path.startsWith(SDCARD)) {
                File(EXTERNAL_DIR, path.toRelativeString(SDCARD))
            } else {
                path
            }

        private fun shortenPath(path: File): File {
            val relPath = path.relativeToOrSelf(EXTERNAL_DIR)
            val relPathString = relPath.toString()

            return if (relPathString.isEmpty()) {
                HOME
            } else if (!relPath.isAbsolute) {
                File(HOME, relPathString)
            } else {
                relPath
            }
        }
    }

    data class State(
        val cwd: File,
        // Can include "..".
        val childDirs: List<String>,
    ) {
        val shortCwd: File
            get() = shortenPath(cwd)
    }

    private val _state = MutableStateFlow(State(cwd = EXTERNAL_DIR, childDirs = emptyList()))
    val state = _state.asStateFlow()

    fun navigate(path: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var newCwd = _state.value.cwd.resolve(expandPath(path)).normalize()

                // Don't allow paths outside of the internal storage. They aren't readable anyway.
                var relPath = newCwd.toRelativeString(EXTERNAL_DIR)
                if (relPath == ".." || relPath.startsWith("../")) {
                    newCwd = EXTERNAL_DIR
                    relPath = ""
                }

                val children = newCwd.listFiles() ?: return@withContext
                children.sort()

                val childDirs = ArrayList<String>().apply {
                    if (relPath.isNotEmpty()) {
                        add("..")
                    }

                    children
                        .asSequence()
                        .filter { it.isDirectory }
                        .map { it.name }
                        .toCollection(this)
                }

                _state.update { State(cwd = newCwd, childDirs = childDirs) }
            }
        }
    }

    fun mkdir(path: File) {
        viewModelScope.launch {
            val cwd = _state.value.cwd

            withContext(Dispatchers.IO) {
                val newPath = cwd.resolve(path).normalize()

                newPath.mkdir()
            }

            navigate(cwd)
        }
    }
}
