/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.get
import androidx.preference.size
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.R
import com.chiller3.basicsync.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.basicsync.extension.EXTERNAL_DIR
import com.chiller3.basicsync.extension.expandTilde
import com.chiller3.basicsync.extension.shortenTilde
import kotlinx.coroutines.launch
import java.io.File

class ConflictsFragment : PreferenceBaseFragment(), Preference.OnPreferenceClickListener {
    companion object {
        const val PREFIX = "conflict_"
    }

    private val viewModel: ConflictsViewModel by viewModels()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_conflicts, rootKey)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.exitRequested.collect {
                    requireActivity().finishAffinity()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.conflicts.collect { conflicts ->
                    if (conflicts == null) {
                        // Still loading.
                        return@collect
                    } else if (conflicts.isEmpty()) {
                        // All conflicts were resolved.
                        requireActivity().finish()
                        return@collect
                    }

                    updateDynamicPrefs(conflicts)
                }
            }
        }
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
}
