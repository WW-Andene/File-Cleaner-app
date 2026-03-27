package com.filecleaner.app.utils.analytics

import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.utils.UndoHelper

/**
 * Analyzes the cost of duplicate files — how much storage they waste,
 * which categories are most duplicated, and the potential savings.
 */
object DuplicateCostAnalyzer {

    data class DuplicateCost(
        val totalDuplicateSize: Long,
        val wastedSize: Long,            // Size that could be freed (total - one copy per group)
        val wastedPercent: Int,          // % of total storage wasted
        val duplicateCount: Int,
        val groupCount: Int,
        val largestGroup: GroupInfo?,
        val categoryBreakdown: List<CategoryCost>
    )

    data class GroupInfo(
        val groupId: Int,
        val fileCount: Int,
        val fileSize: Long,             // Size per file
        val wastedSize: Long,           // (count - 1) * fileSize
        val sampleName: String
    )

    data class CategoryCost(
        val category: FileCategory,
        val duplicateCount: Int,
        val wastedSize: Long
    )

    /** Analyze duplicate cost across all scanned files. */
    fun analyze(duplicates: List<FileItem>, totalStorageSize: Long): DuplicateCost {
        if (duplicates.isEmpty()) {
            return DuplicateCost(0, 0, 0, 0, 0, null, emptyList())
        }

        val groups = duplicates.groupBy { it.duplicateGroup }
        val totalDupSize = duplicates.sumOf { it.size }

        // Wasted = total size minus one copy per group
        var wastedSize = 0L
        var largestGroup: GroupInfo? = null

        for ((groupId, files) in groups) {
            if (files.size < 2) continue
            val perFile = files.first().size
            val wasted = perFile * (files.size - 1)
            wastedSize += wasted

            if (largestGroup == null || wasted > (largestGroup.wastedSize)) {
                largestGroup = GroupInfo(groupId, files.size, perFile, wasted, files.first().name)
            }
        }

        val wastedPercent = if (totalStorageSize > 0) ((wastedSize * 100) / totalStorageSize).toInt() else 0

        // Category breakdown
        val catCosts = duplicates.groupBy { it.category }
            .map { (cat, files) ->
                val catGroups = files.groupBy { it.duplicateGroup }
                val catWasted = catGroups.values.sumOf { group ->
                    if (group.size >= 2) group.first().size * (group.size - 1) else 0L
                }
                CategoryCost(cat, files.size, catWasted)
            }
            .filter { it.wastedSize > 0 }
            .sortedByDescending { it.wastedSize }

        return DuplicateCost(
            totalDuplicateSize = totalDupSize,
            wastedSize = wastedSize,
            wastedPercent = wastedPercent,
            duplicateCount = duplicates.size,
            groupCount = groups.size,
            largestGroup = largestGroup,
            categoryBreakdown = catCosts
        )
    }

    /** Format as readable summary. */
    fun formatSummary(cost: DuplicateCost): String = buildString {
        if (cost.duplicateCount == 0) {
            appendLine("No duplicates found — your storage is clean!")
            return@buildString
        }

        appendLine("Duplicate Analysis")
        appendLine("═══════════════════════════════")
        appendLine("${cost.duplicateCount} duplicate files in ${cost.groupCount} groups")
        appendLine("Total duplicate size: ${UndoHelper.formatBytes(cost.totalDuplicateSize)}")
        appendLine("Wasted space: ${UndoHelper.formatBytes(cost.wastedSize)} (${cost.wastedPercent}% of storage)")
        appendLine()

        if (cost.largestGroup != null) {
            appendLine("Largest duplicate group:")
            appendLine("  \"${cost.largestGroup.sampleName}\" × ${cost.largestGroup.fileCount} copies")
            appendLine("  Wasting ${UndoHelper.formatBytes(cost.largestGroup.wastedSize)}")
            appendLine()
        }

        if (cost.categoryBreakdown.isNotEmpty()) {
            appendLine("By category:")
            for (cat in cost.categoryBreakdown) {
                appendLine("  ${cat.category.emoji} ${cat.category.name}: ${cat.duplicateCount} files, ${UndoHelper.formatBytes(cat.wastedSize)} wasted")
            }
        }
    }
}
