/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.os.Parcelable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chiller3.basicsync.R
import com.chiller3.basicsync.ui.Preference
import com.chiller3.basicsync.ui.PreferenceColumn
import com.chiller3.basicsync.ui.betterSegmentedShapes
import kotlinx.parcelize.Parcelize

@Parcelize
enum class StorageChoice : Parcelable {
    LOCAL,
    SAF,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StorageChoiceDialog(
    onSelect: (StorageChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_storage_type_title)) },
        text = {
            val choices = StorageChoice.entries

            PreferenceColumn(fillScreen = false) {
                itemsIndexed(choices) { index, choice ->
                    val title = when (choice) {
                        StorageChoice.LOCAL ->
                            stringResource(R.string.dialog_storage_type_local_title)
                        StorageChoice.SAF ->
                            stringResource(R.string.dialog_storage_type_saf_title)
                    }
                    val summary = when (choice) {
                        StorageChoice.LOCAL ->
                            stringResource(R.string.dialog_storage_type_local_desc)
                        StorageChoice.SAF ->
                            stringResource(R.string.dialog_storage_type_saf_desc)
                    }

                    Preference(
                        onClick = { onSelect(choice) },
                        shapes = betterSegmentedShapes(index = index, count = choices.size),
                        title = { Text(text = title) },
                        summary = { Text(text = summary) },
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
