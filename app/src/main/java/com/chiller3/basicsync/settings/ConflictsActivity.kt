/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import com.chiller3.basicsync.BaseActivity
import com.chiller3.basicsync.ui.theme.AppTheme

class ConflictsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                ConflictsScreen(
                    onBack = ::finish,
                    onExit = ::finishAffinity,
                )
            }
        }
    }
}
