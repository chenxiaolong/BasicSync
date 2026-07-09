/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.basicsync.R
import com.chiller3.basicsync.extension.shortenTilde
import com.chiller3.basicsync.ui.Preference
import com.chiller3.basicsync.ui.PreferenceColumn
import com.chiller3.basicsync.ui.betterSegmentedShapes
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
sealed interface FolderPickerLocation : Parcelable {
    @Parcelize
    data object Default : FolderPickerLocation

    @Parcelize
    data class Path(val path: String) : FolderPickerLocation
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderPickerDialog(
    initialLocation: FolderPickerLocation,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scopedOwner = rememberViewModelStoreOwner()

    CompositionLocalProvider(LocalViewModelStoreOwner provides scopedOwner) {
        val viewModel = viewModel {
            val initialPath = when (initialLocation) {
                FolderPickerLocation.Default -> FolderPickerViewModel.VIRTUAL_ROOT
                is FolderPickerLocation.Path -> File(initialLocation.path)
            }

            FolderPickerViewModel(context, initialPath)
        }

        val state by viewModel.state.collectAsStateWithLifecycle()
        val isRoot = state.cwd == FolderPickerViewModel.VIRTUAL_ROOT
        val shortCwd = state.cwd.shortenTilde().toString()

        var showNewFolderDialog by rememberSaveable { mutableStateOf(false) }

        AlertDialog(
            title = {
                if (!isRoot) {
                    Text(text = shortCwd)
                }
            },
            text = {
                PreferenceColumn(fillScreen = false) {
                    itemsIndexed(state.childDirs, key = { _, c -> c.cdPath }) { index, childDir ->
                        Preference(
                            onClick = { viewModel.navigate(state.cwd, childDir.cdPath) },
                            shapes = betterSegmentedShapes(
                                index = index,
                                count = state.childDirs.size,
                            ),
                            enabled = childDir.enabled,
                            title = { Text(text = childDir.title) },
                            summary = childDir.summary?.let { { Text(text = it) } },
                            // This is uglier, but having the fade out animation delays the
                            // shrinking of the dialog window noticeably when navigating to a
                            // smaller directory.
                            modifier = Modifier.animateItem(fadeOutSpec = null),
                        )
                    }
                }

                BackHandler(
                    enabled = !isRoot,
                    onBack = { viewModel.navigate(state.cwd, File("..")) },
                )
            },
            onDismissRequest = onDismiss,
            confirmButton = {
                if (!isRoot) {
                    TextButton(onClick = { onSelect(shortCwd) }) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                if (!isRoot) {
                    TextButton(onClick = { showNewFolderDialog = true }) {
                        Text(text = stringResource(R.string.dialog_new_folder_title))
                    }
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = isRoot,
                dismissOnClickOutside = false,
            ),
        )

        if (showNewFolderDialog) {
            NewFolderDialog(
                cwd = state.cwd,
                onSelect = { cwd, name ->
                    viewModel.mkdir(cwd, name)
                    showNewFolderDialog = false
                },
                onDismiss = {
                    showNewFolderDialog = false
                },
            )
        }
    }
}
