package com.filecleaner.app.utils.security

import android.content.Context
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import java.io.File

/**
 * Secure folder — a hidden, encrypted storage area for private files.
 *
 * Files are moved into the app's private directory (noBackupFilesDir)
 * and encrypted with AES-256 via FileEncryptor. The original file is
 * securely deleted after encryption.
 *
 * Premium feature — requires active subscription.
 */
object SecureFolder {

    private const val SECURE_DIR = "secure_vault"
    private const val MANIFEST_FILE = "manifest.txt"

    private fun secureDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, SECURE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun manifestFile(context: Context): File =
        File(secureDir(context), MANIFEST_FILE)

    /** Move a file into the secure folder (encrypt + delete original). */
    suspend fun addFile(context: Context, filePath: String, password: String): Boolean {
        val src = File(filePath)
        if (!src.exists()) return false

        val destDir = secureDir(context)
        val destFile = File(destDir, src.name)

        // Copy to secure dir first
        src.copyTo(destFile, overwrite = true)

        // Encrypt in place
        val result = FileEncryptor.encrypt(destFile.absolutePath, password)
        if (!result.success) {
            destFile.delete()
            return false
        }

        // Delete the unencrypted copy in secure dir
        destFile.delete()

        // Record original path in manifest for restore
        manifestFile(context).appendText("${src.name}|${filePath}\n")

        // Securely delete original
        FileShredder.shred(filePath)

        return true
    }

    /** List all files in the secure folder. */
    fun listFiles(context: Context): List<FileItem> {
        val dir = secureDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.name != MANIFEST_FILE }
            ?.map { file ->
                FileItem(
                    path = file.absolutePath,
                    name = file.name.removeSuffix(".encrypted"),
                    size = file.length(),
                    lastModified = file.lastModified(),
                    category = FileCategory.fromExtension(
                        file.name.removeSuffix(".encrypted")
                            .substringAfterLast('.', "").lowercase()
                    )
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    /** Restore a file from secure folder to its original location. */
    suspend fun restoreFile(context: Context, fileName: String, password: String): Boolean {
        val encFile = File(secureDir(context), "$fileName.encrypted")
        if (!encFile.exists()) return false

        val result = FileEncryptor.decrypt(encFile.absolutePath, password)
        if (!result.success) return false

        // Find original path from manifest
        val originalPath = manifestFile(context).readLines()
            .firstOrNull { it.startsWith("$fileName|") }
            ?.substringAfter("|")

        if (originalPath != null) {
            val dest = File(originalPath)
            dest.parentFile?.mkdirs()
            File(result.outputPath).renameTo(dest)
        }

        encFile.delete()
        return true
    }

    /** Returns total size of secure folder. */
    fun totalSize(context: Context): Long {
        val dir = secureDir(context)
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    /** Returns number of files in secure folder. */
    fun fileCount(context: Context): Int {
        val dir = secureDir(context)
        return (dir.listFiles()?.size ?: 1) - 1 // Exclude manifest
    }
}
