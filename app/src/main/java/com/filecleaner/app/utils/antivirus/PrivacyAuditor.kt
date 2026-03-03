package com.filecleaner.app.utils.antivirus

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Privacy auditor (inspired by Seraphimdroid).
 * Checks installed apps for dangerous permissions and privacy concerns:
 * - Apps with excessive dangerous permissions
 * - Apps that can read SMS, contacts, call logs
 * - Apps with camera/microphone access without obvious need
 * - Apps accessing location in background
 */
object PrivacyAuditor {

    /** Dangerous permissions grouped by concern */
    private val PRIVACY_PERMISSIONS = mapOf(
        "SMS Access" to listOf(
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS"
        ),
        "Call Log Access" to listOf(
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS"
        ),
        "Contacts Access" to listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.GET_ACCOUNTS"
        ),
        "Camera Access" to listOf(
            "android.permission.CAMERA"
        ),
        "Microphone Access" to listOf(
            "android.permission.RECORD_AUDIO"
        ),
        "Location Access" to listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        ),
        "Phone Access" to listOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_NUMBERS"
        ),
        "Storage Access" to listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
        )
    )

    /** Threshold: apps with this many dangerous permission groups are flagged */
    private const val EXCESSIVE_PERMISSION_THRESHOLD = 5

    @Suppress("DEPRECATION")
    suspend fun audit(context: Context, onProgress: (Int) -> Unit): List<ThreatResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<ThreatResult>()
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val total = packages.size

            for ((index, pkg) in packages.withIndex()) {
                if (index % 10 == 0) onProgress((index * 100) / total.coerceAtLeast(1))

                val appInfo = pkg.applicationInfo ?: continue
                // Skip system apps
                if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue

                val appName = pm.getApplicationLabel(appInfo).toString()
                val requestedPerms = pkg.requestedPermissions ?: continue

                // Check each privacy category
                val matchedCategories = mutableListOf<String>()
                for ((category, permissions) in PRIVACY_PERMISSIONS) {
                    if (permissions.any { it in requestedPerms }) {
                        matchedCategories.add(category)
                    }
                }

                // Flag apps with excessive permissions
                if (matchedCategories.size >= EXCESSIVE_PERMISSION_THRESHOLD) {
                    results.add(ThreatResult(
                        name = "Excessive Permissions",
                        description = "\"$appName\" requests ${matchedCategories.size} dangerous permission categories: " +
                                matchedCategories.joinToString(", ") + ".",
                        severity = ThreatResult.Severity.HIGH,
                        source = ThreatResult.ScannerSource.PRIVACY_AUDIT,
                        packageName = pkg.packageName,
                        action = ThreatResult.ThreatAction.UNINSTALL
                    ))
                }

                // Flag SMS access (very few legitimate apps need this)
                if ("SMS Access" in matchedCategories && !isLikelySmsApp(pkg.packageName)) {
                    results.add(ThreatResult(
                        name = "SMS Access",
                        description = "\"$appName\" can read/send SMS. This may be a privacy risk.",
                        severity = ThreatResult.Severity.MEDIUM,
                        source = ThreatResult.ScannerSource.PRIVACY_AUDIT,
                        packageName = pkg.packageName
                    ))
                }

                // Flag background location (very few apps need this)
                if ("android.permission.ACCESS_BACKGROUND_LOCATION" in requestedPerms) {
                    results.add(ThreatResult(
                        name = "Background Location",
                        description = "\"$appName\" can track location in the background.",
                        severity = ThreatResult.Severity.MEDIUM,
                        source = ThreatResult.ScannerSource.PRIVACY_AUDIT,
                        packageName = pkg.packageName
                    ))
                }

                // Flag call log access
                if ("Call Log Access" in matchedCategories && !isLikelyPhoneApp(pkg.packageName)) {
                    results.add(ThreatResult(
                        name = "Call Log Access",
                        description = "\"$appName\" can read call logs.",
                        severity = ThreatResult.Severity.LOW,
                        source = ThreatResult.ScannerSource.PRIVACY_AUDIT,
                        packageName = pkg.packageName
                    ))
                }
            }

            onProgress(100)
            results
        }

    private fun isLikelySmsApp(packageName: String): Boolean {
        return packageName.contains("sms", ignoreCase = true) ||
                packageName.contains("message", ignoreCase = true) ||
                packageName.contains("mms", ignoreCase = true)
    }

    private fun isLikelyPhoneApp(packageName: String): Boolean {
        return packageName.contains("phone", ignoreCase = true) ||
                packageName.contains("dialer", ignoreCase = true) ||
                packageName.contains("call", ignoreCase = true)
    }
}
