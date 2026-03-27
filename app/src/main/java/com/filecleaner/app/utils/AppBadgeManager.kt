package com.filecleaner.app.utils

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.filecleaner.app.R

/**
 * Manages the app launcher icon badge showing the count of
 * actionable items (junk files + duplicates) found in the last scan.
 *
 * Uses a silent notification with badge count on Android 8+.
 * The notification itself is low-priority and auto-dismissed.
 */
object AppBadgeManager {

    private const val CHANNEL_ID = "badge_channel"
    private const val NOTIFICATION_ID = 3001

    /**
     * Updates the app icon badge with the total count of actionable items.
     * Pass 0 to clear the badge.
     */
    fun updateBadge(context: Context, junkCount: Int, duplicateCount: Int) {
        val total = junkCount + duplicateCount
        if (total <= 0) {
            clearBadge(context)
            return
        }

        createChannel(context)

        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.badge_title))
            .setContentText(context.getString(R.string.badge_message, total))
            .setNumber(total)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun clearBadge(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.badge_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.badge_channel_desc)
            setShowBadge(true)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }
}
