package com.filecleaner.app.utils.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Computes cryptographic hashes (MD5, SHA-1, SHA-256) for file integrity
 * verification. Users can compare hashes against published checksums
 * to verify downloaded files haven't been tampered with.
 */
object FileHasher {

    enum class Algorithm(val label: String, val javaName: String) {
        MD5("MD5", "MD5"),
        SHA1("SHA-1", "SHA-1"),
        SHA256("SHA-256", "SHA-256")
    }

    data class HashResult(
        val algorithm: Algorithm,
        val hash: String,
        val filePath: String,
        val fileSize: Long,
        val durationMs: Long
    )

    /**
     * Computes the hash of a file using the specified algorithm.
     * Reports progress periodically for large files.
     */
    suspend fun computeHash(
        filePath: String,
        algorithm: Algorithm = Algorithm.SHA256,
        onProgress: ((Long, Long) -> Unit)? = null
    ): HashResult = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val fileSize = file.length()
        val startMs = System.currentTimeMillis()

        val digest = MessageDigest.getInstance(algorithm.javaName)
        val buffer = ByteArray(8192)
        var totalRead = 0L

        file.inputStream().buffered().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                ensureActive()
                digest.update(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalRead % (1024 * 1024) == 0L) { // Report every 1MB
                    onProgress?.invoke(totalRead, fileSize)
                }
            }
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val durationMs = System.currentTimeMillis() - startMs

        HashResult(algorithm, hash, filePath, fileSize, durationMs)
    }

    /**
     * Verifies a file's hash matches an expected value.
     */
    suspend fun verify(
        filePath: String,
        expectedHash: String,
        algorithm: Algorithm = Algorithm.SHA256
    ): Boolean {
        val result = computeHash(filePath, algorithm)
        return result.hash.equals(expectedHash, ignoreCase = true)
    }
}
