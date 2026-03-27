package com.filecleaner.app.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.filecleaner.app.data.UserPreferences
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Automatically switches between light and dark mode based on time of day.
 *
 * Users set a start time (e.g., 20:00) and end time (e.g., 07:00).
 * A periodic WorkManager job checks every 30 minutes and applies the mode.
 */
object DarkModeScheduler {

    private const val WORK_NAME = "dark_mode_schedule"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<DarkModeWorker>(
            30, TimeUnit.MINUTES,
            10, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Check current time and apply dark/light mode immediately. */
    fun applyNow() {
        val startHour = UserPreferences.autoDarkStartHour
        val endHour = UserPreferences.autoDarkEndHour
        if (startHour < 0 || endHour < 0) return

        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isDark = if (startHour < endHour) {
            now in startHour until endHour
        } else {
            now >= startHour || now < endHour // Wraps midnight (e.g., 20:00 → 07:00)
        }

        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    class DarkModeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            try {
                UserPreferences.init(applicationContext)
                applyNow()
            } catch (_: Exception) { }
            return Result.success()
        }
    }
}
