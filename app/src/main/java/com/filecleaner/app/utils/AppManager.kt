package com.filecleaner.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Utility for listing installed apps with their storage usage,
 * and providing uninstall/app-info actions.
 *
 * Focuses on user-installed apps (excludes system apps by default)
 * and sorts by estimated storage size for quick space recovery.
 */
object AppManager {

    data class InstalledApp(
        val packageName: String,
        val name: String,
        val sizeBytes: Long,
        val isSystemApp: Boolean,
        val installedAt: Long,
        val lastUpdated: Long
    )

    /**
     * Lists all installed apps sorted by estimated size (largest first).
     * @param includeSystem Whether to include system apps
     */
    fun getInstalledApps(context: Context, includeSystem: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        return packages
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { appInfo ->
                val pkgInfo = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(appInfo.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(appInfo.packageName, 0)
                    }
                } catch (_: Exception) { null }

                // Estimate size from APK file + data directory
                val apkSize = try {
                    java.io.File(appInfo.sourceDir).length()
                } catch (_: Exception) { 0L }

                val dataSize = try {
                    appInfo.dataDir?.let { calculateDirSize(java.io.File(it)) } ?: 0L
                } catch (_: Exception) { 0L }

                InstalledApp(
                    packageName = appInfo.packageName,
                    name = pm.getApplicationLabel(appInfo).toString(),
                    sizeBytes = apkSize + dataSize,
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    installedAt = pkgInfo?.firstInstallTime ?: 0,
                    lastUpdated = pkgInfo?.lastUpdateTime ?: 0
                )
            }
            .sortedByDescending { it.sizeBytes }
    }

    /** Opens the system app info screen for uninstall/clear data. */
    fun openAppInfo(context: Context, packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Triggers the system uninstall dialog. */
    fun requestUninstall(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Returns summary stats for display. */
    fun getSummary(context: Context): AppSummary {
        val apps = getInstalledApps(context)
        return AppSummary(
            totalApps = apps.size,
            totalSize = apps.sumOf { it.sizeBytes },
            largestApp = apps.firstOrNull()
        )
    }

    data class AppSummary(
        val totalApps: Int,
        val totalSize: Long,
        val largestApp: InstalledApp?
    )

    private fun calculateDirSize(dir: java.io.File): Long {
        if (!dir.isDirectory) return 0
        var size = 0L
        val children = dir.listFiles() ?: return 0
        for (child in children) {
            size += if (child.isFile) child.length() else calculateDirSize(child)
        }
        return size
    }
}
