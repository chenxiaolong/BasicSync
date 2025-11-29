/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import com.chiller3.basicsync.PreferenceBaseActivity
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.R

class SettingsActivity : PreferenceBaseActivity() {
    override val titleResId: Int = R.string.app_name

    override val showUpButton: Boolean = false

    override fun createFragment(): PreferenceBaseFragment = SettingsFragment()
}
