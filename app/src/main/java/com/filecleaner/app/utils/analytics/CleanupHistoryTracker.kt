package com.filecleaner.app.utils.analytics

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks cleanup operations history — what was cleaned, when, and
 * how much space was freed. Motivates users and shows impact.
 */
object CleanupHistoryTracker {

    private const val PREFS_NAME = "cleanup_history"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_TOTAL_FREED = "total_freed_bytes"
    private const val MAX_ENTRIES = 100

    data class CleanupEntry(
        val timestamp: Long,
        val type: String,        // "junk", "duplicates", "messaging", "manual", etc.
        val filesDeleted: Int,
        val bytesFreed: Long,
        val description: String
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Record a cleanup operation. */
    fun record(context: Context, type: String, filesDeleted: Int, bytesFreed: Long, description: String) {
        val entries = getHistory(context).toMutableList()
        entries.add(0, CleanupEntry(System.currentTimeMillis(), type, filesDeleted, bytesFreed, description))
        val capped = entries.take(MAX_ENTRIES)

        // Update total
        val total = prefs(context).getLong(KEY_TOTAL_FREED, 0) + bytesFreed
        prefs(context).edit()
            .putLong(KEY_TOTAL_FREED, total)
            .putString(KEY_ENTRIES, entriesToJson(capped))
            .apply()
    }

    /** Get cleanup history, newest first. */
    fun getHistory(context: Context): List<CleanupEntry> {
        val json = prefs(context).getString(KEY_ENTRIES, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CleanupEntry(
                    timestamp = obj.getLong("ts"),
                    type = obj.getString("type"),
                    filesDeleted = obj.getInt("files"),
                    bytesFreed = obj.getLong("bytes"),
                    description = obj.optString("desc", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Total bytes freed since app install. */
    fun totalBytesFreed(context: Context): Long =
        prefs(context).getLong(KEY_TOTAL_FREED, 0)

    /** Total files deleted since app install. */
    fun totalFilesDeleted(context: Context): Int =
        getHistory(context).sumOf { it.filesDeleted }

    /** Total cleanup operations performed. */
    fun totalCleanups(context: Context): Int =
        getHistory(context).size

    /** Format stats for display. */
    fun formatSummary(context: Context): String {
        val total = totalBytesFreed(context)
        val files = totalFilesDeleted(context)
        val cleanups = totalCleanups(context)
        return "🧹 ${UndoHelper.formatBytes(total)} freed across $cleanups cleanups ($files files)"
    }

    /** Format full history. */
    fun formatHistory(context: Context): String {
        val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        val entries = getHistory(context)
        if (entries.isEmpty()) return "No cleanup history yet."
        return buildString {
            appendLine("Cleanup History")
            appendLine("═══════════════════════════════")
            for (entry in entries.take(20)) {
                appendLine()
                appendLine("${dateFormat.format(Date(entry.timestamp))} — ${entry.type}")
                appendLine("  ${entry.filesDeleted} files, ${UndoHelper.formatBytes(entry.bytesFreed)} freed")
                if (entry.description.isNotBlank()) appendLine("  ${entry.description}")
            }
            if (entries.size > 20) appendLine("\n... and ${entries.size - 20} more")
        }
    }

    /** Clear history. */
    fun clearHistory(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun entriesToJson(entries: List<CleanupEntry>): String {
        val array = JSONArray()
        for (e in entries) {
            array.put(JSONObject().apply {
                put("ts", e.timestamp)
                put("type", e.type)
                put("files", e.filesDeleted)
                put("bytes", e.bytesFreed)
                put("desc", e.description)
            })
        }
        return array.toString()
    }
}
