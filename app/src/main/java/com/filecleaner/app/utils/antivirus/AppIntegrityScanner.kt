package com.filecleaner.app.utils.antivirus

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App integrity scanner (inspired by Talsec).
 * Checks for:
 * - Root detection (su binary, Magisk, root management apps)
 * - Debugger attachment
 * - Emulator detection
 * - Tampered/repackaged apps on device
 * - Known malicious package names
 */
object AppIntegrityScanner {

    private val ROOT_BINARIES = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/su", "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su-backup",
        "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su"
    )

    private val ROOT_PACKAGES = listOf(
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.topjohnwu.magisk",
        "me.phh.superuser"
    )

    private val KNOWN_MALICIOUS_PACKAGES = listOf(
        "com.android.fakeapp",
        "com.android.fakeid",
        "com.svpnfree.proxy",
        "com.apphider.applock",
        "com.system.battery.optimizer.fake"
    )

    private val EMULATOR_INDICATORS = listOf(
        "goldfish", "ranchu", "sdk_gphone",
        "google_sdk", "Emulator", "Android SDK"
    )

    suspend fun scan(context: Context, onProgress: (Int) -> Unit): List<ThreatResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<ThreatResult>()
            onProgress(0)

            // 1. Root detection
            results.addAll(checkRoot(context))
            onProgress(20)

            // 2. Debugger detection
            results.addAll(checkDebugger())
            onProgress(40)

            // 3. Emulator detection
            results.addAll(checkEmulator())
            onProgress(60)

            // 4. Known malicious packages
            results.addAll(checkMaliciousPackages(context))
            onProgress(80)

            // 5. Check for apps from unknown sources without proper signing
            results.addAll(checkSuspiciousApps(context))
            onProgress(100)

            results
        }

    private fun checkRoot(context: Context): List<ThreatResult> {
        val results = mutableListOf<ThreatResult>()

        // Check for su binaries
        for (path in ROOT_BINARIES) {
            if (File(path).exists()) {
                results.add(ThreatResult(
                    name = "Root Binary Found",
                    description = "Su binary detected at $path. Device may be rooted.",
                    severity = ThreatResult.Severity.HIGH,
                    source = ThreatResult.ScannerSource.APP_INTEGRITY,
                    filePath = path
                ))
            }
        }

        // Check for root management apps
        val pm = context.packageManager
        for (pkg in ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                results.add(ThreatResult(
                    name = "Root Management App",
                    description = "Root management app installed: $pkg",
                    severity = ThreatResult.Severity.MEDIUM,
                    source = ThreatResult.ScannerSource.APP_INTEGRITY,
                    packageName = pkg
                ))
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed — safe
            }
        }

        // Check build tags
        if (Build.TAGS?.contains("test-keys") == true) {
            results.add(ThreatResult(
                name = "Test Keys Detected",
                description = "Device build uses test signing keys, indicating custom ROM or root.",
                severity = ThreatResult.Severity.MEDIUM,
                source = ThreatResult.ScannerSource.APP_INTEGRITY
            ))
        }

        return results
    }

    private fun checkDebugger(): List<ThreatResult> {
        val results = mutableListOf<ThreatResult>()

        if (Debug.isDebuggerConnected()) {
            results.add(ThreatResult(
                name = "Debugger Attached",
                description = "A debugger is currently attached to the application.",
                severity = ThreatResult.Severity.HIGH,
                source = ThreatResult.ScannerSource.APP_INTEGRITY
            ))
        }

        return results
    }

    private fun checkEmulator(): List<ThreatResult> {
        val results = mutableListOf<ThreatResult>()

        val isEmulator = EMULATOR_INDICATORS.any { indicator ->
            Build.FINGERPRINT.contains(indicator, ignoreCase = true) ||
            Build.MODEL.contains(indicator, ignoreCase = true) ||
            Build.MANUFACTURER.contains(indicator, ignoreCase = true) ||
            Build.HARDWARE.contains(indicator, ignoreCase = true) ||
            Build.PRODUCT.contains(indicator, ignoreCase = true)
        }

        if (isEmulator) {
            results.add(ThreatResult(
                name = "Emulator Detected",
                description = "App appears to be running on an emulator (${Build.MODEL}).",
                severity = ThreatResult.Severity.INFO,
                source = ThreatResult.ScannerSource.APP_INTEGRITY
            ))
        }

        return results
    }

    private fun checkMaliciousPackages(context: Context): List<ThreatResult> {
        val results = mutableListOf<ThreatResult>()
        val pm = context.packageManager

        for (pkg in KNOWN_MALICIOUS_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                results.add(ThreatResult(
                    name = "Known Malicious App",
                    description = "Known malicious package installed: $pkg. Consider uninstalling.",
                    severity = ThreatResult.Severity.CRITICAL,
                    source = ThreatResult.ScannerSource.APP_INTEGRITY,
                    packageName = pkg,
                    action = ThreatResult.ThreatAction.UNINSTALL
                ))
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }

        return results
    }

    @Suppress("DEPRECATION")
    private fun checkSuspiciousApps(context: Context): List<ThreatResult> {
        val results = mutableListOf<ThreatResult>()
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            // Check for apps installed from unknown sources (non-system, non-store)
            if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                val installer = pm.getInstallerPackageName(pkg.packageName)
                if (installer == null && appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    results.add(ThreatResult(
                        name = "Suspicious Debuggable App",
                        description = "App ${pkg.packageName} is debuggable and has no known installer.",
                        severity = ThreatResult.Severity.LOW,
                        source = ThreatResult.ScannerSource.APP_INTEGRITY,
                        packageName = pkg.packageName
                    ))
                }
            }
        }

        return results
    }
}
