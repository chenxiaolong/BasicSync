/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.FileObserver
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.basicsync.extension.directoryCompat
import com.chiller3.basicsync.extension.expandTilde
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator

class FolderPickerViewModel(context: Context, initialPath: File) : ViewModel() {
    companion object {
        private val TAG = FolderPickerViewModel::class.java.simpleName

        // Same sorting method as AOSP's DocumentsUI.
        private val COLLATOR = Collator.getInstance().apply {
            strength = Collator.SECONDARY
        }

        val VIRTUAL_ROOT = File("")

        private val SUPPORTS_INOTIFY = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    sealed interface Child {
        val pinToTop: Boolean
        val title: String
        val summary: String?
        val cdPath: File
    }

    data class Root(
        val isPrimary: Boolean,
        val description: String,
        val uuid: String?,
        val mountPoint: File,
    ) : Child {
        override val pinToTop: Boolean
            get() = isPrimary

        override val title: String
            get() = description

        override val summary: String?
            get() = uuid

        override val cdPath: File
            get() = mountPoint
    }

    data class Directory(val name: String) : Child {
        override val pinToTop: Boolean
            get() = false

        override val title: String
            get() = "$name/"

        override val summary: String?
            get() = null

        override val cdPath: File
            get() = File(name)
    }

    data class State(
        val cwd: File,
        val childDirs: List<Child>,
        val writable: Boolean,
    )

    private val appContext = context.applicationContext
    private val storageManager = appContext.getSystemService(StorageManager::class.java)

    private val _state = MutableStateFlow(State(
        cwd = VIRTUAL_ROOT,
        childDirs = emptyList(),
        writable = false,
    ))
    val state = _state.asStateFlow()

    private val operationLock = Mutex()
    private var roots = emptyList<Root>()
    @RequiresApi(Build.VERSION_CODES.Q)
    private var observer: FileObserver? = null
    private val observerLock = this

    private val rootsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "StorageManager event: ${intent?.action}")

            launchOperation {
                refreshRootsLocked()
                refreshIfCwdLocked(VIRTUAL_ROOT)
            }
        }
    }

    init {
        appContext.registerReceiver(
            rootsReceiver,
            // Keep in sync with android.os.storage.VolumeInfo.sEnvironmentToBroadcast.
            IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_CHECKING)
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_UNMOUNTABLE)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme(ContentResolver.SCHEME_FILE)
            },
        )

        launchOperation {
            refreshRootsLocked()

            if (initialPath == VIRTUAL_ROOT) {
                if (roots.size == 1) {
                    // Navigate to the only usable root initially to avoid an extra tap. The user
                    // can always navigate back if external storage is later attached.
                    navigateLocked(roots[0].mountPoint, File("."))
                    return@launchOperation
                }
            }

            navigateLocked(initialPath.expandTilde(), File("."))
        }
    }

    override fun onCleared() {
        Log.d(TAG, "Cleared")

        appContext.unregisterReceiver(rootsReceiver)

        if (SUPPORTS_INOTIFY) {
            synchronized(observerLock) {
                observer?.stopWatching()
                observer = null
            }
        }
    }

    private fun launchOperation(block: suspend CoroutineScope.() -> Unit): Job {
        return viewModelScope.launch {
            withContext(Dispatchers.IO) {
                operationLock.withLock {
                    block()
                }
            }
        }
    }

    private fun refreshRootsLocked() {
        Log.d(TAG, "Refreshing roots")

        val newRoots = mutableListOf<Root>()

        for (volume in storageManager.storageVolumes) {
            val mountPoint = volume.directoryCompat ?: continue

            newRoots.add(Root(
                isPrimary = volume.isPrimary,
                description = volume.getDescription(appContext),
                uuid = volume.uuid,
                mountPoint = mountPoint,
            ))
        }

        Log.d(TAG, "New roots: $newRoots")
        roots = newRoots
    }

    private fun canBrowseLocked(file: File) = roots.any { file.startsWith(it.mountPoint) }

    private fun navigateLocked(cwd: File, path: File) {
        var newCwd = VIRTUAL_ROOT
        if (path != VIRTUAL_ROOT) {
            cwd.resolve(path)
                .normalize()
                .takeIf { canBrowseLocked(it) && it.isDirectory }
                ?.let { newCwd = it }
        }
        Log.d(TAG, "Changing cwd: $cwd + $path = $newCwd")

        val childDirs = if (newCwd == VIRTUAL_ROOT) {
            roots.toMutableList()
        } else {
            mutableListOf<Directory>().apply {
                add(Directory(".."))

                (newCwd.listFiles() ?: emptyArray())
                    .asSequence()
                    .filter { it.isDirectory }
                    .map { Directory(it.name) }
                    .toCollection(this)
            }
        }

        childDirs.sortWith { a, b ->
            compareValuesBy(
                a,
                b,
                { !it.pinToTop },
                { COLLATOR.getCollationKey(it.title) },
                { COLLATOR.getCollationKey(it.summary) },
            )
        }

        val writable = if (newCwd == VIRTUAL_ROOT) {
            false
        } else {
            try {
                Os.access(newCwd.path, OsConstants.W_OK)
            } catch (_: ErrnoException) {
                false
            }
        }

        if (SUPPORTS_INOTIFY && newCwd != cwd) {
            synchronized(observerLock) {
                observer?.stopWatching()

                if (newCwd == VIRTUAL_ROOT) {
                    observer = null
                } else {
                    Log.d(TAG, "Watching: $newCwd")
                    observer = DirectoryObserver(newCwd, this).apply {
                        startWatching()
                    }
                }
            }
        }

        _state.update {
            State(
                cwd = newCwd,
                childDirs = childDirs,
                writable = writable,
            )
        }
    }

    private fun refreshIfCwdLocked(expectedCwd: File) {
        val cwd = _state.value.cwd
        if (cwd == expectedCwd) {
            navigateLocked(cwd, File("."))
        } else {
            Log.d(TAG, "Skipping refresh because of current cwd: $cwd != $expectedCwd")
        }
    }

    fun navigate(cwd: File, path: File) {
        Log.d(TAG, "Navigating to: $path")

        launchOperation {
            navigateLocked(cwd, path)
        }
    }

    fun mkdir(cwd: File, name: String) {
        Log.d(TAG, "Creating in: $cwd: $name")

        if (!isSafeName(name)) {
            throw IllegalArgumentException("Unsafe name: $name")
        }

        launchOperation {
            if (!cwd.resolve(name).mkdir()) {
                Log.w(TAG, "Failed to create in: $cwd: $name")
            }

            refreshIfCwdLocked(cwd)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class DirectoryObserver(
        private val directory: File,
        private val viewModel: FolderPickerViewModel,
    ) : FileObserver(directory, EVENTS) {
        companion object {
            private const val EVENTS = CLOSE_WRITE or MOVED_FROM or MOVED_TO or DELETE or CREATE or
                    DELETE_SELF or MOVE_SELF
        }

        override fun onEvent(event: Int, path: String?) {
            // The kernel can send unwanted events.
            if (event and EVENTS != 0) {
                Log.d(TAG, "inotify event $event: $path")

                viewModel.launchOperation {
                    viewModel.refreshIfCwdLocked(directory)
                }
            }
        }
    }
}
