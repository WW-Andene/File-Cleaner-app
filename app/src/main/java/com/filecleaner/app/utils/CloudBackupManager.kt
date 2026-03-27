package com.filecleaner.app.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.filecleaner.app.data.UserPreferences
import com.filecleaner.app.data.cloud.CloudConnectionStore
import com.filecleaner.app.data.cloud.CloudProvider
import com.filecleaner.app.data.cloud.ProviderType
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Automated cloud backup manager. Backs up user-selected folders to
 * a configured cloud provider on a schedule.
 *
 * Premium feature — requires active subscription.
 */
object CloudBackupManager {

    private const val WORK_NAME = "cloud_backup"
    private const val PREFS_NAME = "cloud_backup"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Schedule periodic backup. */
    fun schedule(context: Context, intervalHours: Long = 24) {
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(
            intervalHours, TimeUnit.HOURS,
            1, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Get/set the cloud connection ID to use for backup. */
    var backupConnectionId: String
        get() = ""  // Read from prefs at call site
        set(_) {}   // Set from prefs at call site

    fun getBackupConnectionId(context: Context): String =
        prefs(context).getString("connection_id", "") ?: ""

    fun setBackupConnectionId(context: Context, connectionId: String) {
        prefs(context).edit().putString("connection_id", connectionId).apply()
    }

    /** Get/set folders to back up. */
    fun getBackupFolders(context: Context): Set<String> =
        prefs(context).getStringSet("folders", emptySet()) ?: emptySet()

    fun setBackupFolders(context: Context, folders: Set<String>) {
        prefs(context).edit().putStringSet("folders", folders).apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getBackupConnectionId(context).isNotEmpty() &&
            getBackupFolders(context).isNotEmpty()
    }

    class CloudBackupWorker(
        context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val ctx = applicationContext
            try {
                UserPreferences.init(ctx)
                CloudConnectionStore.init(ctx)

                val connectionId = getBackupConnectionId(ctx)
                if (connectionId.isEmpty()) return Result.success()

                val connection = CloudConnectionStore.getConnections()
                    .find { it.id == connectionId } ?: return Result.failure()

                val folders = getBackupFolders(ctx)
                if (folders.isEmpty()) return Result.success()

                // Create provider and connect
                val provider = when (connection.type) {
                    com.filecleaner.app.data.cloud.ProviderType.SFTP -> com.filecleaner.app.data.cloud.SftpProvider(connection, ctx)
                    com.filecleaner.app.data.cloud.ProviderType.WEBDAV -> com.filecleaner.app.data.cloud.WebDavProvider(connection)
                    com.filecleaner.app.data.cloud.ProviderType.GOOGLE_DRIVE -> com.filecleaner.app.data.cloud.GoogleDriveProvider(connection, ctx)
                    com.filecleaner.app.data.cloud.ProviderType.GITHUB -> com.filecleaner.app.data.cloud.GitHubProvider(connection, ctx)
                }
                if (!provider.connect()) return Result.retry()

                try {
                    for (folderPath in folders) {
                        val folder = File(folderPath)
                        if (!folder.isDirectory) continue

                        val remotePath = "/RaccoonBackup/${folder.name}"
                        try { provider.createDirectory(remotePath) } catch (_: Exception) {}

                        val files = folder.listFiles()?.filter { it.isFile } ?: continue
                        for (file in files.take(100)) { // Cap per-run to prevent timeout
                            try {
                                file.inputStream().use { input ->
                                    provider.upload(
                                        "$remotePath/${file.name}",
                                        input,
                                        file.name,
                                        "application/octet-stream"
                                    )
                                }
                            } catch (_: Exception) {
                                // Skip failed files, continue with rest
                            }
                        }
                    }
                } finally {
                    provider.disconnect()
                }

                return Result.success()
            } catch (_: Exception) {
                return Result.retry()
            }
        }
    }
}
