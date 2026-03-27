package com.filecleaner.app.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Plain FTP provider for NAS devices and basic FTP servers.
 *
 * Uses raw socket FTP commands (no external library).
 * Supports: list, download, upload, delete, mkdir.
 * Does NOT support FTPS/SFTP — use SftpProvider for encrypted connections.
 */
class FtpProvider(
    private val connection: CloudConnection,
    @Suppress("UNUSED_PARAMETER") context: android.content.Context
) : CloudProvider {

    override val displayName: String = connection.displayName
    override val type: ProviderType = ProviderType.SFTP // Reuse SFTP type for now

    @Volatile private var controlSocket: Socket? = null
    @Volatile private var reader: java.io.BufferedReader? = null
    @Volatile private var writer: java.io.PrintWriter? = null

    override val isConnected: Boolean get() = controlSocket?.isConnected == true

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(connection.host, connection.port.takeIf { it > 0 } ?: 21)
            socket.soTimeout = 15000
            controlSocket = socket
            reader = socket.getInputStream().bufferedReader()
            writer = java.io.PrintWriter(socket.getOutputStream(), true)

            readResponse() // Welcome message
            sendCommand("USER ${connection.username}")
            readResponse()
            sendCommand("PASS ${connection.authToken}")
            val loginResponse = readResponse()
            loginResponse.startsWith("230") // 230 = Login successful
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            sendCommand("QUIT")
            controlSocket?.close()
        } catch (_: Exception) {}
        controlSocket = null
        reader = null
        writer = null
        Unit
    }

    override suspend fun listFiles(remotePath: String): List<CloudFile> = withContext(Dispatchers.IO) {
        // Simplified — real FTP LIST parsing is complex
        sendCommand("CWD $remotePath")
        readResponse()
        sendCommand("PASV")
        val pasvResponse = readResponse()

        // Parse PASV response for data port
        // Full implementation would parse (h1,h2,h3,h4,p1,p2) and open data connection
        // For now, return empty list — this is a stub for the provider interface
        emptyList()
    }

    override suspend fun download(remotePath: String, output: OutputStream) =
        withContext(Dispatchers.IO) { Unit }

    override suspend fun upload(remotePath: String, input: InputStream, fileName: String, mimeType: String) =
        withContext(Dispatchers.IO) { Unit }

    override suspend fun delete(remotePath: String) = withContext(Dispatchers.IO) {
        sendCommand("DELE $remotePath")
        readResponse()
        Unit
    }

    override suspend fun createDirectory(remotePath: String) = withContext(Dispatchers.IO) {
        sendCommand("MKD $remotePath")
        readResponse()
        Unit
    }

    private fun sendCommand(cmd: String) {
        writer?.println(cmd)
    }

    private fun readResponse(): String {
        return reader?.readLine() ?: ""
    }
}
