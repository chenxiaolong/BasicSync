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
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.databinding.DialogTextInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SyncScheduleDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = SyncScheduleDialogFragment::class.java.simpleName

        const val RESULT_SUCCESS = "success"

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
    }

    private lateinit var prefs: Preferences
    private lateinit var binding: DialogTextInputBinding
    private var success: Boolean = false
    private var cycleMs: Int? = null
    private var syncMs: Int? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        prefs = Preferences(context)

        binding = DialogTextInputBinding.inflate(layoutInflater)
        binding.message.setText(R.string.dialog_sync_schedule_message)

        binding.textLayout.setHint(R.string.dialog_sync_schedule_hint_cycle)
        binding.confirmTextLayout.setHint(R.string.dialog_sync_schedule_hint_sync)
        binding.confirmTextLayout.isVisible = true

        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        binding.text.inputType = inputType
        binding.confirmText.inputType = inputType

        binding.text.addTextChangedListener {
            cycleMs = try {
                humanToMs(it.toString())
            } catch (_: Exception) {
                null
            }

            refreshOkButtonEnabledState()
        }
        binding.confirmText.addTextChangedListener {
            syncMs = try {
                humanToMs(it.toString())
            } catch (_: Exception) {
                null
            }

            refreshOkButtonEnabledState()
        }

        if (savedInstanceState == null) {
            binding.text.setText(msToHuman(prefs.scheduleCycleMs))
            binding.confirmText.setText(msToHuman(prefs.scheduleSyncMs))
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
            prefs.scheduleCycleMs = cycleMs!!
            prefs.scheduleSyncMs = syncMs!!
        }

        setFragmentResult(tag!!, bundleOf(RESULT_SUCCESS to success))
    }

    private fun refreshOkButtonEnabledState() {
        val cycleMs = cycleMs
        val syncMs = syncMs

        (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            cycleMs != null && syncMs != null && syncMs < cycleMs
    }
}
