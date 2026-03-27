package com.filecleaner.app.utils.cleanup

import android.content.Context
import android.os.Environment
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.utils.file.JunkFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates a unified cleanup plan by aggregating all cleanup sources.
 * Shows users exactly what will be cleaned before they confirm.
 *
 * Categories:
 * 1. Junk/temp files — safe, auto-selected
 * 2. App residuals — safe, auto-selected
 * 3. Redundant downloads — safe, auto-selected
 * 4. Empty folders — safe, auto-selected
 * 5. Thumbnail caches — safe, auto-selected
 * 6. Old large media — risky, NOT auto-selected
 */
object SmartCleanPlanner {

    data class CleanupCategory(
        val id: String,
        val title: String,
        val description: String,
        val icon: Int,
        val files: List<FileItem>,
        val totalSize: Long,
        var selected: Boolean = true
    )

    data class CleanupPlan(
        val categories: List<CleanupCategory>,
        val totalSize: Long,
        val totalFiles: Int
    ) {
        val selectedSize: Long get() = categories.filter { it.selected }.sumOf { it.totalSize }
        val selectedFiles: Int get() = categories.filter { it.selected }.sumOf { it.files.size }

        fun selectedItems(): List<FileItem> =
            categories.filter { it.selected }.flatMap { it.files }
    }

    /**
     * Scans all cleanup sources and builds a preview plan.
     * Does NOT delete anything — the caller must execute selectedItems().
     */
    suspend fun generatePlan(
        context: Context,
        files: List<FileItem>
    ): CleanupPlan = withContext(Dispatchers.IO) {
        val categories = mutableListOf<CleanupCategory>()

        // 1. Junk files
        val junk = JunkFinder.findJunk(files)
        if (junk.isNotEmpty()) {
            categories.add(CleanupCategory(
                id = "junk",
                title = context.getString(R.string.smart_clean_junk),
                description = context.getString(R.string.smart_clean_junk_desc),
                icon = R.drawable.ic_delete,
                files = junk,
                totalSize = junk.sumOf { it.size },
                selected = true
            ))
        }

        // 2. App residual data
        val residuals = AppResidualFinder.find(context)
        if (residuals.isNotEmpty()) {
            val residualItems = residuals.map { r ->
                FileItem(r.path, r.packageName, r.sizeBytes, 0, FileCategory.OTHER)
            }
            categories.add(CleanupCategory(
                id = "residuals",
                title = context.getString(R.string.smart_clean_residuals),
                description = context.getString(R.string.smart_clean_residuals_desc),
                icon = R.drawable.ic_apps,
                files = residualItems,
                totalSize = residuals.sumOf { it.sizeBytes },
                selected = true
            ))
        }

        // 3. Redundant downloads
        val redundant = RedundantDownloadFinder.find(files)
        if (redundant.isNotEmpty()) {
            categories.add(CleanupCategory(
                id = "downloads",
                title = context.getString(R.string.smart_clean_downloads),
                description = context.getString(R.string.smart_clean_downloads_desc),
                icon = R.drawable.ic_download,
                files = redundant,
                totalSize = redundant.sumOf { it.size },
                selected = true
            ))
        }

        // 4. Thumbnail caches
        val thumbnails = files.filter { item ->
            item.path.contains("/.thumbnails/", ignoreCase = true) ||
                item.path.contains("/.thumbs/", ignoreCase = true)
        }
        if (thumbnails.isNotEmpty()) {
            categories.add(CleanupCategory(
                id = "thumbnails",
                title = context.getString(R.string.smart_clean_thumbnails),
                description = context.getString(R.string.smart_clean_thumbnails_desc),
                icon = R.drawable.ic_image,
                files = thumbnails,
                totalSize = thumbnails.sumOf { it.size },
                selected = true
            ))
        }

        // 5. Old large media (NOT auto-selected — risky)
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
        val oldMedia = files.filter { item ->
            item.category in listOf(FileCategory.IMAGE, FileCategory.VIDEO) &&
                item.size > 50 * 1024 * 1024 &&
                item.lastModified in 1 until cutoff
        }.sortedByDescending { it.size }
        if (oldMedia.isNotEmpty()) {
            categories.add(CleanupCategory(
                id = "old_media",
                title = context.getString(R.string.smart_clean_old_media),
                description = context.getString(R.string.smart_clean_old_media_desc),
                icon = R.drawable.ic_similar_photos,
                files = oldMedia,
                totalSize = oldMedia.sumOf { it.size },
                selected = false // Conservative — user must opt in
            ))
        }

        val plan = CleanupPlan(
            categories = categories,
            totalSize = categories.sumOf { it.totalSize },
            totalFiles = categories.sumOf { it.files.size }
        )
        plan
    }
}
