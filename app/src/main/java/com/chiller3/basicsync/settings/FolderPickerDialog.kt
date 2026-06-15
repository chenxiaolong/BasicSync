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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.basicsync.R
import com.chiller3.basicsync.extension.EXTERNAL_DIR
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
    val scopedOwner = rememberViewModelStoreOwner()

    CompositionLocalProvider(LocalViewModelStoreOwner provides scopedOwner) {
        val viewModel: FolderPickerViewModel = viewModel()
        rememberSaveable {
            val initialPath = when (initialLocation) {
                FolderPickerLocation.Default -> EXTERNAL_DIR
                is FolderPickerLocation.Path -> File(initialLocation.path)
            }
            viewModel.navigate(initialPath)
            true
        }

        val state by viewModel.state.collectAsStateWithLifecycle()
        val hasParent = ".." in state.childDirs

        var showNewFolderDialog by rememberSaveable { mutableStateOf(false) }

        AlertDialog(
            title = { Text(text = state.shortCwd.toString()) },
            text = {
                PreferenceColumn(fillScreen = false) {
                    itemsIndexed(state.childDirs, key = { _, c -> c }) { index, childDir ->
                        Preference(
                            onClick = { viewModel.navigate(File(childDir)) },
                            shapes = betterSegmentedShapes(
                                index = index,
                                count = state.childDirs.size,
                            ),
                            title = { Text(text = "$childDir/") },
                        )
                    }
                }

                BackHandler(
                    enabled = hasParent,
                    onBack = { viewModel.navigate(File("..")) },
                )
            },
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { onSelect(state.shortCwd.toString()) }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(onClick = { showNewFolderDialog = true }) {
                    Text(text = stringResource(R.string.dialog_new_folder_title))
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = !hasParent,
                dismissOnClickOutside = false,
            ),
        )

        if (showNewFolderDialog) {
            NewFolderDialog(
                cwd = state.shortCwd.toString(),
                onSelect = { name ->
                    viewModel.mkdir(File(name))
                    showNewFolderDialog = false
                },
                onDismiss = {
                    showNewFolderDialog = false
                },
            )
        }
    }
}
