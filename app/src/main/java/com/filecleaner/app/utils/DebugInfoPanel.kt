package com.filecleaner.app.utils

import android.content.Context
import android.os.Build
import com.filecleaner.app.BuildConfig
import com.filecleaner.app.data.UserPreferences

/**
 * Generates debug information for troubleshooting and support.
 * Accessible from Settings when user taps version number 7 times.
 */
object DebugInfoPanel {

    /** Generate complete debug info report. */
    fun generateReport(context: Context): String = buildString {
        appendLine("═══ BUILD INFO ═══")
        appendLine("App: ${BuildConfig.APPLICATION_ID}")
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
        appendLine("Debug: ${BuildConfig.DEBUG}")
        appendLine()

        appendLine("═══ DEVICE INFO ═══")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Display: ${context.resources.displayMetrics.let { "${it.widthPixels}×${it.heightPixels} @ ${it.density}x" }}")
        appendLine("Locale: ${java.util.Locale.getDefault()}")
        appendLine()

        appendLine("═══ STORAGE ═══")
        try {
            val statFs = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath)
            appendLine("Total: ${UndoHelper.formatBytes(statFs.totalBytes)}")
            appendLine("Free: ${UndoHelper.formatBytes(statFs.freeBytes)}")
            appendLine("Used: ${UndoHelper.formatBytes(statFs.totalBytes - statFs.freeBytes)}")
        } catch (_: Exception) {
            appendLine("Unable to read storage")
        }
        appendLine()

        appendLine("═══ MEMORY ═══")
        val mem = PerformanceProfiler.getMemoryInfo(context)
        appendLine("Heap: ${mem.heapUsedMb}/${mem.heapMaxMb} MB")
        appendLine("Native: ${mem.nativeHeapMb} MB")
        appendLine("Device free: ${mem.deviceFreeMb} MB")
        appendLine("Low memory: ${mem.isLowMemory}")
        appendLine()

        appendLine("═══ PREFERENCES ═══")
        try {
            appendLine("Theme: ${UserPreferences.themeMode}")
            appendLine("Large file threshold: ${UserPreferences.largeFileThresholdMb} MB")
            appendLine("Stale download days: ${UserPreferences.staleDownloadDays}")
            appendLine("Show hidden: ${UserPreferences.showHiddenFiles}")
            appendLine("High contrast: ${UserPreferences.highContrastEnabled}")
            appendLine("Crash reporting: ${UserPreferences.crashReportingEnabled}")
        } catch (_: Exception) {
            appendLine("Preferences not initialized")
        }
        appendLine()

        appendLine("═══ FEATURE FLAGS ═══")
        for ((flag, enabled) in FeatureFlags.getAllFlags(context)) {
            appendLine("${if (enabled) "✓" else "✗"} ${flag.description} (${flag.key})")
        }
        appendLine()

        appendLine("═══ PERFORMANCE ═══")
        for (op in PerformanceProfiler.getOperations().take(10)) {
            appendLine("${op.name}: ${op.durationMs}ms")
        }
        appendLine()

        appendLine("═══ LOG (last 20) ═══")
        append(AppLogger.formatLog(AppLogger.Level.WARN).take(2000))
    }

    /** Copy-friendly summary for support tickets. */
    fun generateSupportInfo(context: Context): String = buildString {
        appendLine("Raccoon File Manager v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        try {
            val statFs = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath)
            val usedPct = ((statFs.totalBytes - statFs.freeBytes) * 100 / statFs.totalBytes).toInt()
            appendLine("Storage: $usedPct% used (${UndoHelper.formatBytes(statFs.freeBytes)} free)")
        } catch (_: Exception) {}
        val mem = PerformanceProfiler.getMemoryInfo(context)
        appendLine("Memory: ${mem.heapUsedMb}/${mem.heapMaxMb} MB heap")
    }
}
