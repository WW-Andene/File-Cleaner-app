package com.filecleaner.app.utils

import android.os.Environment
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Direct filesystem browser — reads files from a directory without
 * requiring a full scan. Provides instant file access like native
 * file managers.
 *
 * Used by BrowseFragment as the primary data source. Scan data from
 * MainViewModel enriches the view (duplicate badges, junk indicators)
 * but is not required for basic browsing.
 */
object DirectoryBrowser {

    data class DirectoryListing(
        val path: String,
        val folders: List<FolderEntry>,
        val files: List<FileItem>,
        val parentPath: String?
    )

    data class FolderEntry(
        val path: String,
        val name: String,
        val itemCount: Int,
        val totalSize: Long
    )

    /**
     * Lists the contents of a directory, returning folders and files separately.
     * Folders are sorted alphabetically, files by the given comparator.
     *
     * @param dirPath Directory to list. Defaults to external storage root.
     * @param showHidden Whether to include hidden files/folders (starting with '.')
     */
    suspend fun listDirectory(
        dirPath: String = Environment.getExternalStorageDirectory().absolutePath,
        showHidden: Boolean = false
    ): DirectoryListing = withContext(Dispatchers.IO) {
        val dir = File(dirPath)
        if (!dir.isDirectory) {
            return@withContext DirectoryListing(dirPath, emptyList(), emptyList(), dir.parent)
        }

        val children = dir.listFiles() ?: return@withContext DirectoryListing(
            dirPath, emptyList(), emptyList(), dir.parent
        )

        val folders = mutableListOf<FolderEntry>()
        val files = mutableListOf<FileItem>()

        for (child in children) {
            if (!showHidden && child.isHidden) continue

            if (child.isDirectory) {
                val contents = child.listFiles()
                folders.add(FolderEntry(
                    path = child.absolutePath,
                    name = child.name,
                    itemCount = contents?.size ?: 0,
                    totalSize = 0 // Calculated lazily to avoid blocking
                ))
            } else {
                val ext = child.extension.lowercase()
                files.add(FileItem(
                    path = child.absolutePath,
                    name = child.name,
                    size = child.length(),
                    lastModified = child.lastModified(),
                    category = FileCategory.fromExtension(ext)
                ))
            }
        }

        DirectoryListing(
            path = dirPath,
            folders = folders.sortedBy { it.name.lowercase() },
            files = files.sortedBy { it.name.lowercase() },
            parentPath = if (dirPath == Environment.getExternalStorageDirectory().absolutePath)
                null else dir.parent
        )
    }

    /** Returns breadcrumb path segments for display. */
    fun getBreadcrumbs(path: String): List<Pair<String, String>> {
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (!path.startsWith(root)) return listOf("Storage" to path)

        val relative = path.removePrefix(root).trimStart(File.separatorChar)
        val segments = mutableListOf("Storage" to root)

        if (relative.isNotEmpty()) {
            var current = root
            for (part in relative.split(File.separatorChar)) {
                current = "$current${File.separator}$part"
                segments.add(part to current)
            }
        }

        return segments
    }
}
