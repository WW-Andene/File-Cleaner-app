package com.filecleaner.app.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs all network connections the app makes for transparency.
 * Users can view the log in Settings to see exactly what URLs
 * the app connected to and when.
 *
 * Max 200 entries, oldest pruned automatically.
 */
object NetworkActivityLog {

    private const val PREFS_NAME = "network_log"
    private const val KEY_LOG = "entries"
    private const val MAX_ENTRIES = 200

    data class NetworkEntry(
        val timestamp: Long,
        val url: String,
        val method: String,
        val statusCode: Int,
        val purpose: String
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Record a network connection. Call from HTTP clients. */
    fun record(context: Context, url: String, method: String, statusCode: Int, purpose: String) {
        val entries = getEntries(context).toMutableList()
        entries.add(0, NetworkEntry(System.currentTimeMillis(), url, method, statusCode, purpose))

        // Prune old entries
        val capped = entries.take(MAX_ENTRIES)
        persist(context, capped)
    }

    /** Get all logged entries, newest first. */
    fun getEntries(context: Context): List<NetworkEntry> {
        val json = prefs(context).getString(KEY_LOG, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                NetworkEntry(
                    timestamp = obj.getLong("ts"),
                    url = obj.getString("url"),
                    method = obj.optString("method", "GET"),
                    statusCode = obj.optInt("status", 0),
                    purpose = obj.optString("purpose", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Format the log as a readable string for display. */
    fun formatReadable(context: Context): String {
        val entries = getEntries(context)
        if (entries.isEmpty()) return "No network activity recorded."

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("Network Activity Log (${entries.size} entries)")
            appendLine("═══════════════════════════════")
            for (entry in entries) {
                appendLine()
                appendLine("${dateFormat.format(Date(entry.timestamp))}")
                appendLine("  ${entry.method} ${entry.url}")
                appendLine("  Status: ${entry.statusCode} | Purpose: ${entry.purpose}")
            }
        }
    }

    /** Clear all logged entries. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LOG).apply()
    }

    private fun persist(context: Context, entries: List<NetworkEntry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(JSONObject().apply {
                put("ts", e.timestamp)
                put("url", e.url)
                put("method", e.method)
                put("status", e.statusCode)
                put("purpose", e.purpose)
            })
        }
        prefs(context).edit().putString(KEY_LOG, array.toString()).apply()
    }
}
