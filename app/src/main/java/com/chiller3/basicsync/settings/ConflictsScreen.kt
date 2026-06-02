/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.chiller3.basicsync.R
import com.chiller3.basicsync.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.basicsync.extension.EXTERNAL_DIR
import com.chiller3.basicsync.extension.expandTilde
import com.chiller3.basicsync.extension.shortenTilde
import com.chiller3.basicsync.syncthing.SyncthingService
import com.chiller3.basicsync.ui.AppScreen
import com.chiller3.basicsync.ui.BetterSegmentedShapes
import com.chiller3.basicsync.ui.Preference
import com.chiller3.basicsync.ui.PreferenceColumn
import com.chiller3.basicsync.ui.PreferenceDefaults
import com.chiller3.basicsync.ui.betterSegmentedShapes
import com.chiller3.basicsync.ui.theme.AppTheme
import java.io.File

@Composable
fun ConflictsScreen(
    onBack: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current

    var syncConflicts by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showDocumentsUIAlert by rememberSaveable { mutableStateOf(false) }

    rememberServiceEventWatcher(
        listener = object : SyncthingService.ServiceListener {
            override fun onExitRequested() = onExit()

            override fun onRunStateChanged(
                state: SyncthingService.ServiceState,
                guiInfo: SyncthingService.GuiInfo?,
            ) {}

            override fun onPreRunActionResult(
                preRunAction: SyncthingService.PreRunAction,
                exception: Exception?,
            ) {}

            override fun onConflictsUpdated(conflicts: List<String>) {
                if (conflicts.isEmpty()) {
                    onExit()
                } else {
                    syncConflicts = conflicts
                }
            }
        },
    )

    AppScreen(
        title = { Text(text = stringResource(R.string.pref_conflicts_name)) },
        onBack = onBack,
    ) { params ->
        ConflictsContent(
            conflicts = syncConflicts,
            onConflictOpen = { relPath ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        val uri = DocumentsContract.buildDocumentUri(
                            DOCUMENTSUI_AUTHORITY,
                            "primary:$relPath",
                        )
                        setDataAndType(uri, "vnd.android.document/directory")
                    }

                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    showDocumentsUIAlert = true
                }
            },
            contentPadding = params.contentPadding,
        )

        if (showDocumentsUIAlert) {
            val message = stringResource(R.string.alert_documentsui_not_found)

            LaunchedEffect(Unit) {
                params.snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = true,
                )
                showDocumentsUIAlert = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConflictsContent(
    conflicts: List<String>,
    onConflictOpen: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    PreferenceColumn(contentPadding = contentPadding) {
        item(key = "info_conflicts") {
            Preference(
                onClick = {},
                shapes = BetterSegmentedShapes.single(),
                title = {},
                summary = { Text(text = stringResource(R.string.pref_conflicts_info)) },
                colors = PreferenceDefaults.preferenceInfoColors(),
                modifier = Modifier.animateItem(),
            )
        }

        itemsIndexed(conflicts, key = { _, n -> "conflict_$n" }) { index, path ->
            val expanded: File
            val shortened: File
            val relPath: File

            if (LocalInspectionMode.current) {
                // EXTERNAL_DIR fails with NPE in compose previewer.
                val externalDir = "/storage/emulated/0"
                expanded = File(path.replaceFirst("~", externalDir))
                shortened = File(path.replaceFirst(externalDir, "~"))
                relPath = expanded.relativeTo(File(externalDir))
            } else {
                expanded = File(path).expandTilde()
                shortened = expanded.shortenTilde()
                relPath = expanded.relativeTo(EXTERNAL_DIR)
            }

            Preference(
                onClick = { onConflictOpen(relPath.toString()) },
                shapes = betterSegmentedShapes(index, conflicts.size),
                title = { Text(text = shortened.toString()) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewConflictsScreen() {
    val conflicts = listOf(
        "/storage/emulated/0/DCIM/Camera/picture.sync-conflict-20260101-000000-ABCDEFG.jpg",
        "/storage/emulated/0/Sync/test.sync-conflict-20260101-000000-ABCDEFG.txt",
    )

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.pref_conflicts_name)) },
            onBack = {},
        ) { params ->
            ConflictsContent(
                conflicts = conflicts,
                onConflictOpen = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
