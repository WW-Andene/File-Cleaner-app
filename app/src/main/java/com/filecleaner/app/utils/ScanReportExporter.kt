package com.filecleaner.app.utils

import android.content.Context
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.viewmodel.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports scan results as a CSV or text report for enterprise users.
 */
object ScanReportExporter {

    data class ExportResult(val success: Boolean, val path: String, val message: String)

    /** Export scan results as CSV. */
    fun exportCsv(
        context: Context,
        files: List<FileItem>,
        duplicates: List<FileItem>,
        junkFiles: List<FileItem>,
        stats: MainViewModel.StorageStats?
    ): ExportResult {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(context.getExternalFilesDir(null),
                "scan_report_$timestamp.csv")

            outputFile.bufferedWriter().use { writer ->
                // Header
                writer.appendLine("Path,Name,Size (bytes),Size (readable),Category,Last Modified,Is Duplicate,Is Junk")

                val dupPaths = duplicates.map { it.path }.toSet()
                val junkPaths = junkFiles.map { it.path }.toSet()

                for (file in files) {
                    val isDup = if (file.path in dupPaths) "Yes" else "No"
                    val isJunk = if (file.path in junkPaths) "Yes" else "No"
                    writer.appendLine(
                        "\"${file.path}\",\"${file.name}\",${file.size}," +
                        "\"${UndoHelper.formatBytes(file.size)}\",${file.category.name}," +
                        "${file.lastModified},$isDup,$isJunk"
                    )
                }

                // Summary section
                if (stats != null) {
                    writer.appendLine()
                    writer.appendLine("# Summary")
                    writer.appendLine("Total Files,${stats.totalFiles}")
                    writer.appendLine("Total Size,${UndoHelper.formatBytes(stats.totalSize)}")
                    writer.appendLine("Duplicate Size,${UndoHelper.formatBytes(stats.duplicateSize)}")
                    writer.appendLine("Junk Size,${UndoHelper.formatBytes(stats.junkSize)}")
                    writer.appendLine("Large File Size,${UndoHelper.formatBytes(stats.largeSize)}")
                    writer.appendLine("Potential Savings,${UndoHelper.formatBytes(stats.duplicateSize + stats.junkSize)}")
                }
            }

            ExportResult(true, outputFile.absolutePath,
                "Report exported: ${outputFile.name}")
        } catch (e: Exception) {
            ExportResult(false, "", "Export failed: ${e.localizedMessage}")
        }
    }

    /** Export as plain text summary. */
    fun exportTextSummary(
        context: Context,
        stats: MainViewModel.StorageStats?,
        categoryBreakdown: Map<FileCategory, List<FileItem>>
    ): ExportResult {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(context.getExternalFilesDir(null),
                "scan_summary_$timestamp.txt")

            outputFile.bufferedWriter().use { writer ->
                writer.appendLine("=== Raccoon File Manager — Scan Report ===")
                writer.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                writer.appendLine()

                if (stats != null) {
                    writer.appendLine("STORAGE SUMMARY")
                    writer.appendLine("  Total files: ${stats.totalFiles}")
                    writer.appendLine("  Total size:  ${UndoHelper.formatBytes(stats.totalSize)}")
                    writer.appendLine("  Duplicates:  ${UndoHelper.formatBytes(stats.duplicateSize)}")
                    writer.appendLine("  Junk files:  ${UndoHelper.formatBytes(stats.junkSize)}")
                    writer.appendLine("  Large files: ${UndoHelper.formatBytes(stats.largeSize)}")
                    writer.appendLine("  Savings:     ${UndoHelper.formatBytes(stats.duplicateSize + stats.junkSize)}")
                    writer.appendLine()
                }

                writer.appendLine("CATEGORY BREAKDOWN")
                for ((category, files) in categoryBreakdown.entries.sortedByDescending { it.value.sumOf { f -> f.size } }) {
                    val totalSize = files.sumOf { it.size }
                    writer.appendLine("  ${category.name}: ${files.size} files (${UndoHelper.formatBytes(totalSize)})")
                }
            }

            ExportResult(true, outputFile.absolutePath, "Summary exported: ${outputFile.name}")
        } catch (e: Exception) {
            ExportResult(false, "", "Export failed: ${e.localizedMessage}")
        }
    }
}
