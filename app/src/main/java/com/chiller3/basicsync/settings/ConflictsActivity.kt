/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import com.chiller3.basicsync.PreferenceBaseActivity
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.R

class ConflictsActivity : PreferenceBaseActivity() {
    override val titleResId: Int = R.string.pref_conflicts_name

    override val showUpButton: Boolean = true

    override fun createFragment(): PreferenceBaseFragment = ConflictsFragment()
}
