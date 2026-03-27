package com.filecleaner.app.utils.analytics

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks storage usage snapshots over time so the dashboard can show trends.
 *
 * Saves one snapshot per day (keyed by date string). Old entries beyond
 * [MAX_HISTORY_DAYS] are pruned on each save to keep storage bounded.
 *
 * Data is stored as a JSON array in SharedPreferences.
 */
object StorageHistoryManager {

    private const val PREFS_NAME = "storage_history"
    private const val KEY_SNAPSHOTS = "snapshots"
    private const val MAX_HISTORY_DAYS = 90

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    data class StorageSnapshot(
        val date: String,
        val totalFiles: Int,
        val totalSize: Long,
        val junkSize: Long,
        val duplicateSize: Long,
        val largeSize: Long
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records a storage snapshot for today. If a snapshot for today already exists,
     * it is replaced with the latest data.
     */
    fun recordSnapshot(
        context: Context,
        totalFiles: Int,
        totalSize: Long,
        junkSize: Long,
        duplicateSize: Long,
        largeSize: Long
    ) {
        val today = dateFormat.format(Date())
        val snapshots = loadSnapshots(context).toMutableList()

        // Replace today's entry if it exists
        snapshots.removeAll { it.date == today }
        snapshots.add(StorageSnapshot(today, totalFiles, totalSize, junkSize, duplicateSize, largeSize))

        // Prune entries older than MAX_HISTORY_DAYS
        val cutoff = System.currentTimeMillis() - MAX_HISTORY_DAYS * 24L * 60 * 60 * 1000
        val pruned = snapshots.filter { snapshot ->
            val ts = try { dateFormat.parse(snapshot.date)?.time ?: 0L } catch (_: Exception) { 0L }
            ts >= cutoff
        }

        saveSnapshots(context, pruned)
    }

    /**
     * Returns all stored snapshots sorted by date (oldest first).
     */
    fun loadSnapshots(context: Context): List<StorageSnapshot> {
        val json = prefs(context).getString(KEY_SNAPSHOTS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                StorageSnapshot(
                    date = obj.getString("date"),
                    totalFiles = obj.getInt("totalFiles"),
                    totalSize = obj.getLong("totalSize"),
                    junkSize = obj.optLong("junkSize", 0),
                    duplicateSize = obj.optLong("duplicateSize", 0),
                    largeSize = obj.optLong("largeSize", 0)
                )
            }.sortedBy { it.date }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the change in total storage size between the most recent snapshot
     * and the snapshot from [daysAgo] days ago. Returns null if not enough history.
     */
    fun getStorageChange(context: Context, daysAgo: Int = 7): StorageChange? {
        val snapshots = loadSnapshots(context)
        if (snapshots.size < 2) return null

        val latest = snapshots.last()
        val cutoffDate = System.currentTimeMillis() - daysAgo * 24L * 60 * 60 * 1000
        val older = snapshots.lastOrNull { snapshot ->
            val ts = try { dateFormat.parse(snapshot.date)?.time ?: 0L } catch (_: Exception) { 0L }
            ts <= cutoffDate
        } ?: snapshots.first()

        return StorageChange(
            periodDays = daysAgo,
            totalSizeDelta = latest.totalSize - older.totalSize,
            junkSizeDelta = latest.junkSize - older.junkSize,
            duplicateSizeDelta = latest.duplicateSize - older.duplicateSize,
            totalFilesDelta = latest.totalFiles - older.totalFiles
        )
    }

    data class StorageChange(
        val periodDays: Int,
        val totalSizeDelta: Long,
        val junkSizeDelta: Long,
        val duplicateSizeDelta: Long,
        val totalFilesDelta: Int
    ) {
        val isGrowing: Boolean get() = totalSizeDelta > 0
    }

    private fun saveSnapshots(context: Context, snapshots: List<StorageSnapshot>) {
        val array = JSONArray()
        for (s in snapshots) {
            array.put(JSONObject().apply {
                put("date", s.date)
                put("totalFiles", s.totalFiles)
                put("totalSize", s.totalSize)
                put("junkSize", s.junkSize)
                put("duplicateSize", s.duplicateSize)
                put("largeSize", s.largeSize)
            })
        }
        prefs(context).edit().putString(KEY_SNAPSHOTS, array.toString()).apply()
    }

    /**
     * Clears all stored history. Useful for testing or user-initiated reset.
     */
    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_SNAPSHOTS).apply()
    }
}
