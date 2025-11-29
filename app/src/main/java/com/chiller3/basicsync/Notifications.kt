/*
 * SPDX-FileCopyrightText: 2022-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.chiller3.basicsync.extension.toSingleLineString
import com.chiller3.basicsync.settings.SettingsActivity
import com.chiller3.basicsync.settings.WebUiActivity
import com.chiller3.basicsync.syncthing.SyncthingService

class Notifications(private val context: Context) {
    companion object {
        private const val CHANNEL_ID_PERSISTENT = "persistent"
        private const val CHANNEL_ID_FAILURE = "failure"

        private val LEGACY_CHANNEL_IDS = arrayOf<String>()

        const val ID_PERSISTENT = -1
        private const val ID_FAILURE = -2
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** Create a low priority notification channel for the persistent notification. */
    private fun createPersistentChannel() = NotificationChannel(
        CHANNEL_ID_PERSISTENT,
        context.getString(R.string.notification_channel_persistent_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_persistent_desc)
    }

    private fun createFailureAlertsChannel() = NotificationChannel(
        CHANNEL_ID_FAILURE,
        context.getString(R.string.notification_channel_failure_name),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_failure_desc)
    }

    /**
     * Ensure notification channels are up-to-date.
     *
     * Legacy notification channels are deleted without migrating settings.
     */
    fun updateChannels() {
        notificationManager.createNotificationChannels(listOf(
            createPersistentChannel(),
            createFailureAlertsChannel(),
        ))
        LEGACY_CHANNEL_IDS.forEach { notificationManager.deleteNotificationChannel(it) }
    }

    fun createKeepAliveNotification(state: SyncthingService.ServiceState): Notification {
        val titleResId = when (state.runState) {
            SyncthingService.RunState.RUNNING -> R.string.notification_persistent_running_title
            SyncthingService.RunState.NOT_RUNNING -> R.string.notification_persistent_not_running_title
            SyncthingService.RunState.STARTING -> R.string.notification_persistent_starting_title
            SyncthingService.RunState.STOPPING -> R.string.notification_persistent_stopping_title
            SyncthingService.RunState.IMPORTING -> R.string.notification_persistent_importing_title
            SyncthingService.RunState.EXPORTING -> R.string.notification_persistent_exporting_title
        }

        return Notification.Builder(context, CHANNEL_ID_PERSISTENT).run {
            setContentTitle(context.getString(titleResId))
            setSmallIcon(R.drawable.ic_notifications)
            setOngoing(true)
            setOnlyAlertOnce(true)

            for (action in state.actions) {
                val actionTextResId = when (action) {
                    SyncthingService.ACTION_AUTO_MODE -> R.string.notification_action_auto_mode
                    SyncthingService.ACTION_MANUAL_MODE -> R.string.notification_action_manual_mode
                    SyncthingService.ACTION_START -> R.string.notification_action_start
                    SyncthingService.ACTION_STOP -> R.string.notification_action_stop
                    else -> throw IllegalArgumentException("Invalid action: $action")
                }

                val actionPendingIntent = PendingIntent.getService(
                    context,
                    0,
                    SyncthingService.createIntent(context, action),
                    PendingIntent.FLAG_IMMUTABLE or
                            PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_ONE_SHOT,
                )

                addAction(Notification.Action.Builder(
                    null,
                    context.getString(actionTextResId),
                    actionPendingIntent,
                ).build())
            }

            val primaryIntent = if (state.runState == SyncthingService.RunState.RUNNING) {
                Intent(context, WebUiActivity::class.java)
            } else {
                Intent(context, SettingsActivity::class.java)
            }
            val primaryPendingIntent = PendingIntent.getActivity(
                context,
                0,
                primaryIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            setContentIntent(primaryPendingIntent)

            // Inhibit 10-second delay when showing persistent notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            build()
        }
    }

    fun sendFailureNotification(exception: Exception) {
        val notification = Notification.Builder(context, CHANNEL_ID_FAILURE).run {
            val text = buildString {
                append(context.getString(R.string.notification_failure_run_message))

                val errorMsg = exception.toSingleLineString().trim()
                if (errorMsg.isNotEmpty()) {
                    append("\n\n")
                    append(errorMsg)
                }
            }

            setContentTitle(context.getString(R.string.notification_failure_run_title))
            if (text.isNotBlank()) {
                setContentText(text)
                style = Notification.BigTextStyle()
            }
            setSmallIcon(R.drawable.ic_notifications)

            build()
        }

        notificationManager.notify(ID_FAILURE, notification)
    }
}
