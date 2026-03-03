package com.filecleaner.app.data.cloud

import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebDAV cloud provider using plain HttpURLConnection (no extra dependencies).
 * Supports Nextcloud, ownCloud, and other WebDAV servers.
 */
class WebDavProvider(private val connection: CloudConnection) : CloudProvider {

    override val displayName: String = connection.displayName
    override val type: ProviderType = ProviderType.WEBDAV

    private var connected = false

    // Base URL must end without trailing slash
    private val baseUrl: String
        get() = connection.host.trimEnd('/')

    override val isConnected: Boolean get() = connected

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Test connection with a PROPFIND on root
            val url = URL("$baseUrl/")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PROPFIND"
                setRequestProperty("Authorization", authHeader())
                setRequestProperty("Depth", "0")
                setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            conn.disconnect()
            connected = code in 200..299 || code == 207
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
            val path = remotePath.trimEnd('/') + "/"
            val url = URL("$baseUrl$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PROPFIND"
                setRequestProperty("Authorization", authHeader())
                setRequestProperty("Depth", "1")
                setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
            }
            conn.outputStream.use { out ->
                out.write(PROPFIND_BODY.toByteArray(Charsets.UTF_8))
            }

            val responseBody = if (conn.responseCode in 200..299 || conn.responseCode == 207) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.disconnect()
                return@withContext emptyList()
            }
            conn.disconnect()

            parseMultiStatus(responseBody, path)
                .sortedWith(compareBy<CloudFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun download(remotePath: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$remotePath")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", authHeader())
            connectTimeout = 15000
            readTimeout = 30000
        }
        conn.inputStream.use { it.copyTo(output) }
        conn.disconnect()
        Unit
    }

    override suspend fun upload(remotePath: String, input: InputStream, fileName: String, mimeType: String) =
        withContext(Dispatchers.IO) {
            val path = remotePath.trimEnd('/') + "/$fileName"
            val url = URL("$baseUrl$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", authHeader())
                setRequestProperty("Content-Type", mimeType)
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }
            conn.outputStream.use { out -> input.copyTo(out) }
            conn.responseCode // Trigger the request
            conn.disconnect()
            Unit
        }

    override suspend fun delete(remotePath: String) = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$remotePath")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", authHeader())
            connectTimeout = 15000
        }
        conn.responseCode
        conn.disconnect()
        Unit
    }

    override suspend fun createDirectory(remotePath: String) = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$remotePath")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "MKCOL"
            setRequestProperty("Authorization", authHeader())
            connectTimeout = 15000
        }
        conn.responseCode
        conn.disconnect()
        Unit
    }

    private fun authHeader(): String {
        val credentials = "${connection.username}:${connection.authToken}"
        return "Basic ${Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}"
    }

    /** Parse WebDAV multistatus XML response into CloudFile list */
    private fun parseMultiStatus(xml: String, requestPath: String): List<CloudFile> {
        val files = mutableListOf<CloudFile>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var href = ""
            var isDirectory = false
            var contentLength = 0L
            var lastModified = 0L
            var contentType = ""
            var inResponse = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "response", "d:response", "D:response" -> {
                            inResponse = true
                            href = ""
                            isDirectory = false
                            contentLength = 0L
                            lastModified = 0L
                            contentType = ""
                        }
                        "href", "d:href", "D:href" -> if (inResponse) {
                            href = parser.nextText().trim()
                        }
                        "collection", "d:collection", "D:collection" -> isDirectory = true
                        "getcontentlength", "d:getcontentlength", "D:getcontentlength" -> {
                            contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        "getcontenttype", "d:getcontenttype", "D:getcontenttype" -> {
                            contentType = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "response", "d:response", "D:response" -> {
                            if (inResponse && href.isNotEmpty()) {
                                // Skip the directory itself (first entry)
                                val hrefPath = URL(if (href.startsWith("http")) href else "$baseUrl$href").path
                                val normalRequest = requestPath.trimEnd('/')
                                val normalHref = hrefPath.trimEnd('/')
                                if (normalHref != normalRequest) {
                                    val name = hrefPath.trimEnd('/').substringAfterLast('/')
                                    if (name.isNotEmpty()) {
                                        files.add(CloudFile(
                                            name = java.net.URLDecoder.decode(name, "UTF-8"),
                                            remotePath = hrefPath,
                                            isDirectory = isDirectory,
                                            size = contentLength,
                                            lastModified = lastModified,
                                            mimeType = contentType
                                        ))
                                    }
                                }
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }
        } catch (_: Exception) {
            // Parse errors — return what we have
        }
        return files
    }

    companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:getcontenttype/>
  </d:prop>
</d:propfind>"""
    }
}
