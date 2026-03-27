package com.filecleaner.app.utils

import com.filecleaner.app.data.FileItem

/**
 * Analyzes files by age to help users identify old data worth cleaning.
 * Groups files into time buckets with size totals.
 */
object FileAgeAnalyzer {

    data class AgeBucket(
        val label: String,
        val files: List<FileItem>,
        val totalSize: Long,
        val fileCount: Int
    )

    data class AgeDistribution(
        val buckets: List<AgeBucket>,
        val oldestFileDate: Long,
        val newestFileDate: Long,
        val totalFiles: Int,
        val totalSize: Long
    )

    /** Analyze file age distribution across time buckets. */
    fun analyze(files: List<FileItem>): AgeDistribution {
        if (files.isEmpty()) return AgeDistribution(emptyList(), 0, 0, 0, 0)

        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L

        val bucketDefs = listOf(
            "Today" to (0L to 1 * day),
            "This week" to (1 * day to 7 * day),
            "This month" to (7 * day to 30 * day),
            "1-3 months" to (30 * day to 90 * day),
            "3-6 months" to (90 * day to 180 * day),
            "6-12 months" to (180 * day to 365 * day),
            "1-2 years" to (365 * day to 730 * day),
            "2+ years" to (730 * day to Long.MAX_VALUE)
        )

        val buckets = bucketDefs.map { (label, range) ->
            val (minAge, maxAge) = range
            val matching = files.filter { item ->
                val age = now - item.lastModified
                age >= minAge && age < maxAge
            }
            AgeBucket(label, matching, matching.sumOf { it.size }, matching.size)
        }.filter { it.fileCount > 0 }

        return AgeDistribution(
            buckets = buckets,
            oldestFileDate = files.minOf { it.lastModified },
            newestFileDate = files.maxOf { it.lastModified },
            totalFiles = files.size,
            totalSize = files.sumOf { it.size }
        )
    }

    /** Format as readable text. */
    fun formatDistribution(dist: AgeDistribution): String = buildString {
        appendLine("File Age Distribution (${dist.totalFiles} files)")
        appendLine("═══════════════════════════════")
        for (bucket in dist.buckets) {
            val pct = if (dist.totalSize > 0) (bucket.totalSize * 100 / dist.totalSize) else 0
            appendLine("${bucket.label}: ${bucket.fileCount} files (${UndoHelper.formatBytes(bucket.totalSize)}, $pct%)")
        }
    }
}
