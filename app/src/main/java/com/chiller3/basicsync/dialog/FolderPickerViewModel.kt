/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.basicsync.extension.EXTERNAL_DIR
import com.chiller3.basicsync.extension.expandTilde
import com.chiller3.basicsync.extension.shortenTilde
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator

class FolderPickerViewModel : ViewModel() {
    companion object {
        // Same sorting method as AOSP's DocumentsUI.
        private val COLLATOR = Collator.getInstance().apply {
            strength = Collator.SECONDARY
        }
    }

    data class State(
        val cwd: File,
        // Can include "..".
        val childDirs: List<String>,
    ) {
        val shortCwd: File
            get() = cwd.shortenTilde()
    }

    private val _state = MutableStateFlow(State(cwd = EXTERNAL_DIR, childDirs = emptyList()))
    val state = _state.asStateFlow()

    fun navigate(path: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var newCwd = _state.value.cwd.resolve(path.expandTilde()).normalize()

                // Don't allow paths outside of the internal storage. They aren't readable anyway.
                var relPath = newCwd.toRelativeString(EXTERNAL_DIR)
                if (relPath == ".." || relPath.startsWith("../") || !newCwd.isDirectory) {
                    newCwd = EXTERNAL_DIR
                    relPath = ""
                }

                val children = newCwd.listFiles() ?: return@withContext
                children.sortWith(Comparator { a, b -> COLLATOR.compare(a.name, b.name) })

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
