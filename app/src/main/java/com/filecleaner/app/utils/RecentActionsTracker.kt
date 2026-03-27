package com.filecleaner.app.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks recently used features so the hub can highlight them
 * and suggest quick-access shortcuts.
 */
object RecentActionsTracker {

    private const val PREFS_NAME = "recent_actions"
    private const val KEY_ACTIONS = "actions"
    private const val MAX_RECENT = 5

    data class RecentAction(
        val featureId: String,
        val timestamp: Long
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Record that a feature was used. */
    fun record(context: Context, featureId: String) {
        val recent = getRecent(context).toMutableList()
        recent.removeAll { it.featureId == featureId }
        recent.add(0, RecentAction(featureId, System.currentTimeMillis()))
        val capped = recent.take(MAX_RECENT)
        prefs(context).edit().putString(KEY_ACTIONS,
            capped.joinToString(",") { "${it.featureId}:${it.timestamp}" }
        ).apply()
    }

    /** Get recently used features, most recent first. */
    fun getRecent(context: Context): List<RecentAction> {
        val raw = prefs(context).getString(KEY_ACTIONS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                RecentAction(parts[0], parts[1].toLongOrNull() ?: 0)
            } else null
        }
    }

    /** Get the most recent feature IDs. */
    fun getRecentIds(context: Context): List<String> =
        getRecent(context).map { it.featureId }
}
