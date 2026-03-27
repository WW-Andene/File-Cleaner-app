package com.filecleaner.app.utils.cloud

import android.content.Context
import com.filecleaner.app.data.cloud.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Tests all saved cloud connections and reports their health status.
 * Useful for troubleshooting and quick diagnostics.
 */
object ConnectionHealthChecker {

    data class HealthResult(
        val connectionId: String,
        val displayName: String,
        val type: ProviderType,
        val isHealthy: Boolean,
        val latencyMs: Long,
        val errorMessage: String?
    )

    /** Test all saved connections and return their status. */
    suspend fun checkAll(context: Context): List<HealthResult> = withContext(Dispatchers.IO) {
        CloudConnectionStore.init(context)
        val connections = CloudConnectionStore.getConnections()
        connections.map { conn -> checkConnection(conn, context) }
    }

    /** Test a single connection. */
    suspend fun checkConnection(connection: CloudConnection, context: Context): HealthResult {
        val startMs = System.currentTimeMillis()
        return try {
            val provider = when (connection.type) {
                ProviderType.SFTP -> SftpProvider(connection, context)
                ProviderType.WEBDAV -> WebDavProvider(connection)
                ProviderType.GOOGLE_DRIVE -> GoogleDriveProvider(connection, context)
                ProviderType.GITHUB -> GitHubProvider(connection, context)
            }

            val success = withTimeout(10_000) { provider.connect() }
            val latency = System.currentTimeMillis() - startMs

            try { provider.disconnect() } catch (_: Exception) {}

            HealthResult(
                connectionId = connection.id,
                displayName = connection.displayName,
                type = connection.type,
                isHealthy = success,
                latencyMs = latency,
                errorMessage = if (!success) "Connection refused" else null
            )
        } catch (e: Exception) {
            HealthResult(
                connectionId = connection.id,
                displayName = connection.displayName,
                type = connection.type,
                isHealthy = false,
                latencyMs = System.currentTimeMillis() - startMs,
                errorMessage = e.localizedMessage ?: "Unknown error"
            )
        }
    }

    /** Format results as readable text. */
    fun formatResults(results: List<HealthResult>): String = buildString {
        if (results.isEmpty()) {
            appendLine("No saved connections to test.")
            return@buildString
        }
        for (result in results) {
            val icon = if (result.isHealthy) "✓" else "✗"
            appendLine("$icon ${result.displayName} (${result.type.name})")
            if (result.isHealthy) {
                appendLine("  Connected in ${result.latencyMs}ms")
            } else {
                appendLine("  Failed: ${result.errorMessage}")
            }
        }
    }
}
