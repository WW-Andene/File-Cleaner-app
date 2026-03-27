package com.filecleaner.app.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detects leftover data directories from uninstalled apps.
 * These folders in /Android/data/ and /Android/obb/ persist after
 * uninstall and waste storage.
 */
object AppResidualFinder {

    data class ResidualApp(
        val packageName: String,
        val path: String,
        val sizeBytes: Long
    )

    /** Finds data directories belonging to apps no longer installed. */
    suspend fun find(context: Context): List<ResidualApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (_: Exception) { emptyList() }

        val installedPackages = installed.map { it.packageName }.toSet()

        @Suppress("DEPRECATION")
        val storage = Environment.getExternalStorageDirectory().absolutePath
        val dirsToCheck = listOf(
            File(storage, "Android/data"),
            File(storage, "Android/obb"),
            File(storage, "Android/media")
        )

        val result = mutableListOf<ResidualApp>()
        for (parentDir in dirsToCheck) {
            if (!parentDir.isDirectory) continue
            val children = parentDir.listFiles() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name !in installedPackages) {
                    val size = calculateSize(child)
                    if (size > 0) {
                        result.add(ResidualApp(child.name, child.absolutePath, size))
                    }
                }
            }
        }
        result.sortedByDescending { it.sizeBytes }
    }

    /** Total size of residual data. */
    suspend fun totalResidualSize(context: Context): Long =
        find(context).sumOf { it.sizeBytes }

    fun delete(path: String): Boolean {
        val dir = File(path)
        if (!dir.absolutePath.contains("Android/")) return false // Safety
        return dir.deleteRecursively()
    }

    private fun calculateSize(dir: File): Long {
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
