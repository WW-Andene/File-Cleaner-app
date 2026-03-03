package com.filecleaner.app.utils.antivirus

import com.filecleaner.app.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * File signature scanner (inspired by Hypatia / ClamAV).
 * Scans files against known malware signatures using:
 * - File hash matching (MD5/SHA256)
 * - Filename pattern matching for known malware names
 * - Suspicious file characteristics (e.g., APKs in unusual locations)
 *
 * Note: In production, signatures would be loaded from an updatable
 * database (ClamAV signatures, YARA rules). This implementation uses
 * a built-in signature set for demonstration.
 */
object SignatureScanner {

    /** Known malware file hashes (MD5). In production, load from DB. */
    private val KNOWN_MALWARE_HASHES = setOf(
        "d41d8cd98f00b204e9800998ecf8427e" // Example: empty file (placeholder)
    )

    /** Suspicious filename patterns (regex) */
    private val SUSPICIOUS_PATTERNS = listOf(
        Regex(".*\\.apk\\..*", RegexOption.IGNORE_CASE), // Double extension APK
        Regex(".*payload.*\\.apk", RegexOption.IGNORE_CASE), // Payload APKs
        Regex(".*keylog.*", RegexOption.IGNORE_CASE), // Keyloggers
        Regex(".*trojan.*", RegexOption.IGNORE_CASE), // Trojan indicators
        Regex(".*rat_.*", RegexOption.IGNORE_CASE), // RAT (Remote Access Trojan)
        Regex(".*\\.exe", RegexOption.IGNORE_CASE), // Windows executables on Android
        Regex(".*\\.bat", RegexOption.IGNORE_CASE), // Batch files
        Regex(".*\\.cmd", RegexOption.IGNORE_CASE), // Command files
        Regex(".*\\.scr", RegexOption.IGNORE_CASE), // Screen saver (malware vector)
    )

    /** Suspicious APK locations (outside standard install paths) */
    private val SUSPICIOUS_APK_DIRS = listOf(
        "/Download/", "/WhatsApp/", "/Telegram/",
        "/DCIM/", "/Pictures/", "/Music/"
    )

    suspend fun scan(
        files: List<FileItem>,
        onProgress: (scanned: Int, total: Int) -> Unit
    ): List<ThreatResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ThreatResult>()
        val total = files.size

        for ((index, item) in files.withIndex()) {
            if (index % 100 == 0) onProgress(index, total)

            // Check filename patterns
            for (pattern in SUSPICIOUS_PATTERNS) {
                if (pattern.matches(item.name)) {
                    results.add(ThreatResult(
                        name = "Suspicious Filename",
                        description = "File \"${item.name}\" matches suspicious pattern.",
                        severity = ThreatResult.Severity.MEDIUM,
                        source = ThreatResult.ScannerSource.FILE_SIGNATURE,
                        filePath = item.path,
                        action = ThreatResult.ThreatAction.QUARANTINE
                    ))
                    break
                }
            }

            // Check APKs in suspicious locations
            if (item.extension == "apk") {
                for (dir in SUSPICIOUS_APK_DIRS) {
                    if (item.path.contains(dir)) {
                        results.add(ThreatResult(
                            name = "APK in Unusual Location",
                            description = "APK file found in ${item.path.substringBeforeLast('/')}. " +
                                    "APKs outside standard install locations may be side-loaded malware.",
                            severity = ThreatResult.Severity.LOW,
                            source = ThreatResult.ScannerSource.FILE_SIGNATURE,
                            filePath = item.path,
                            action = ThreatResult.ThreatAction.QUARANTINE
                        ))
                        break
                    }
                }
            }

            // Hash check for small files (< 10MB to avoid slow I/O)
            if (item.size in 1..10_485_760) {
                try {
                    val hash = md5Hash(File(item.path))
                    if (hash in KNOWN_MALWARE_HASHES) {
                        results.add(ThreatResult(
                            name = "Known Malware Detected",
                            description = "File \"${item.name}\" matches known malware signature (MD5: $hash).",
                            severity = ThreatResult.Severity.CRITICAL,
                            source = ThreatResult.ScannerSource.FILE_SIGNATURE,
                            filePath = item.path,
                            action = ThreatResult.ThreatAction.DELETE
                        ))
                    }
                } catch (_: Exception) {
                    // File may be unreadable
                }
            }
        }

        onProgress(total, total)
        results
    }

    private fun md5Hash(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
