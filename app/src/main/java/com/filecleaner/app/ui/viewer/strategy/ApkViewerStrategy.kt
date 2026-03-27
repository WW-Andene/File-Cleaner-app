package com.filecleaner.app.ui.viewer.strategy

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.view.View
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.utils.UndoHelper
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Inspects APK files: app details, permissions, install status,
 * file contents tree. Provides install action.
 */
class ApkViewerStrategy : ViewerStrategy {

    override fun canHandle(extension: String, category: FileCategory): Boolean =
        extension == "apk"

    override fun show(file: File, rootView: View, savedInstanceState: android.os.Bundle?) {
        // APK viewer uses the text editor container for display
        // The actual rendering is still delegated to FileViewerFragment.showApkInfo()
        // because it requires binding access to codeToolbar buttons.
        // This strategy exists for the registry — full extraction requires
        // a dedicated APK viewer layout.
    }

    /** Generate APK analysis text. Can be used independently of the fragment. */
    fun analyze(file: File, pm: PackageManager): String {
        val apkInfo = pm.getPackageArchiveInfo(file.absolutePath,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_META_DATA
        ) ?: return "Cannot parse this APK file."

        val appInfo = apkInfo.applicationInfo
        appInfo?.sourceDir = file.absolutePath
        appInfo?.publicSourceDir = file.absolutePath

        val appName = try { appInfo?.let { pm.getApplicationLabel(it).toString() } ?: "Unknown" } catch (_: Exception) { "Unknown" }
        val versionName = apkInfo.versionName ?: "Unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            apkInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            apkInfo.versionCode.toString()
        }
        val packageName = apkInfo.packageName ?: "Unknown"
        val minSdk = appInfo?.minSdkVersion ?: 0
        val targetSdk = appInfo?.targetSdkVersion ?: 0

        val installedVersion = try {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            installed.versionName ?: "Unknown"
        } catch (_: Exception) { null }

        val permissions = apkInfo.requestedPermissions ?: emptyArray()
        val dangerousPerms = permissions.filter { perm ->
            try {
                val permInfo = pm.getPermissionInfo(perm, 0)
                permInfo.protection == PermissionInfo.PROTECTION_DANGEROUS
            } catch (_: Exception) { false }
        }

        return buildString {
            appendLine("══════════════════════════════")
            appendLine("  APK DETAILS")
            appendLine("══════════════════════════════")
            appendLine()
            appendLine("App Name:      $appName")
            appendLine("Package:       $packageName")
            appendLine("Version:       $versionName ($versionCode)")
            appendLine("Min SDK:       $minSdk (Android ${sdkToVersion(minSdk)})")
            appendLine("Target SDK:    $targetSdk (Android ${sdkToVersion(targetSdk)})")
            appendLine("File Size:     ${UndoHelper.formatBytes(file.length())}")
            appendLine()
            if (installedVersion != null) {
                appendLine("⚠ Already installed: v$installedVersion")
                if (installedVersion != versionName) {
                    appendLine("  APK is ${if (versionName > installedVersion) "NEWER" else "OLDER"} than installed")
                }
                appendLine()
            }
            appendLine("══════════════════════════════")
            appendLine("  PERMISSIONS (${permissions.size})")
            appendLine("══════════════════════════════")
            appendLine()
            if (dangerousPerms.isNotEmpty()) {
                appendLine("⚠ Dangerous permissions (${dangerousPerms.size}):")
                for (perm in dangerousPerms) {
                    appendLine("  • ${perm.substringAfterLast('.')}")
                }
                appendLine()
            }
            appendLine("All permissions:")
            for (perm in permissions.sorted()) {
                val short = perm.substringAfterLast('.')
                val isDangerous = perm in dangerousPerms
                appendLine("  ${if (isDangerous) "⚠" else "•"} $short")
            }
            val activities = apkInfo.activities?.size ?: 0
            if (activities > 0) {
                appendLine()
                appendLine("Activities: $activities")
            }
        }
    }

    /** List APK contents (ZIP entries). */
    fun listContents(file: File): String {
        return try {
            val entries = mutableListOf<Pair<String, Long>>()
            ZipInputStream(file.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries.add(entry.name to entry.size)
                    }
                    entry = zis.nextEntry
                }
            }
            val grouped = entries.groupBy { it.first.substringBefore('/') }
            buildString {
                appendLine()
                appendLine("══════════════════════════════")
                appendLine("  APK CONTENTS (${entries.size} files)")
                appendLine("══════════════════════════════")
                appendLine()
                for ((folder, files) in grouped.toSortedMap()) {
                    val folderSize = files.sumOf { it.second }
                    appendLine("📁 $folder/ (${UndoHelper.formatBytes(folderSize)})")
                    for ((idx, f) in files.sortedBy { it.first }.withIndex()) {
                        if (idx >= 10) {
                            appendLine("     ... and ${files.size - 10} more files")
                            break
                        }
                        appendLine("   📄 ${f.first.substringAfter('/')} (${UndoHelper.formatBytes(f.second)})")
                    }
                }
                appendLine()
                appendLine("Total: ${entries.size} files, ${UndoHelper.formatBytes(entries.sumOf { it.second })}")
            }
        } catch (e: Exception) {
            "\n\nCannot read APK contents: ${e.localizedMessage}"
        }
    }

    /** Extract a single file from the APK to outputDir. Returns the extracted File or null. */
    fun extractFile(apkFile: File, entryName: String, outputDir: File): File? {
        return try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                val outFile = File(outputDir, entryName.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile
            }
        } catch (_: Exception) { null }
    }

    /** Extract all APK contents to outputDir. Returns the number of files extracted. */
    fun extractAll(apkFile: File, outputDir: File): Int {
        var count = 0
        try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val outFile = File(outputDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    count++
                }
            }
        } catch (_: Exception) { }
        return count
    }

    /** Get the list of entries in the APK for browsable display. */
    fun getEntries(apkFile: File): List<Pair<String, Long>> {
        val entries = mutableListOf<Pair<String, Long>>()
        try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.isDirectory) {
                        entries.add(entry.name to entry.size)
                    }
                }
            }
        } catch (_: Exception) { }
        return entries
    }

    private fun sdkToVersion(sdk: Int): String = when (sdk) {
        29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"
        33 -> "13"; 34 -> "14"; 35 -> "15"
        else -> if (sdk > 0) "API $sdk" else "?"
    }
}
