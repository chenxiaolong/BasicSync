/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.content.Context
import com.chiller3.basicsync.R

class NewFolderDialogFragment : TextInputDialogFragment<String>() {
    companion object {
        val TAG: String = NewFolderDialogFragment::class.java.simpleName

        const val RESULT_SUCCESS = TextInputDialogFragment.RESULT_SUCCESS
        const val RESULT_NAME = RESULT_VALUE

        fun newInstance(context: Context, cwd: String) =
            NewFolderDialogFragment().apply {
                arguments = TextInputParams(
                    inputType = TextInputType.NORMAL,
                    title = context.getString(R.string.dialog_new_folder_title),
                    message = context.getString(R.string.dialog_new_folder_message, cwd),
                    hint = context.getString(R.string.dialog_new_folder_hint),
                ).toArgs()
            }
    }

    override fun translateInput(input: String): String? =
        if (input.isNotEmpty() && !input.contains('/') && input != "." && input != "..") {
            input
        } else {
            null
        }
}
