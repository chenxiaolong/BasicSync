/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.content.Context
import com.chiller3.basicsync.R

class NewFolderDialogFragment : TextInputDialogFragment() {
    companion object {
        const val RESULT_SUCCESS = TextInputDialogFragment.RESULT_SUCCESS
        const val RESULT_INPUT = TextInputDialogFragment.RESULT_INPUT

        fun newInstance(context: Context, cwd: String) =
            NewFolderDialogFragment().apply {
                arguments = toArgs(
                    context.getString(R.string.dialog_new_folder_title),
                    context.getString(R.string.dialog_new_folder_message, cwd),
                    context.getString(R.string.dialog_new_folder_hint),
                    false,
                )
            }
    }

    override fun isValid(input: String): Boolean =
        input.isNotEmpty() && !input.contains('/') && input != "." && input != ".."
}
