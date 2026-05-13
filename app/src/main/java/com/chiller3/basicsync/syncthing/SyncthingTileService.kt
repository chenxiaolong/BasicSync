/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.syncthing

import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R

class SyncthingTileService : TileService(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = SyncthingTileService::class.java.simpleName
    }

    private lateinit var prefs: Preferences

    override fun onCreate() {
        super.onCreate()

        prefs = Preferences(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs.registerListener(this)

        refreshTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        prefs.unregisterListener(this)
    }

    override fun onClick() {
        super.onClick()

        val action = if (!prefs.isManualMode) {
            // Auto mode -> manually started.
            SyncthingService.ACTION_START
        } else if (prefs.manualShouldRun) {
            // Manually started -> manually stopped.
            SyncthingService.ACTION_STOP
        } else {
            // Manually stopped -> auto mode.
            SyncthingService.ACTION_AUTO_MODE
        }

        startForegroundService(SyncthingService.createIntent(this, action))
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        refreshTileState()
    }

    private fun refreshTileState() {
        val tile = qsTile
        if (tile == null) {
            Log.w(TAG, "Tile was null during refreshTileState")
            return
        }

        if (!prefs.isManualMode) {
            // Auto mode.
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.quick_settings_tile_auto_mode)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_notifications)
        } else if (prefs.manualShouldRun) {
            // Manually started.
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.quick_settings_tile_manual_mode_started)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_manually_started)
        } else {
            // Manually stopped.
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.quick_settings_tile_manual_mode_stopped)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_manually_stopped)
        }

        tile.updateTile()
    }
}
