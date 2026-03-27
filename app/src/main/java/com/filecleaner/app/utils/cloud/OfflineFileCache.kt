package com.filecleaner.app.utils.cloud

import android.content.Context
import java.io.File

/**
 * Caches recently viewed cloud files locally for offline access.
 * Uses LRU eviction when cache exceeds [MAX_CACHE_MB].
 */
object OfflineFileCache {

    private const val CACHE_DIR = "cloud_cache"
    private const val MAX_CACHE_MB = 100L
    private const val MAX_CACHE_BYTES = MAX_CACHE_MB * 1024 * 1024

    data class CachedFile(
        val remotePath: String,
        val connectionId: String,
        val localPath: String,
        val size: Long,
        val cachedAt: Long
    )

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Get a cached file if it exists and is still valid. */
    fun getCached(context: Context, connectionId: String, remotePath: String): File? {
        val key = cacheKey(connectionId, remotePath)
        val file = File(cacheDir(context), key)
        return if (file.exists()) file else null
    }

    /** Cache a file after downloading. Returns the cache file path. */
    fun cache(context: Context, connectionId: String, remotePath: String, data: ByteArray): File {
        evictIfNeeded(context, data.size.toLong())
        val key = cacheKey(connectionId, remotePath)
        val file = File(cacheDir(context), key)
        file.writeBytes(data)
        return file
    }

    /** List all cached files. */
    fun listCached(context: Context): List<CachedFile> {
        val dir = cacheDir(context)
        return dir.listFiles()?.map { file ->
            CachedFile(
                remotePath = file.name,
                connectionId = "",
                localPath = file.absolutePath,
                size = file.length(),
                cachedAt = file.lastModified()
            )
        }?.sortedByDescending { it.cachedAt } ?: emptyList()
    }

    /** Total cache size in bytes. */
    fun totalSize(context: Context): Long =
        cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0

    /** Clear all cached files. */
    fun clearAll(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Evict oldest files to make room for [neededBytes]. */
    private fun evictIfNeeded(context: Context, neededBytes: Long) {
        val dir = cacheDir(context)
        var currentSize = dir.listFiles()?.sumOf { it.length() } ?: 0

        if (currentSize + neededBytes <= MAX_CACHE_BYTES) return

        // Delete oldest files first
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (file in files) {
            if (currentSize + neededBytes <= MAX_CACHE_BYTES) break
            currentSize -= file.length()
            file.delete()
        }
    }

    private fun cacheKey(connectionId: String, remotePath: String): String {
        val raw = "$connectionId:$remotePath"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
