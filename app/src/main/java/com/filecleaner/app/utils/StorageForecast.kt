package com.filecleaner.app.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs

/**
 * Predicts when storage will be full based on historical usage trends.
 * Uses StorageHistoryManager snapshots to calculate growth rate.
 */
object StorageForecast {

    data class Forecast(
        val daysUntilFull: Int?,      // null = shrinking or stable
        val dailyGrowthBytes: Long,   // positive = growing
        val currentFreeBytes: Long,
        val currentUsedBytes: Long,
        val totalBytes: Long,
        val usedPercent: Int,
        val trend: Trend
    )

    enum class Trend { GROWING, STABLE, SHRINKING }

    /** Generate a storage forecast based on last 30 days of data. */
    fun predict(context: Context): Forecast {
        @Suppress("DEPRECATION")
        val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.freeBytes
        val usedBytes = totalBytes - freeBytes
        val usedPercent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0

        val snapshots = StorageHistoryManager.loadSnapshots(context)
        if (snapshots.size < 2) {
            return Forecast(null, 0, freeBytes, usedBytes, totalBytes, usedPercent, Trend.STABLE)
        }

        // Calculate daily growth rate from oldest to newest snapshot
        val oldest = snapshots.first()
        val newest = snapshots.last()
        val daysBetween = ((newest.totalSize - oldest.totalSize).toDouble() /
            (newest.date.hashCode() - oldest.date.hashCode()).coerceAtLeast(1)).toLong()

        // Simplified: use total size delta / number of snapshots
        val totalDelta = newest.totalSize - oldest.totalSize
        val days = snapshots.size.coerceAtLeast(1)
        val dailyGrowth = totalDelta / days

        val trend = when {
            dailyGrowth > 1_048_576 -> Trend.GROWING  // >1 MB/day
            dailyGrowth < -1_048_576 -> Trend.SHRINKING
            else -> Trend.STABLE
        }

        val daysUntilFull = if (dailyGrowth > 0 && freeBytes > 0) {
            (freeBytes / dailyGrowth).toInt()
        } else null

        return Forecast(daysUntilFull, dailyGrowth, freeBytes, usedBytes, totalBytes, usedPercent, trend)
    }

    /** Format forecast as human-readable text. */
    fun formatForecast(forecast: Forecast): String = buildString {
        appendLine("Storage: ${UndoHelper.formatBytes(forecast.currentUsedBytes)} / ${UndoHelper.formatBytes(forecast.totalBytes)} (${forecast.usedPercent}%)")
        appendLine("Free: ${UndoHelper.formatBytes(forecast.currentFreeBytes)}")
        appendLine()
        when (forecast.trend) {
            Trend.GROWING -> {
                appendLine("📈 Growing by ~${UndoHelper.formatBytes(forecast.dailyGrowthBytes)}/day")
                if (forecast.daysUntilFull != null) {
                    when {
                        forecast.daysUntilFull < 7 -> appendLine("⚠️ Storage full in ~${forecast.daysUntilFull} days!")
                        forecast.daysUntilFull < 30 -> appendLine("Storage full in ~${forecast.daysUntilFull} days")
                        forecast.daysUntilFull < 365 -> appendLine("Storage full in ~${forecast.daysUntilFull / 30} months")
                        else -> appendLine("Storage full in ~${forecast.daysUntilFull / 365} years")
                    }
                }
            }
            Trend.SHRINKING -> appendLine("📉 Shrinking by ~${UndoHelper.formatBytes(-forecast.dailyGrowthBytes)}/day")
            Trend.STABLE -> appendLine("📊 Storage usage is stable")
        }
    }
}
