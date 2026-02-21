/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.content.Context
import android.os.Bundle
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R

class MinBatteryLevelDialogFragment : TextInputDialogFragment<Int>() {
    companion object {
        val TAG: String = MinBatteryLevelDialogFragment::class.java.simpleName

        const val RESULT_SUCCESS = TextInputDialogFragment.RESULT_SUCCESS

        fun newInstance(context: Context) =
            MinBatteryLevelDialogFragment().apply {
                arguments = TextInputParams(
                    inputType = TextInputType.NUMBER,
                    title = context.getString(R.string.dialog_min_battery_level_title),
                    message = context.getString(R.string.dialog_min_battery_level_message),
                    hint = context.getString(R.string.dialog_min_battery_level_hint),
                    origValue = Preferences(context).minBatteryLevel.toString(),
                ).toArgs()
            }
    }

    override fun translateInput(input: String): Int? =
        try {
            val level = input.toInt()
            if (level in 0..100) {
                level
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    override fun updateResult(result: Bundle) {
        if (result.getBoolean(RESULT_SUCCESS)) {
            val prefs = Preferences(requireContext())
            prefs.minBatteryLevel = result.getInt(RESULT_VALUE)
        }
    }
}
