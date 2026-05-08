/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.databinding.DialogTextInputBinding
import com.chiller3.basicsync.dialog.SyncScheduleDialogFragment.Companion.humanToMs
import com.chiller3.basicsync.dialog.SyncScheduleDialogFragment.Companion.msToHuman
import com.chiller3.basicsync.syncthing.DeviceStateTracker
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SyncScheduleIdleDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = SyncScheduleIdleDialogFragment::class.java.simpleName

        const val RESULT_SUCCESS = "success"
    }

    private lateinit var prefs: Preferences
    private lateinit var binding: DialogTextInputBinding
    private var success: Boolean = false
    private var idleMs: Int? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        prefs = Preferences(context)

        binding = DialogTextInputBinding.inflate(layoutInflater)
        binding.message.setText(R.string.dialog_sync_schedule_message)

        binding.textLayout.setHint(R.string.dialog_sync_schedule_hint_idle)
        binding.confirmTextLayout.isVisible = false

        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        binding.text.inputType = inputType

        binding.text.addTextChangedListener {
            idleMs = try {
                humanToMs(it.toString())
            } catch (_: Exception) {
                null
            }

            refreshOkButtonEnabledState()
        }

        if (savedInstanceState == null) {
            binding.text.setText(msToHuman(prefs.scheduleIdleMs))
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_sync_schedule_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                success = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        refreshOkButtonEnabledState()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        if (success) {
            prefs.scheduleIdleMs = idleMs!!
            prefs.clampSyncScheduleDurations(false)
        }

        setFragmentResult(tag!!, Bundle().apply { putBoolean(RESULT_SUCCESS, success) })
    }

    private fun refreshOkButtonEnabledState() {
        val idleMs = idleMs

        (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            idleMs != null && idleMs >= DeviceStateTracker.MINIMUM_IDLE_MS
    }
}
