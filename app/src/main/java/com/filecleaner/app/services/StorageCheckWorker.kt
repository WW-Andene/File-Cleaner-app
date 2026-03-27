package com.filecleaner.app.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.filecleaner.app.MainActivity
import com.filecleaner.app.R
import com.filecleaner.app.data.UserPreferences
import com.filecleaner.app.utils.StorageHistoryManager
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.widget.StorageWidget
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that:
 * 1. Checks storage levels and sends a notification if free space < 10%
 * 2. Records a storage snapshot for trend tracking
 * 3. Refreshes the home screen widget
 *
 * Runs every 24 hours via WorkManager (battery-friendly, survives reboots).
 */
class StorageCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "storage_check"
        private const val CHANNEL_ID = "storage_alerts"
        private const val NOTIFICATION_ID = 2001
        private const val LOW_STORAGE_THRESHOLD = 0.10 // 10%

        /** Schedule the periodic storage check. Call from Application.onCreate(). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StorageCheckWorker>(
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS // flex interval
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancel the periodic work. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // Read storage stats
        val statFs = try {
            StatFs(Environment.getExternalStorageDirectory().absolutePath)
        } catch (_: Exception) {
            return Result.success() // Storage unavailable
        }
        val totalBytes = statFs.totalBytes
        if (totalBytes <= 0) return Result.success() // Guard against division by zero
        val freeBytes = statFs.freeBytes
        val usedBytes = totalBytes - freeBytes
        val freeRatio = freeBytes.toDouble() / totalBytes

        // Record snapshot for trend tracking
        try {
            UserPreferences.init(ctx)
            StorageHistoryManager.recordSnapshot(
                ctx,
                totalFiles = 0, // Not scanning — just recording device-level stats
                totalSize = usedBytes,
                junkSize = 0,
                duplicateSize = 0,
                largeSize = 0
            )
        } catch (_: Exception) { }

        // Refresh widget
        try {
            StorageWidget.refreshAll(ctx)
        } catch (_: Exception) { }

        // Low storage alert
        if (freeRatio < LOW_STORAGE_THRESHOLD) {
            sendLowStorageNotification(ctx, freeBytes, totalBytes)
        }

        return Result.success()
    }

    private fun sendLowStorageNotification(context: Context, freeBytes: Long, totalBytes: Long) {
        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        }

        createNotificationChannel(context)

        val usedPct = ((totalBytes - freeBytes) * 100 / totalBytes).toInt()
        val freeFormatted = UndoHelper.formatBytes(freeBytes)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_storage)
            .setContentTitle(context.getString(R.string.notif_low_storage_title))
            .setContentText(context.getString(R.string.notif_low_storage_body, freeFormatted, usedPct))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_storage),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_storage_desc)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }
}
