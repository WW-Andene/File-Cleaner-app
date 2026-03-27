package com.filecleaner.app.utils

import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem

/**
 * Deep dive analysis per file category — shows top files, most common
 * extensions, largest folders, and growth trends within each category.
 */
object CategoryAnalyzer {

    data class CategoryDetail(
        val category: FileCategory,
        val totalFiles: Int,
        val totalSize: Long,
        val topFilesBySize: List<FileItem>,
        val extensionBreakdown: List<ExtensionStat>,
        val topFolders: List<FolderStat>,
        val percentOfTotal: Int
    )

    data class ExtensionStat(val extension: String, val count: Int, val totalSize: Long)
    data class FolderStat(val path: String, val name: String, val count: Int, val totalSize: Long)

    /** Generate detailed analysis for a specific category. */
    fun analyze(files: List<FileItem>, category: FileCategory): CategoryDetail {
        val catFiles = files.filter { it.category == category }
        val totalAllFiles = files.sumOf { it.size }

        // Top 10 files by size
        val topFiles = catFiles.sortedByDescending { it.size }.take(10)

        // Extension breakdown
        val extStats = catFiles.groupBy { it.extension }
            .map { (ext, items) ->
                ExtensionStat(ext.ifEmpty { "no ext" }, items.size, items.sumOf { it.size })
            }
            .sortedByDescending { it.totalSize }
            .take(10)

        // Top folders containing these files
        val folderStats = catFiles.groupBy { java.io.File(it.path).parent ?: "/" }
            .map { (path, items) ->
                FolderStat(path, java.io.File(path).name, items.size, items.sumOf { it.size })
            }
            .sortedByDescending { it.totalSize }
            .take(10)

        val catSize = catFiles.sumOf { it.size }
        val percent = if (totalAllFiles > 0) ((catSize * 100) / totalAllFiles).toInt() else 0

        return CategoryDetail(
            category = category,
            totalFiles = catFiles.size,
            totalSize = catSize,
            topFilesBySize = topFiles,
            extensionBreakdown = extStats,
            topFolders = folderStats,
            percentOfTotal = percent
        )
    }

    /** Analyze all categories and return sorted by size. */
    fun analyzeAll(files: List<FileItem>): List<CategoryDetail> {
        return FileCategory.entries
            .map { analyze(files, it) }
            .filter { it.totalFiles > 0 }
            .sortedByDescending { it.totalSize }
    }

    /** Format a single category detail for display. */
    fun formatDetail(detail: CategoryDetail): String = buildString {
        appendLine("${detail.category.emoji} ${detail.category.name}")
        appendLine("${detail.totalFiles} files, ${UndoHelper.formatBytes(detail.totalSize)} (${detail.percentOfTotal}%)")
        appendLine()

        if (detail.extensionBreakdown.isNotEmpty()) {
            appendLine("Extensions:")
            for (ext in detail.extensionBreakdown) {
                appendLine("  .${ext.extension}: ${ext.count} files (${UndoHelper.formatBytes(ext.totalSize)})")
            }
            appendLine()
        }

        if (detail.topFilesBySize.isNotEmpty()) {
            appendLine("Largest files:")
            for (file in detail.topFilesBySize.take(5)) {
                appendLine("  ${file.name} (${UndoHelper.formatBytes(file.size)})")
            }
            appendLine()
        }

        if (detail.topFolders.isNotEmpty()) {
            appendLine("Top folders:")
            for (folder in detail.topFolders.take(5)) {
                appendLine("  ${folder.name}/ — ${folder.count} files (${UndoHelper.formatBytes(folder.totalSize)})")
            }
        }
    }
}
