package com.filecleaner.app.ui.viewer.strategy

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.filecleaner.app.R
import java.io.File

/**
 * Manages PDF rendering with PdfRenderer, page navigation, and pinch-to-zoom.
 * Holds its own renderer/fd state — caller must invoke [destroy] in onDestroyView.
 */
class PdfViewerDelegate(
    private val pdfContainer: View,
    private val ivPdfPage: ImageView,
    private val tvPdfPageInfo: TextView,
    private val btnPdfPrev: ImageButton,
    private val btnPdfNext: ImageButton,
    private val strPageFormat: String
) {

    private var pdfRenderer: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private var currentPage = 0
    private var currentBitmap: Bitmap? = null

    /** Show the PDF. Returns false if the file cannot be rendered. */
    fun show(file: File, savedInstanceState: Bundle?): Boolean {
        return try {
            pdfContainer.visibility = View.VISIBLE
            pdfFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfFd!!)
            currentPage = savedInstanceState?.getInt("pdf_page", 0) ?: 0
            renderPage()

            btnPdfPrev.setOnClickListener {
                if (currentPage > 0) { currentPage--; renderPage() }
            }
            btnPdfNext.setOnClickListener {
                val count = pdfRenderer?.pageCount ?: 0
                if (currentPage < count - 1) { currentPage++; renderPage() }
            }
            setupZoom()
            true
        } catch (_: Exception) {
            pdfContainer.visibility = View.GONE
            false
        }
    }

    fun saveState(outState: Bundle) {
        outState.putInt("pdf_page", currentPage)
    }

    fun destroy() {
        currentBitmap?.recycle()
        currentBitmap = null
        pdfRenderer?.close()
        pdfFd?.close()
        pdfRenderer = null
        pdfFd = null
    }

    private fun renderPage() {
        val renderer = pdfRenderer ?: return
        val page = renderer.openPage(currentPage)
        val scale = 2
        val bitmap = Bitmap.createBitmap(
            page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        currentBitmap?.recycle()
        currentBitmap = bitmap
        ivPdfPage.setImageBitmap(bitmap)

        val pageCount = renderer.pageCount
        tvPdfPageInfo.text = String.format(strPageFormat, currentPage + 1, pageCount)
        btnPdfPrev.isEnabled = currentPage > 0
        btnPdfNext.isEnabled = currentPage < pageCount - 1
    }

    private fun setupZoom() {
        var scaleFactor = 1f
        val scaleDetector = ScaleGestureDetector(ivPdfPage.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 4f)
                    ivPdfPage.scaleX = scaleFactor
                    ivPdfPage.scaleY = scaleFactor
                    return true
                }
            })
        ivPdfPage.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
            true
        }
    }
}
