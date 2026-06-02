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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chiller3.basicsync.R
import com.chiller3.basicsync.syncthing.DeviceStateTracker

private fun msToHuman(duration: Int): String =
    if (duration % (60 * 60 * 1000) == 0) {
        val hours = duration / 60 / 60 / 1000
        "${hours}h"
    } else if (duration % (60 * 1000) == 0) {
        val minutes = duration / 60 / 1000
        "${minutes}m"
    } else {
        val seconds = duration / 1000
        "${seconds}s"
    }

private fun humanToMs(input: String): Int {
    val multiplier = when (input.last()) {
        'h' -> 60 * 60 * 1000
        'm' -> 60 * 1000
        's' -> 1000
        else -> throw IllegalArgumentException("Unrecognized suffix in: $input")
    }

    val base = input.substring(0, input.length - 1).toInt()
    if (base <= 0) {
        throw IllegalArgumentException("Base value must be positive: $base")
    } else if (base > Int.MAX_VALUE / multiplier) {
        throw IllegalArgumentException("Duration too large as ms: $base * $multiplier")
    }

    return base * multiplier
}

@Composable
fun SyncScheduleDialog(
    initialCycleMs: Int,
    initialSyncMs: Int,
    onSelect: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialTextCycle = remember { msToHuman(initialCycleMs) }
    val initialTextSync = remember { msToHuman(initialSyncMs) }
    val inputCycle = rememberTextFieldState(initialText = initialTextCycle)
    val inputSync = rememberTextFieldState(initialText = initialTextSync)
    val cycleMs = tryParseInput(inputCycle.text.toString())
    val syncMs = tryParseInput(inputSync.text.toString())

    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_sync_schedule_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = stringResource(R.string.dialog_sync_schedule_message))

                OutlinedTextField(
                    state = inputCycle,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(text = stringResource(R.string.dialog_sync_schedule_hint_cycle)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )

                OutlinedTextField(
                    state = inputSync,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(text = stringResource(R.string.dialog_sync_schedule_hint_sync)) },
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
                onClick = { onSelect(cycleMs!!, syncMs!!) },
                enabled = isCycleSyncValid(cycleMs, syncMs),
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

@Composable
fun SyncScheduleIdleDialog(
    initialIdleMs: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialText = remember { msToHuman(initialIdleMs) }
    val input = rememberTextFieldState(initialText = initialText)
    val idleMs = tryParseInput(input.text.toString())

    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_sync_schedule_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = stringResource(R.string.dialog_sync_schedule_message))

                OutlinedTextField(
                    state = input,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(text = stringResource(R.string.dialog_sync_schedule_hint_idle)) },
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
                onClick = { onSelect(idleMs!!) },
                enabled = isIdleValid(idleMs),
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

@Composable
private fun tryParseInput(input: String) = try {
    humanToMs(input)
} catch (_: Exception) {
    null
}

private fun isCycleSyncValid(cycleMs: Int?, syncMs: Int?) =
    cycleMs != null && syncMs != null && syncMs < cycleMs
            && syncMs >= DeviceStateTracker.MINIMUM_SYNC_MS
            && cycleMs >= DeviceStateTracker.MINIMUM_CYCLE_MS

private fun isIdleValid(idleMs: Int?) =
    idleMs != null && idleMs >= DeviceStateTracker.MINIMUM_IDLE_MS
