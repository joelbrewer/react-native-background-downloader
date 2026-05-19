package com.eko.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/**
 * Owns the single foreground-service notification used by the upload queue.
 *
 * Because uploads are serialized through a single WorkManager unique chain,
 * at most one worker is foreground at any moment. We use one stable
 * notification ID — the active worker updates it in place; when the queue
 * drains the notification disappears with the worker.
 */
object UploadNotificationManager {

    @Volatile
    var showNotificationsEnabled: Boolean = false

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val visible = NotificationChannel(
            UploadConstants.NOTIFICATION_CHANNEL_VISIBLE,
            "Background Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress for background uploads"
            setShowBadge(false)
        }
        nm.createNotificationChannel(visible)

        val silent = NotificationChannel(
            UploadConstants.NOTIFICATION_CHANNEL_SILENT,
            "Background Uploads (Silent)",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Silent notifications for background uploads"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(silent)
    }

    fun buildNotification(
        context: Context,
        title: String,
        contentText: String,
        progress: Int,
        indeterminate: Boolean
    ): Notification {
        val channelId = if (showNotificationsEnabled)
            UploadConstants.NOTIFICATION_CHANNEL_VISIBLE
        else
            UploadConstants.NOTIFICATION_CHANNEL_SILENT

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .setPriority(if (showNotificationsEnabled) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun buildForegroundInfo(
        context: Context,
        title: String,
        contentText: String,
        progress: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val notification = buildNotification(context, title, contentText, progress, indeterminate)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UploadConstants.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(UploadConstants.NOTIFICATION_ID, notification)
        }
    }
}
