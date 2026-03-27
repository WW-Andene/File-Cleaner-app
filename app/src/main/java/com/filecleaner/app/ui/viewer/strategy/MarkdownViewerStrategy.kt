package com.filecleaner.app.ui.viewer.strategy

import android.view.View
import android.webkit.WebView
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import java.io.File

/**
 * Converts markdown to HTML and renders in a WebView.
 * Supports: headers, bold/italic, code blocks, inline code,
 * links, images, blockquotes, lists, horizontal rules, tables.
 * Dark mode support via prefers-color-scheme media query.
 */
class MarkdownViewerStrategy : ViewerStrategy {

    companion object {
        private const val MAX_TEXT_BYTES = 50 * 1024 // 50 KB
    }

    override fun canHandle(extension: String, category: FileCategory): Boolean =
        extension in ViewerRegistry.MARKDOWN_EXTENSIONS

    override fun show(file: File, rootView: View, savedInstanceState: android.os.Bundle?) {
        val webView = rootView.findViewById<WebView>(R.id.web_view) ?: return
        webView.visibility = View.VISIBLE
        webView.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        val content = try {
            val bytes = file.inputStream().use { it.readNBytes(MAX_TEXT_BYTES) }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "Error reading file: ${e.localizedMessage}"
        }

        val html = convertToHtml(content)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        webView.contentDescription = rootView.context.getString(R.string.a11y_webview_content, file.name)
    }

    /** Convert markdown text to styled HTML. */
    fun convertToHtml(content: String): String = buildString {
        append("<!DOCTYPE html><html><head>")
        append("<meta charset='utf-8'>")
        append("<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:;\">")
        append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        append("<style>")
        append(":root{--text:#1C1E1C;--bg:#F8F6F2;--code-bg:#EEEAE4;--border:#DDD9D3;--muted:#626966;}")
        append("@media(prefers-color-scheme:dark){:root{--text:#E6E4DF;--bg:#0E1311;--code-bg:#1F2723;--border:#2D3531;--muted:#8A918D;}}")
        append("body{font-family:sans-serif;padding:16px;line-height:1.6;color:var(--text);background:var(--bg);max-width:100%;word-wrap:break-word;}")
        append("pre,code{background:var(--code-bg);padding:2px 6px;border-radius:4px;font-family:monospace;font-size:14px;}")
        append("pre{padding:12px;overflow-x:auto;}")
        append("pre code{background:none;padding:0;}")
        append("blockquote{border-left:4px solid var(--border);margin:0;padding:0 16px;color:var(--muted);}")
        append("h1,h2,h3{margin-top:24px;}")
        append("hr{border:none;border-top:1px solid var(--border);margin:24px 0;}")
        append("img{max-width:100%;}")
        append("table{border-collapse:collapse;width:100%;}")
        append("th,td{border:1px solid var(--border);padding:8px;text-align:left;}")
        append("th{background:var(--code-bg);}")
        append("a{color:var(--muted);}")
        append("</style></head><body>")

        var inCodeBlock = false
        for (line in content.lines()) {
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    append("</code></pre>")
                    inCodeBlock = false
                } else {
                    append("<pre><code>")
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                append("\n")
                continue
            }

            var processed = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

            // Headers
            processed = processed
                .replace(Regex("^######\\s+(.*)"), "<h6>$1</h6>")
                .replace(Regex("^#####\\s+(.*)"), "<h5>$1</h5>")
                .replace(Regex("^####\\s+(.*)"), "<h4>$1</h4>")
                .replace(Regex("^###\\s+(.*)"), "<h3>$1</h3>")
                .replace(Regex("^##\\s+(.*)"), "<h2>$1</h2>")
                .replace(Regex("^#\\s+(.*)"), "<h1>$1</h1>")

            // Bold & italic
            processed = processed
                .replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*"), "<b><i>$1</i></b>")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")

            // Inline code
            processed = processed.replace(Regex("`(.+?)`"), "<code>$1</code>")

            // Images
            processed = processed.replace(Regex("!\\[([^]]*)]\\(([^)]+)\\)"), "<img src=\"$2\" alt=\"$1\">")

            // Links
            processed = processed.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")

            // Horizontal rule
            if (processed.matches(Regex("^\\s*[-*_]{3,}\\s*$"))) {
                append("<hr>")
                continue
            }

            // Blockquote
            if (processed.startsWith("&gt; ")) {
                processed = "<blockquote>${processed.removePrefix("&gt; ")}</blockquote>"
            }

            // Lists
            if (processed.matches(Regex("^\\s*[-*+]\\s+.*"))) {
                processed = "<li>${processed.replace(Regex("^\\s*[-*+]\\s+"), "")}</li>"
            }
            if (processed.matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
                processed = "<li>${processed.replace(Regex("^\\s*\\d+\\.\\s+"), "")}</li>"
            }

            if (processed.isBlank()) {
                append("<br>")
            } else if (!processed.startsWith("<h") && !processed.startsWith("<li") &&
                !processed.startsWith("<blockquote") && !processed.startsWith("<hr")) {
                append("<p>$processed</p>")
            } else {
                append(processed)
            }
        }
        if (inCodeBlock) append("</code></pre>")
        append("</body></html>")
    }
}
