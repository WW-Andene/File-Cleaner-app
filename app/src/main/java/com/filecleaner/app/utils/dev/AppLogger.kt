package com.filecleaner.app.utils.dev

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Structured in-app logger. Stores log entries in memory and
 * optionally persists to file. Viewable in the debug panel
 * for troubleshooting without adb.
 */
object AppLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private val entries = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_ENTRIES = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    private fun log(level: Level, tag: String, message: String) {
        entries.add(LogEntry(System.currentTimeMillis(), level, tag, message))
        while (entries.size > MAX_ENTRIES) entries.poll()

        // Also forward to Android logcat
        when (level) {
            Level.DEBUG -> android.util.Log.d(tag, message)
            Level.INFO -> android.util.Log.i(tag, message)
            Level.WARN -> android.util.Log.w(tag, message)
            Level.ERROR -> android.util.Log.e(tag, message)
        }
    }

    /** Get all log entries, newest first. */
    fun getEntries(): List<LogEntry> = entries.toList().reversed()

    /** Get entries filtered by level. */
    fun getEntries(minLevel: Level): List<LogEntry> =
        entries.filter { it.level.ordinal >= minLevel.ordinal }.reversed()

    /** Format log as readable text. */
    fun formatLog(minLevel: Level = Level.DEBUG): String = buildString {
        for (entry in getEntries(minLevel)) {
            val time = dateFormat.format(Date(entry.timestamp))
            val levelChar = entry.level.name.first()
            appendLine("$time $levelChar/${entry.tag}: ${entry.message}")
        }
    }

    /** Export log to file. */
    fun exportToFile(context: Context): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.getExternalFilesDir(null), "raccoon_log_$timestamp.txt")
        file.writeText(formatLog())
        return file
    }

    /** Clear all entries. */
    fun clear() = entries.clear()

    /** Count entries by level. */
    fun countByLevel(): Map<Level, Int> =
        entries.groupBy { it.level }.mapValues { it.value.size }
}
