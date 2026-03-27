package com.filecleaner.app.utils.security

import com.filecleaner.app.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans files for potentially sensitive content — passwords, API keys,
 * private keys, personal data. Alerts users so they can secure or
 * delete these files.
 */
object SensitiveFileScanner {

    data class SensitiveFile(
        val item: FileItem,
        val reason: String,
        val severity: Severity
    )

    enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

    /** Filename patterns that indicate sensitive content. */
    private val SENSITIVE_FILENAMES = listOf(
        "password" to Severity.CRITICAL,
        "passwd" to Severity.CRITICAL,
        ".env" to Severity.HIGH,
        "credentials" to Severity.CRITICAL,
        "secret" to Severity.HIGH,
        "private_key" to Severity.CRITICAL,
        "id_rsa" to Severity.CRITICAL,
        "id_ed25519" to Severity.CRITICAL,
        "keystore" to Severity.HIGH,
        ".pem" to Severity.HIGH,
        ".p12" to Severity.HIGH,
        ".pfx" to Severity.HIGH,
        "token" to Severity.MEDIUM,
        "api_key" to Severity.HIGH,
        "apikey" to Severity.HIGH,
        "auth" to Severity.MEDIUM,
        "wallet" to Severity.HIGH,
        "seed_phrase" to Severity.CRITICAL,
        "recovery" to Severity.MEDIUM,
        "backup_codes" to Severity.CRITICAL,
        "tax" to Severity.MEDIUM,
        "ssn" to Severity.HIGH,
        "passport" to Severity.MEDIUM,
        "license" to Severity.LOW,
        "bank" to Severity.MEDIUM,
        "credit_card" to Severity.HIGH
    )

    /** Content patterns to search for in text files. */
    private val CONTENT_PATTERNS = listOf(
        Regex("(?i)(password|passwd|pwd)\\s*[:=]\\s*\\S+") to "Password in plaintext" to Severity.CRITICAL,
        Regex("(?i)api[_-]?key\\s*[:=]\\s*['\"]?[A-Za-z0-9_-]{20,}") to "API key exposed" to Severity.HIGH,
        Regex("-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----") to "Private key file" to Severity.CRITICAL,
        Regex("(?i)bearer\\s+[A-Za-z0-9_.-]{20,}") to "Bearer token exposed" to Severity.HIGH,
        Regex("ghp_[A-Za-z0-9]{36}") to "GitHub personal access token" to Severity.CRITICAL,
        Regex("sk-[A-Za-z0-9]{40,}") to "OpenAI API key" to Severity.CRITICAL,
        Regex("AKIA[A-Z0-9]{16}") to "AWS access key" to Severity.CRITICAL
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "csv", "log", "env", "cfg", "conf", "ini", "json", "yml",
        "yaml", "xml", "properties", "sh", "bat", "py", "js", "kt", "java"
    )

    /**
     * Scan files for sensitive content.
     * Phase 1: Filename matching (fast, all files)
     * Phase 2: Content scanning (slower, text files only, first 10 KB)
     */
    suspend fun scan(
        files: List<FileItem>,
        scanContent: Boolean = true,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<SensitiveFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SensitiveFile>()

        // Phase 1: Filename scan
        for (item in files) {
            val nameLower = item.name.lowercase()
            for ((pattern, severity) in SENSITIVE_FILENAMES) {
                if (nameLower.contains(pattern)) {
                    results.add(SensitiveFile(item, "Filename contains \"$pattern\"", severity))
                    break
                }
            }
        }

        // Phase 2: Content scan (text files only)
        if (scanContent) {
            val textFiles = files.filter { it.extension in TEXT_EXTENSIONS }
            for ((index, item) in textFiles.withIndex()) {
                ensureActive()
                if (index % 50 == 0) onProgress?.invoke(index, textFiles.size)

                try {
                    val file = File(item.path)
                    if (!file.exists() || file.length() > 100_000) continue // Skip large files

                    val content = file.readText(Charsets.UTF_8).take(10_000)
                    for ((pair, severity) in CONTENT_PATTERNS) {
                        val (regex, reason) = pair
                        if (regex.containsMatchIn(content)) {
                            results.add(SensitiveFile(item, reason, severity))
                            break
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        results.sortedByDescending { it.severity.ordinal }
    }
}
