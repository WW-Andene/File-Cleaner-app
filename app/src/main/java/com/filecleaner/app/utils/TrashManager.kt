package com.filecleaner.app.utils

import android.os.Environment
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import java.io.File

/**
 * Provides read access to the app's trash directory for the Recycle Bin
 * viewer. Users can browse, restore, or permanently delete trashed files.
 */
object TrashManager {

    private val trashDir: File
        get() = File(Environment.getExternalStorageDirectory(), ".trash")

    data class TrashEntry(
        val item: FileItem,
        val originalPath: String? // Stored in .meta file if available
    )

    /** Lists all files currently in the trash directory. */
    fun listTrash(): List<TrashEntry> {
        val dir = trashDir
        if (!dir.isDirectory) return emptyList()

        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && !it.name.endsWith(".meta") }
            .map { file ->
                val metaFile = File(file.absolutePath + ".meta")
                val originalPath = if (metaFile.exists()) {
                    try { metaFile.readText().trim() } catch (_: Exception) { null }
                } else null

                TrashEntry(
                    item = FileItem(
                        path = file.absolutePath,
                        name = file.name,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        category = FileCategory.fromExtension(file.extension.lowercase())
                    ),
                    originalPath = originalPath
                )
            }
            .sortedByDescending { it.item.lastModified }
    }

    /** Returns the total size of all trashed files. */
    fun totalTrashSize(): Long {
        val dir = trashDir
        if (!dir.isDirectory) return 0
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
    }

    /** Permanently deletes a file from trash. */
    fun permanentlyDelete(path: String): Boolean {
        val file = File(path)
        if (!file.absolutePath.startsWith(trashDir.absolutePath)) return false // Security check
        val meta = File("$path.meta")
        meta.delete()
        return file.delete()
    }

    /** Restores a file from trash to its original location. */
    fun restore(trashPath: String): Boolean {
        val trashFile = File(trashPath)
        if (!trashFile.exists()) return false

        val metaFile = File("$trashPath.meta")
        val originalPath = if (metaFile.exists()) {
            try { metaFile.readText().trim() } catch (_: Exception) { null }
        } else null

        if (originalPath == null) return false

        val destFile = File(originalPath)
        destFile.parentFile?.mkdirs()
        val success = trashFile.renameTo(destFile)
        if (success) metaFile.delete()
        return success
    }

    /** Permanently deletes all files in trash. */
    fun emptyTrash(): Int {
        val dir = trashDir
        if (!dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.delete()) count++
        }
        return count
    }

    /** Returns true if the trash directory has files. */
    fun hasTrash(): Boolean = trashDir.isDirectory && (trashDir.listFiles()?.isNotEmpty() == true)
}
