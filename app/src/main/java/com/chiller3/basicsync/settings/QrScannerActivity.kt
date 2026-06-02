/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.chiller3.basicsync.BaseActivity
import com.chiller3.basicsync.ui.theme.AppTheme

class QrScannerActivity : BaseActivity() {
    companion object {
        const val EXTRA_DATA = "data"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                QrScannerScreen(
                    onScan = { code ->
                        setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_DATA, code) })
                        finish()
                    },
                    onBack = ::finish,
                )
            }
        }
    }
}
