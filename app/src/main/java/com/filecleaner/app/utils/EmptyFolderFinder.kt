package com.filecleaner.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds empty directories that can be safely removed.
 * Uses iterative BFS to avoid stack overflow on deep trees.
 */
object EmptyFolderFinder {

    data class EmptyFolder(
        val path: String,
        val name: String,
        val depth: Int
    )

    /** Finds all empty directories under [rootPath]. */
    suspend fun find(rootPath: String, showHidden: Boolean = false): List<EmptyFolder> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<EmptyFolder>()
            val root = File(rootPath)
            if (!root.isDirectory) return@withContext result

            // Collect all directories first via BFS
            val allDirs = mutableListOf<File>()
            val queue = ArrayDeque<File>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                ensureActive()
                val dir = queue.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (child.isDirectory && (showHidden || !child.isHidden)) {
                        allDirs.add(child)
                        queue.add(child)
                    }
                }
            }

            // Check each directory — empty means no files anywhere inside
            for (dir in allDirs) {
                if (isEffectivelyEmpty(dir)) {
                    val depth = dir.absolutePath.count { it == File.separatorChar } -
                        root.absolutePath.count { it == File.separatorChar }
                    result.add(EmptyFolder(dir.absolutePath, dir.name, depth))
                }
            }

            // Sort deepest first so deleting children before parents
            result.sortedByDescending { it.depth }
        }

    /** Delete an empty folder and its empty parents up to [stopAt]. */
    fun delete(path: String, stopAt: String? = null): Boolean {
        val dir = File(path)
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    private fun isEffectivelyEmpty(dir: File): Boolean {
        val children = dir.listFiles() ?: return true
        if (children.isEmpty()) return true
        // Contains only empty subdirectories
        return children.all { it.isDirectory && isEffectivelyEmpty(it) }
    }
}
