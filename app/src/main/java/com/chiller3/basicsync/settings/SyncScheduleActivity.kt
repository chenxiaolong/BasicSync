/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import com.chiller3.basicsync.PreferenceBaseActivity
import com.chiller3.basicsync.PreferenceBaseFragment
import com.chiller3.basicsync.R

class SyncScheduleActivity : PreferenceBaseActivity() {
    override val titleResId: Int = R.string.pref_sync_schedule_settings_name

    override val showUpButton: Boolean = true

    override fun createFragment(): PreferenceBaseFragment = SyncScheduleFragment()
}
