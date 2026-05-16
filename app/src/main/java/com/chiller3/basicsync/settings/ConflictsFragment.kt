/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.preference.Preference
import androidx.preference.get
import androidx.preference.size
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.R
import com.chiller3.basicsync.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.basicsync.extension.EXTERNAL_DIR
import com.chiller3.basicsync.extension.expandTilde
import com.chiller3.basicsync.extension.shortenTilde
import com.chiller3.basicsync.syncthing.SyncthingService
import java.io.File

class ConflictsFragment : PreferenceBaseFragment(), Preference.OnPreferenceClickListener,
    SyncthingService.ServiceListener {
    companion object {
        const val PREFIX = "conflict_"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_conflicts, rootKey)

        lifecycle.addObserver(ServiceEventWatcher(requireContext(), this))
    }

    private fun updateDynamicPrefs(conflicts: List<String>) {
        val context = requireContext()

        for (i in (0 until preferenceScreen.size).reversed()) {
            val p = preferenceScreen[i]

            if (p.key.startsWith(PREFIX)) {
                preferenceScreen.removePreference(p)
            }
        }

        for (conflict in conflicts) {
            val expanded = File(conflict).expandTilde()
            val shortened = expanded.shortenTilde()
            val relPath = expanded.relativeTo(EXTERNAL_DIR)

            val p = Preference(context).apply {
                key = PREFIX + relPath
                isPersistent = false
                title = shortened.toString()
                isIconSpaceReserved = false
                onPreferenceClickListener = this@ConflictsFragment
            }

            preferenceScreen.addPreference(p)
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when {
            preference.key.startsWith(PREFIX) -> {
                val relPath = preference.key.substring(PREFIX.length)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri = DocumentsContract.buildDocumentUri(
                        DOCUMENTSUI_AUTHORITY,
                        "primary:$relPath",
                    )
                    setDataAndType(uri, "vnd.android.document/directory")
                }

                startActivity(intent)

                return true
            }
        }

        return false
    }

    override fun onExitRequested() {
        requireActivity().finishAffinity()
    }

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
            requireActivity().finish()
        } else {
            updateDynamicPrefs(conflicts)
        }
    }
}
