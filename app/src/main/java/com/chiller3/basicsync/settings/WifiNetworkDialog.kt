/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chiller3.basicsync.R
import kotlinx.parcelize.Parcelize

@Parcelize
sealed interface WifiNetworkDialogAction : Parcelable {
    @Parcelize
    data object Add : WifiNetworkDialogAction

    @Parcelize
    data class Edit(val name: String) : WifiNetworkDialogAction
}

@Composable
fun WifiNetworkDialog(
    action: WifiNetworkDialogAction,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialText = remember {
        when (action) {
            WifiNetworkDialogAction.Add -> ""
            is WifiNetworkDialogAction.Edit -> action.name
        }
    }
    val input = rememberTextFieldState(initialText = initialText)
    val name = input.text.toString().ifEmpty { null }

    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_wifi_network_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = stringResource(R.string.dialog_wifi_network_message))

                OutlinedTextField(
                    state = input,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(text = stringResource(R.string.dialog_wifi_network_hint)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSelect(name!!) },
                enabled = name != null,
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}
