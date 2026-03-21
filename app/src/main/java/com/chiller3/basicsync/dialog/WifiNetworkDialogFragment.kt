/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.dialog

import android.content.Context
import android.os.Bundle
import com.chiller3.basicsync.R

class WifiNetworkDialogFragment : TextInputDialogFragment<String>() {
    companion object {
        val TAG: String = WifiNetworkDialogFragment::class.java.simpleName

        const val RESULT_SUCCESS = TextInputDialogFragment.RESULT_SUCCESS
        const val RESULT_OLD_NAME = RESULT_ORIG_VALUE
        const val RESULT_NAME = "name"

        fun newInstance(context: Context, origName: String?) =
            WifiNetworkDialogFragment().apply {
                arguments = TextInputParams(
                    inputType = TextInputType.NORMAL,
                    title = context.getString(R.string.dialog_wifi_network_title),
                    message = context.getString(R.string.dialog_wifi_network_message),
                    hint = context.getString(R.string.dialog_wifi_network_hint),
                    origValue = origName,
                ).toArgs()
            }
    }

    override fun translateInput(input: String): String? = input.ifEmpty { null }

    override fun updateResult(result: Bundle, value: String?) {
        result.putString(RESULT_NAME, value)
    }
}
