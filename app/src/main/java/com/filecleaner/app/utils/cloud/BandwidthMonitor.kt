package com.filecleaner.app.utils.cloud

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks data transferred (uploaded + downloaded) per session and
 * cumulative totals. Displayed in cloud browser for transparency.
 */
object BandwidthMonitor {

    private const val PREFS_NAME = "bandwidth"
    private const val KEY_TOTAL_UP = "total_uploaded"
    private const val KEY_TOTAL_DOWN = "total_downloaded"
    private const val KEY_SESSION_UP = "session_uploaded"
    private const val KEY_SESSION_DOWN = "session_downloaded"

    private var sessionUploaded = 0L
    private var sessionDownloaded = 0L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Record bytes uploaded. */
    fun recordUpload(context: Context, bytes: Long) {
        sessionUploaded += bytes
        val total = prefs(context).getLong(KEY_TOTAL_UP, 0) + bytes
        prefs(context).edit().putLong(KEY_TOTAL_UP, total).apply()
    }

    /** Record bytes downloaded. */
    fun recordDownload(context: Context, bytes: Long) {
        sessionDownloaded += bytes
        val total = prefs(context).getLong(KEY_TOTAL_DOWN, 0) + bytes
        prefs(context).edit().putLong(KEY_TOTAL_DOWN, total).apply()
    }

    /** Get session stats. */
    fun getSessionUploaded(): Long = sessionUploaded
    fun getSessionDownloaded(): Long = sessionDownloaded
    fun getSessionTotal(): Long = sessionUploaded + sessionDownloaded

    /** Get all-time stats. */
    fun getTotalUploaded(context: Context): Long = prefs(context).getLong(KEY_TOTAL_UP, 0)
    fun getTotalDownloaded(context: Context): Long = prefs(context).getLong(KEY_TOTAL_DOWN, 0)
    fun getAllTimeTotal(context: Context): Long = getTotalUploaded(context) + getTotalDownloaded(context)

    /** Reset session counters (call on app start). */
    fun resetSession() {
        sessionUploaded = 0
        sessionDownloaded = 0
    }

    /** Format stats for display. */
    fun formatStats(context: Context): String = buildString {
        appendLine("Session: ↑${UndoHelper.formatBytes(sessionUploaded)} ↓${UndoHelper.formatBytes(sessionDownloaded)}")
        appendLine("All-time: ↑${UndoHelper.formatBytes(getTotalUploaded(context))} ↓${UndoHelper.formatBytes(getTotalDownloaded(context))}")
    }

    /** Reset all-time counters. */
    fun resetAllTime(context: Context) {
        prefs(context).edit().clear().apply()
        resetSession()
    }
}
