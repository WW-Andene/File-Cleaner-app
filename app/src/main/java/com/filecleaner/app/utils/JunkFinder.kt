package com.filecleaner.app.utils

import android.os.Environment
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object JunkFinder {

    private val JUNK_EXTENSIONS = setOf(
        "tmp", "temp", "log", "bak", "old", "dmp", "crdownload", "part", "partial"
    )

    private const val DEFAULT_STALE_DOWNLOAD_DAYS = 90L

    // A5: Use dot-prefixed names and known Android cache paths to reduce false positives.
    // Directories like "My Cache Project" or "temp_files" won't match — only exact
    // segment names that are conventionally disposable.
    private val JUNK_DIR_EXACT_NAMES = setOf(
        ".cache", ".thumbnails", "lost+found"
    )
    private val JUNK_DIR_CASE_INSENSITIVE = setOf(
        "cache", "temp", "tmp", "thumbnail"
    )

    // F-039: Pre-compiled regex matches exact path segments (surrounded by /)
    // to avoid false positives on user folders containing keywords as substrings.
    private val JUNK_DIR_REGEX = Regex(
        "/(?:${JUNK_DIR_EXACT_NAMES.joinToString("|") { Regex.escape(it) }})/|" +
            "/Android/data/[^/]+/(?:${JUNK_DIR_CASE_INSENSITIVE.joinToString("|") { Regex.escape(it) }})/",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns files that are considered "junk":
     * - Known junk extensions (.tmp, .log, .bak, etc.)
     * - Files in cache/temp directories
     * - Old downloads (> 90 days, not media)
     */
    suspend fun findJunk(files: List<FileItem>): List<FileItem> = withContext(Dispatchers.IO) {
        val staleDays = try { UserPreferences.staleDownloadDays.toLong().coerceIn(1, 3650) } catch (_: Exception) { DEFAULT_STALE_DOWNLOAD_DAYS }
        val cutoff90Days = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(staleDays)
        // File manager needs broad storage access; MANAGE_EXTERNAL_STORAGE grants it
        @Suppress("DEPRECATION")
        val downloadPath = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).absolutePath

        val result = mutableListOf<FileItem>()
        for ((index, item) in files.withIndex()) {
            if (index % 100 == 0) ensureActive()
            val ext = item.extension

            val isJunk = when {
                // Known junk extension
                ext in JUNK_EXTENSIONS -> true

                // F-039: Single regex match instead of per-file lowercase() + per-keyword contains()
                JUNK_DIR_REGEX.containsMatchIn(item.path) -> true

                // Old download (> 90 days old, only truly disposable types)
                // Excludes documents, media, archives, APKs — only flags
                // files with no recognized extension (F-002)
                // Guard: skip files with unknown date (lastModified == 0)
                item.path.startsWith(downloadPath) &&
                        item.lastModified > 0L &&
                        item.lastModified < cutoff90Days &&
                        !isMedia(ext) && !isDocument(ext) &&
                        !isArchiveOrApk(ext) -> true

                else -> false
            }
            if (isJunk) result.add(item)
        }
        result.sortedByDescending { it.size }
    }

    /**
     * Returns the top N largest files on the device.
     */
    suspend fun findLargeFiles(
        files: List<FileItem>,
        minSizeBytes: Long = 50 * 1024 * 1024L, // 50 MB default
        maxResults: Int = 200
    ): List<FileItem> = withContext(Dispatchers.IO) {
        ensureActive()
        // D5: Use sequence to avoid intermediate list allocation — filter first to
        // reduce the sort input (O(k log k) where k << n instead of O(n log n))
        files.asSequence()
            .filter { it.size >= minSizeBytes }
            .sortedByDescending { it.size }
            .take(maxResults)
            .toList()
    }

    // Derive from FileCategory — single source of truth for extension mappings
    private fun isMedia(ext: String) = ext in FileCategory.MEDIA_EXTENSIONS
    private fun isDocument(ext: String) = ext in FileCategory.DOCUMENT_EXTENSIONS
    private fun isArchiveOrApk(ext: String) = ext in FileCategory.ARCHIVE_APK_EXTENSIONS

    /** Find old/duplicate APK files, keeping only the newest per app name. */
    suspend fun findDuplicateApks(files: List<FileItem>): List<FileItem> =
        withContext(Dispatchers.IO) {
            val apkRegex = Regex("""[-_v]\d+[\.\d]*\.apk$""", RegexOption.IGNORE_CASE)
            val apks = files.filter { it.category == FileCategory.APK }
            val grouped = apks.groupBy { apkRegex.replace(it.name, ".apk").lowercase() }

            val result = mutableListOf<FileItem>()
            for ((_, copies) in grouped) {
                if (copies.size >= 2) {
                    result.addAll(copies.sortedByDescending { it.lastModified }.drop(1))
                }
            }
            result.sortedByDescending { it.size }
        }

    /** Find log files in app data directories. */
    suspend fun findLogFiles(files: List<FileItem>): List<FileItem> =
        withContext(Dispatchers.IO) {
            val logExts = setOf("log", "logs", "logcat")
            val appDirs = setOf("/Android/data/", "/Android/obb/", "/.cache/", "/logs/", "/log/")
            files.filter { item ->
                item.extension in logExts &&
                    appDirs.any { item.path.contains(it, ignoreCase = true) }
            }.sortedByDescending { it.size }
        }
}
