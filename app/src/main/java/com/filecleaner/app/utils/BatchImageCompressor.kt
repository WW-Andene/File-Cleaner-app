package com.filecleaner.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compresses multiple images in batch to reduce storage usage.
 * Preserves original files and creates compressed copies, or
 * replaces originals based on user preference.
 */
object BatchImageCompressor {

    data class CompressResult(
        val processed: Int,
        val failed: Int,
        val savedBytes: Long,
        val outputPaths: List<String>
    )

    /**
     * Compresses a list of image files.
     *
     * @param paths List of image file paths
     * @param quality JPEG quality (1-100, default 75)
     * @param maxDimension Maximum width/height (aspect ratio preserved, 0 = no resize)
     * @param replaceOriginal If true, overwrites the original file
     * @param onProgress Called with (index, total) for each file
     */
    suspend fun compress(
        paths: List<String>,
        quality: Int = 75,
        maxDimension: Int = 0,
        replaceOriginal: Boolean = false,
        onProgress: ((Int, Int) -> Unit)? = null
    ): CompressResult = withContext(Dispatchers.IO) {
        var processed = 0
        var failed = 0
        var savedBytes = 0L
        val outputPaths = mutableListOf<String>()

        for ((index, path) in paths.withIndex()) {
            ensureActive()
            onProgress?.invoke(index, paths.size)

            try {
                val src = File(path)
                val originalSize = src.length()

                // Decode with optional downsampling
                val options = BitmapFactory.Options()
                if (maxDimension > 0) {
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(path, options)
                    val maxSide = maxOf(options.outWidth, options.outHeight)
                    options.inSampleSize = (maxSide / maxDimension).coerceAtLeast(1)
                    options.inJustDecodeBounds = false
                }

                val bitmap = BitmapFactory.decodeFile(path, options)
                if (bitmap == null) {
                    failed++
                    continue
                }

                // Resize if needed
                val finalBitmap = if (maxDimension > 0 && (bitmap.width > maxDimension || bitmap.height > maxDimension)) {
                    val ratio = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
                    val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                    val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                } else bitmap

                val outputFile = if (replaceOriginal) src
                else File(src.parent, "${src.nameWithoutExtension}_compressed.jpg")

                outputFile.outputStream().buffered().use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                finalBitmap.recycle()

                val newSize = outputFile.length()
                if (newSize < originalSize) {
                    savedBytes += originalSize - newSize
                }
                outputPaths.add(outputFile.absolutePath)
                processed++
            } catch (_: OutOfMemoryError) {
                failed++
            } catch (_: Exception) {
                failed++
            }
        }

        onProgress?.invoke(paths.size, paths.size)
        CompressResult(processed, failed, savedBytes, outputPaths)
    }
}
