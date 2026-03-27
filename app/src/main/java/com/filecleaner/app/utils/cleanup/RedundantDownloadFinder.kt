package com.filecleaner.app.utils.cleanup

import android.os.Environment
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects files downloaded multiple times (e.g., file.pdf, file (1).pdf,
 * file (2).pdf) and flags older copies for cleanup.
 */
object RedundantDownloadFinder {

    /** Pattern to strip version/copy suffixes: "file (1).pdf" → "file.pdf" */
    private val COPY_PATTERN = Regex("""[\s_-]\(?(\d+|v\d+[\.\d]*|copy)\)?(?=\.[^.]+$)""", RegexOption.IGNORE_CASE)

    /**
     * Finds redundant download copies.
     * @param keepNewest If true, keeps the newest version and flags older ones.
     */
    suspend fun find(
        files: List<FileItem>,
        keepNewest: Boolean = true
    ): List<FileItem> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val downloadPath = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).absolutePath

        val downloads = files.filter { it.path.startsWith(downloadPath) }
        if (downloads.size < 2) return@withContext emptyList()

        val grouped = downloads.groupBy { item ->
            COPY_PATTERN.replace(item.name, "").lowercase()
        }

        val result = mutableListOf<FileItem>()
        for ((_, copies) in grouped) {
            if (copies.size >= 2) {
                val sorted = copies.sortedByDescending { it.lastModified }
                result.addAll(if (keepNewest) sorted.drop(1) else sorted)
            }
        }
        result.sortedByDescending { it.size }
    }
}
