package com.filecleaner.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.widget.RemoteViews
import com.filecleaner.app.MainActivity
import com.filecleaner.app.R
import com.filecleaner.app.utils.UndoHelper

/**
 * Home screen widget showing storage usage summary with a quick-clean action.
 *
 * Displays:
 * - Used / Total storage with percentage
 * - Progress bar visualization
 * - "Clean Now" button that opens the app
 *
 * Updates automatically on system broadcasts and when the app refreshes data.
 */
class StorageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) {
            updateWidget(context, manager, id)
        }
    }

    companion object {
        /** Call from anywhere to refresh all widget instances. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StorageWidget::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        @Synchronized
        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_storage)

            // Read storage stats
            val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val totalBytes = statFs.totalBytes
            val freeBytes = statFs.freeBytes
            val usedBytes = totalBytes - freeBytes
            val usedPct = if (totalBytes > 0) ((usedBytes * 100.0) / totalBytes).toInt() else 0

            views.setTextViewText(R.id.tv_widget_used,
                context.getString(R.string.widget_storage_used, UndoHelper.formatBytes(usedBytes)))
            views.setTextViewText(R.id.tv_widget_total,
                context.getString(R.string.widget_storage_total, UndoHelper.formatBytes(totalBytes)))
            views.setTextViewText(R.id.tv_widget_free,
                context.getString(R.string.widget_storage_free, UndoHelper.formatBytes(freeBytes)))
            views.setTextViewText(R.id.tv_widget_percent, "$usedPct%")
            views.setProgressBar(R.id.progress_widget, 100, usedPct, false)

            // Warning color when storage is low
            val pctColor = if (usedPct > 90) {
                context.getColor(R.color.colorError)
            } else if (usedPct > 75) {
                context.getColor(R.color.colorWarning)
            } else {
                context.getColor(R.color.colorPrimary)
            }
            views.setTextColor(R.id.tv_widget_percent, pctColor)

            // Tap widget → open app
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingOpen = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingOpen)

            // "Clean Now" button → open app with scan intent
            val cleanIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = "com.filecleaner.app.ACTION_QUICK_CLEAN"
            }
            val pendingClean = PendingIntent.getActivity(
                context, 1, cleanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_clean, pendingClean)

            manager.updateAppWidget(widgetId, views)
        }
    }
}
