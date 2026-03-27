package com.filecleaner.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Secure file deletion — overwrites file content with random data
 * before deleting, making forensic recovery extremely difficult.
 *
 * Uses 3-pass overwrite: random → zeros → random, then deletes.
 */
object FileShredder {

    /** Securely delete a file with 3-pass overwrite. */
    suspend fun shred(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return@withContext false

            val length = file.length()
            val random = SecureRandom()
            val buffer = ByteArray(8192)

            RandomAccessFile(file, "rw").use { raf ->
                // Pass 1: Random data
                raf.seek(0)
                var written = 0L
                while (written < length) {
                    random.nextBytes(buffer)
                    val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
                    raf.write(buffer, 0, toWrite)
                    written += toWrite
                }

                // Pass 2: Zeros
                raf.seek(0)
                buffer.fill(0)
                written = 0L
                while (written < length) {
                    val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
                    raf.write(buffer, 0, toWrite)
                    written += toWrite
                }

                // Pass 3: Random data again
                raf.seek(0)
                written = 0L
                while (written < length) {
                    random.nextBytes(buffer)
                    val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
                    raf.write(buffer, 0, toWrite)
                    written += toWrite
                }

                raf.fd.sync() // Force flush to disk
            }

            // Delete the file
            file.delete()
        } catch (_: Exception) {
            // Fallback: regular delete
            File(filePath).delete()
        }
    }

    /** Securely delete multiple files. Returns count of successfully shredded. */
    suspend fun shredAll(filePaths: List<String>): Int {
        var count = 0
        for (path in filePaths) {
            if (shred(path)) count++
        }
        return count
    }
}
