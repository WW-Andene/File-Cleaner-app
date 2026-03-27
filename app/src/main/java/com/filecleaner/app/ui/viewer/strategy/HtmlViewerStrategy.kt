package com.filecleaner.app.ui.viewer.strategy

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import java.io.File

/**
 * Renders HTML files securely in a WebView with CSP headers.
 * JavaScript disabled, file access disabled, built-in zoom.
 */
class HtmlViewerStrategy : ViewerStrategy {

    override fun canHandle(extension: String, category: FileCategory): Boolean =
        extension in ViewerRegistry.HTML_EXTENSIONS

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
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        }

        val rawHtml = file.readText()
        val cspMeta = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:;\">"
        val headRegex = Regex("(<head[^>]*>)", RegexOption.IGNORE_CASE)
        val htmlContent = if (rawHtml.contains("<head", ignoreCase = true)) {
            headRegex.replaceFirst(rawHtml, "$1$cspMeta")
        } else {
            "$cspMeta$rawHtml"
        }
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        webView.contentDescription = rootView.context.getString(R.string.a11y_webview_content, file.name)
    }

    override fun destroy() {
        // WebView cleanup handled by fragment's onDestroyView
    }
}
