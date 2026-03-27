package com.filecleaner.app.utils.system

import android.app.Activity
import android.content.Context
import com.filecleaner.app.data.UserPreferences
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Manages in-app review prompts using Google Play In-App Review API.
 * Shows the review dialog after the user completes their 3rd scan,
 * and then at most once every 90 days.
 */
object InAppReviewHelper {

    private const val PREF_SCAN_COUNT = "review_scan_count"
    private const val PREF_LAST_PROMPT = "review_last_prompt_ms"
    private const val SCANS_BEFORE_PROMPT = 3
    private const val MIN_DAYS_BETWEEN_PROMPTS = 90L

    /** Call after each successful scan. Shows review prompt when conditions are met. */
    fun onScanCompleted(activity: Activity) {
        val prefs = activity.getSharedPreferences("in_app_review", Context.MODE_PRIVATE)
        val count = prefs.getInt(PREF_SCAN_COUNT, 0) + 1
        prefs.edit().putInt(PREF_SCAN_COUNT, count).apply()

        if (count < SCANS_BEFORE_PROMPT) return

        val lastPrompt = prefs.getLong(PREF_LAST_PROMPT, 0L)
        val daysSince = (System.currentTimeMillis() - lastPrompt) / (24 * 60 * 60 * 1000L)
        if (lastPrompt > 0 && daysSince < MIN_DAYS_BETWEEN_PROMPTS) return

        // Show review dialog
        val manager = ReviewManagerFactory.create(activity)
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val flow = manager.launchReviewFlow(activity, task.result)
                flow.addOnCompleteListener {
                    prefs.edit().putLong(PREF_LAST_PROMPT, System.currentTimeMillis()).apply()
                }
            }
        }
    }
}
