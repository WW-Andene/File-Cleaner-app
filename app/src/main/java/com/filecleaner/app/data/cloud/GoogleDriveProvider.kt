package com.filecleaner.app.data.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive provider using the REST API v3 with OAuth2 access token.
 * The access token must be obtained via the Google Sign-In flow
 * and stored in the connection's authToken field.
 *
 * For full OAuth integration, the app needs google-services.json and
 * Google Sign-In SDK. This provider works with any valid access token.
 */
class GoogleDriveProvider(
    private val connection: CloudConnection,
    @Suppress("unused") private val context: Context
) : CloudProvider {

    override val displayName: String = connection.displayName
    override val type: ProviderType = ProviderType.GOOGLE_DRIVE

    private var connected = false

    override val isConnected: Boolean get() = connected

    private val accessToken: String get() = connection.authToken

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Verify token by calling about endpoint
            val url = URL("https://www.googleapis.com/drive/v3/about?fields=user")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 15000
                readTimeout = 15000
            }
            connected = conn.responseCode == 200
            conn.disconnect()
            connected
        } catch (e: Exception) {
            connected = false
            false
        }
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun listFiles(remotePath: String): List<CloudFile> = withContext(Dispatchers.IO) {
        try {
            val folderId = if (remotePath == "/" || remotePath.isEmpty()) "root" else remotePath
            val query = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
            val fields = URLEncoder.encode(
                "files(id,name,mimeType,size,modifiedTime)", "UTF-8"
            )
            val url = URL(
                "https://www.googleapis.com/drive/v3/files?q=$query&fields=$fields&orderBy=folder,name&pageSize=1000"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 15000
                readTimeout = 15000
            }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext emptyList()
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(body)
            val filesArray = json.getJSONArray("files")
            val result = mutableListOf<CloudFile>()

            for (i in 0 until filesArray.length()) {
                val file = filesArray.getJSONObject(i)
                val mime = file.optString("mimeType", "")
                val isDir = mime == "application/vnd.google-apps.folder"
                result.add(CloudFile(
                    name = file.getString("name"),
                    remotePath = file.getString("id"),
                    isDirectory = isDir,
                    size = file.optLong("size", 0L),
                    lastModified = parseGoogleDate(file.optString("modifiedTime", "")),
                    mimeType = mime
                ))
            }

            result.sortedWith(compareBy<CloudFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun download(remotePath: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val fileId = remotePath
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 15000
            readTimeout = 60000
        }
        conn.inputStream.use { it.copyTo(output) }
        conn.disconnect()
        Unit
    }

    override suspend fun upload(remotePath: String, input: InputStream, fileName: String, mimeType: String) =
        withContext(Dispatchers.IO) {
            val parentId = if (remotePath == "/" || remotePath.isEmpty()) "root" else remotePath
            // Simple upload (up to 5MB; for larger files, resumable upload is needed)
            val metadata = JSONObject().apply {
                put("name", fileName)
                put("parents", org.json.JSONArray().put(parentId))
            }

            // Use multipart upload
            val boundary = "====${System.currentTimeMillis()}===="
            val url = URL(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
            }

            conn.outputStream.use { out ->
                val metaPart = "--$boundary\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                        metadata.toString() + "\r\n"
                out.write(metaPart.toByteArray())

                val mediaPart = "--$boundary\r\n" +
                        "Content-Type: $mimeType\r\n\r\n"
                out.write(mediaPart.toByteArray())
                input.copyTo(out)
                out.write("\r\n--$boundary--".toByteArray())
            }

            conn.responseCode
            conn.disconnect()
            Unit
        }

    override suspend fun delete(remotePath: String) = withContext(Dispatchers.IO) {
        val fileId = remotePath
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 15000
        }
        conn.responseCode
        conn.disconnect()
        Unit
    }

    override suspend fun createDirectory(remotePath: String) = withContext(Dispatchers.IO) {
        val parentId = if (remotePath == "/" || remotePath.isEmpty()) "root"
        else remotePath.substringBeforeLast("/").ifEmpty { "root" }
        val dirName = remotePath.substringAfterLast("/")

        val metadata = JSONObject().apply {
            put("name", dirName)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", org.json.JSONArray().put(parentId))
        }

        val url = URL("https://www.googleapis.com/drive/v3/files")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 15000
            doOutput = true
        }
        conn.outputStream.use { it.write(metadata.toString().toByteArray()) }
        conn.responseCode
        conn.disconnect()
        Unit
    }

    private fun parseGoogleDate(dateStr: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
