package com.filecleaner.app.utils

import android.os.Environment
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import java.io.File

/**
 * Scans WhatsApp, Telegram, and other messaging app media folders
 * for files that can be safely cleaned (received media, voice notes,
 * cached stickers, etc.).
 *
 * These folders accumulate significant storage over time since messaging
 * apps download all shared media by default.
 */
object MessagingMediaCleaner {

    data class MessagingMediaGroup(
        val appName: String,
        val category: String, // "Images", "Videos", "Voice Notes", etc.
        val files: List<FileItem>,
        val totalSize: Long
    )

    /** Known messaging app media directories relative to external storage. */
    private val MESSAGING_PATHS = listOf(
        // WhatsApp
        MessagingApp("WhatsApp", listOf(
            "WhatsApp/Media/WhatsApp Images" to "Images",
            "WhatsApp/Media/WhatsApp Video" to "Videos",
            "WhatsApp/Media/WhatsApp Audio" to "Audio",
            "WhatsApp/Media/WhatsApp Voice Notes" to "Voice Notes",
            "WhatsApp/Media/WhatsApp Documents" to "Documents",
            "WhatsApp/Media/WhatsApp Stickers" to "Stickers",
            "WhatsApp/Media/WhatsApp Animated Gifs" to "GIFs",
            "WhatsApp/Media/.Statuses" to "Statuses"
        )),
        // Telegram
        MessagingApp("Telegram", listOf(
            "Telegram/Telegram Images" to "Images",
            "Telegram/Telegram Video" to "Videos",
            "Telegram/Telegram Audio" to "Audio",
            "Telegram/Telegram Documents" to "Documents"
        )),
        // Signal
        MessagingApp("Signal", listOf(
            "Signal/Signal Images" to "Images",
            "Signal/Signal Video" to "Videos"
        )),
        // Viber
        MessagingApp("Viber", listOf(
            "Viber/media/Viber Images" to "Images",
            "Viber/media/Viber Videos" to "Videos"
        ))
    )

    private data class MessagingApp(
        val name: String,
        val folders: List<Pair<String, String>> // relative path to category name
    )

    /**
     * Scans all known messaging app media folders and returns grouped results.
     * Only returns groups with at least one file.
     */
    fun scan(): List<MessagingMediaGroup> {
        val storage = Environment.getExternalStorageDirectory().absolutePath
        val groups = mutableListOf<MessagingMediaGroup>()

        for (app in MESSAGING_PATHS) {
            for ((relativePath, category) in app.folders) {
                val dir = File(storage, relativePath)
                if (!dir.isDirectory) continue

                val files = scanDirectory(dir)
                if (files.isNotEmpty()) {
                    groups.add(MessagingMediaGroup(
                        appName = app.name,
                        category = category,
                        files = files,
                        totalSize = files.sumOf { it.size }
                    ))
                }
            }
        }

        return groups.sortedByDescending { it.totalSize }
    }

    /** Returns the total size of all messaging app media. */
    fun getTotalSize(): Long {
        val storage = Environment.getExternalStorageDirectory().absolutePath
        var total = 0L
        for (app in MESSAGING_PATHS) {
            for ((relativePath, _) in app.folders) {
                val dir = File(storage, relativePath)
                if (dir.isDirectory) {
                    total += calculateDirSize(dir)
                }
            }
        }
        return total
    }

    /** Quick check: returns true if any messaging media folders exist. */
    fun hasMessagingMedia(): Boolean {
        val storage = Environment.getExternalStorageDirectory().absolutePath
        return MESSAGING_PATHS.any { app ->
            app.folders.any { (path, _) -> File(storage, path).isDirectory }
        }
    }

    private const val MAX_FILES_PER_GROUP = 10_000

    private fun scanDirectory(dir: File): List<FileItem> {
        val files = mutableListOf<FileItem>()
        val children = dir.listFiles() ?: return files
        for (child in children) {
            if (files.size >= MAX_FILES_PER_GROUP) break // Cap to prevent OOM on huge folders
            if (child.isFile && !child.isHidden) {
                val ext = child.extension.lowercase()
                val category = FileCategory.fromExtension(ext)
                files.add(FileItem(
                    path = child.absolutePath,
                    name = child.name,
                    size = child.length(),
                    lastModified = child.lastModified(),
                    category = category
                ))
            } else if (child.isDirectory && child.name != ".nomedia") {
                files.addAll(scanDirectory(child))
            }
        }
        return files
    }

    /** Iterative BFS to avoid StackOverflowError on deep directory trees. */
    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        val queue = ArrayDeque<File>()
        queue.add(dir)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) size += child.length()
                else if (child.isDirectory) queue.add(child)
            }
        }
        return size
    }
}
