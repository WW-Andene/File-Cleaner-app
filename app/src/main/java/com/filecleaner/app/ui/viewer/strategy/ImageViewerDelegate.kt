package com.filecleaner.app.ui.viewer.strategy

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import com.filecleaner.app.R
import com.filecleaner.app.utils.file.FileConverter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encapsulates image viewing logic: Glide loading, pinch-to-zoom/pan,
 * double-tap reset, and image editing toolbar actions.
 */
class ImageViewerDelegate(
    private val imageView: ImageView,
    private val rootView: View
) {

    // Zoom/pan state
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    fun show(file: File, fragment: androidx.fragment.app.Fragment) {
        imageView.visibility = View.VISIBLE
        Glide.with(fragment)
            .load(file)
            .placeholder(R.drawable.ic_image)
            .error(R.drawable.ic_image)
            .into(imageView)
        imageView.contentDescription =
            fragment.getString(R.string.a11y_image_preview, file.name)
        setupZoom()
    }

    /**
     * Wire up image editing toolbar buttons (rotate, flip, crop, grayscale, etc.)
     * Buttons are optional (?.) — caller provides the view references.
     */
    fun setupEditBar(
        file: File,
        fragment: androidx.fragment.app.Fragment,
        lifecycleScope: LifecycleCoroutineScope,
        editBar: View?,
        btnRotate: View?, btnFlip: View?, btnCrop: View?,
        btnBw: View?, btnInvert: View?, btnBright: View?, btnDark: View?
    ) {
        editBar?.visibility = View.VISIBLE

        fun applyAndReload(action: () -> FileConverter.ConvertResult) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { action() }
                if (result.success) {
                    Glide.with(fragment).load(File(result.outputPath)).into(imageView)
                    Snackbar.make(rootView, result.message, Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        btnRotate?.setOnClickListener { applyAndReload { FileConverter.rotateImage(file.absolutePath, 90f) } }
        btnFlip?.setOnClickListener { applyAndReload { FileConverter.flipImage(file.absolutePath, true) } }
        btnCrop?.setOnClickListener { applyAndReload { FileConverter.cropToAspectRatio(file.absolutePath, 1, 1) } }
        btnBw?.setOnClickListener { applyAndReload { FileConverter.toGrayscale(file.absolutePath) } }
        btnInvert?.setOnClickListener { applyAndReload { FileConverter.invertColors(file.absolutePath) } }
        btnBright?.setOnClickListener { applyAndReload { FileConverter.adjustBrightness(file.absolutePath, 1.3f) } }
        btnDark?.setOnClickListener { applyAndReload { FileConverter.adjustBrightness(file.absolutePath, 0.7f) } }
    }

    private fun setupZoom() {
        val scaleDetector = ScaleGestureDetector(imageView.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 5.0f)
                    imageView.scaleX = scaleFactor
                    imageView.scaleY = scaleFactor
                    return true
                }
            })

        imageView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    activePointerId = event.getPointerId(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleFactor > 1.0f) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            translateX += x - lastTouchX
                            translateY += y - lastTouchY
                            imageView.translationX = translateX
                            imageView.translationY = translateY
                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newIdx = if (pointerIndex == 0) 1 else 0
                        if (newIdx < event.pointerCount) {
                            lastTouchX = event.getX(newIdx)
                            lastTouchY = event.getY(newIdx)
                            activePointerId = event.getPointerId(newIdx)
                        }
                    }
                }
            }
            v.performClick()
            true
        }

        // Double-tap to reset
        imageView.setOnClickListener {
            if (scaleFactor != 1.0f) {
                scaleFactor = 1.0f
                translateX = 0f
                translateY = 0f
                imageView.scaleX = 1.0f
                imageView.scaleY = 1.0f
                imageView.translationX = 0f
                imageView.translationY = 0f
            }
        }
    }
}
