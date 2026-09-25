/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.chiller3.basicsync.R

@Composable
fun ProxyOverrideDialog(
    initialUrl: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val input = rememberTextFieldState(initialText = initialUrl ?: "")
    val url = tryParseInput(input.text.toString())

    AlertDialog(
        title = { Text(text = stringResource(R.string.pref_proxy_override_name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = stringResource(R.string.pref_proxy_override_desc))

                OutlinedTextField(
                    state = input,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(text = stringResource(R.string.dialog_proxy_override_hint)) },
                    placeholder = { Text(text = "{http,https,socks5}://1.2.3.4:5678") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSelect(url!!.ifEmpty { null }) },
                enabled = url != null,
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

private fun tryParseInput(input: String): String? {
    if (input.isEmpty()) {
        return input
    }

    val uri = input.toUri()

    if ((uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "socks5")
            && !uri.authority.isNullOrEmpty()) {
        return input
    }

    return null
}
