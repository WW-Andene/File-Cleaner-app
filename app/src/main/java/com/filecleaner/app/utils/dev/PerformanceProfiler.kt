package com.filecleaner.app.utils.dev

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * Tracks operation durations and memory usage for performance analysis.
 * Results viewable in the debug panel.
 */
object PerformanceProfiler {

    data class TimedOperation(
        val name: String,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val operations = mutableListOf<TimedOperation>()
    private val activeTimers = mutableMapOf<String, Long>()

    /** Start timing an operation. */
    fun start(name: String) {
        activeTimers[name] = System.currentTimeMillis()
    }

    /** Stop timing and record the duration. */
    fun stop(name: String) {
        val startTime = activeTimers.remove(name) ?: return
        val duration = System.currentTimeMillis() - startTime
        synchronized(operations) {
            operations.add(TimedOperation(name, duration))
            if (operations.size > 50) operations.removeAt(0)
        }
    }

    /** Inline timing helper. */
    inline fun <T> measure(name: String, block: () -> T): T {
        start(name)
        try {
            return block()
        } finally {
            stop(name)
        }
    }

    /** Get all recorded operations, newest first. */
    fun getOperations(): List<TimedOperation> =
        synchronized(operations) { operations.toList().reversed() }

    /** Get memory info. */
    fun getMemoryInfo(context: Context): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        return MemoryInfo(
            heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
            heapMaxMb = runtime.maxMemory() / (1024 * 1024),
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024),
            deviceFreeMb = memInfo.availMem / (1024 * 1024),
            isLowMemory = memInfo.lowMemory
        )
    }

    data class MemoryInfo(
        val heapUsedMb: Long,
        val heapMaxMb: Long,
        val nativeHeapMb: Long,
        val deviceFreeMb: Long,
        val isLowMemory: Boolean
    )

    /** Format performance report for display. */
    fun formatReport(context: Context): String = buildString {
        val mem = getMemoryInfo(context)
        appendLine("═══ Memory ═══")
        appendLine("Heap: ${mem.heapUsedMb}/${mem.heapMaxMb} MB")
        appendLine("Native: ${mem.nativeHeapMb} MB")
        appendLine("Device free: ${mem.deviceFreeMb} MB${if (mem.isLowMemory) " ⚠ LOW" else ""}")
        appendLine()
        appendLine("═══ Operations ═══")
        for (op in getOperations().take(20)) {
            appendLine("${op.name}: ${op.durationMs}ms")
        }
    }

    fun clear() = synchronized(operations) { operations.clear() }
}
