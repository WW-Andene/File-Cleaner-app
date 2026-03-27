package com.filecleaner.app.utils.file

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Format conversion utilities using only Android SDK APIs (no external dependencies).
 *
 * Supported conversions:
 * - Image -> Image (PNG, JPG, WEBP lossy, WEBP lossless, BMP)
 * - Image resize (fit within max dimensions, preserving aspect ratio)
 * - Image(s) -> PDF
 * - PDF -> Images (PNG or JPG per page)
 * - Text/Code/CSV/Markdown -> PDF
 * - Video -> Thumbnail image at a chosen timestamp
 * - Video -> Key-frame series (evenly spaced across duration)
 * - Audio -> Extract embedded album art
 * - CSV -> Formatted text table
 */
object FileConverter {

    data class ConvertResult(val success: Boolean, val outputPath: String, val message: String)

    /** Supported image output formats. */
    enum class ImageFormat(val extension: String, val compressFormat: Bitmap.CompressFormat, val label: String) {
        PNG("png", Bitmap.CompressFormat.PNG, "PNG"),
        JPG("jpg", Bitmap.CompressFormat.JPEG, "JPG"),
        WEBP("webp", Bitmap.CompressFormat.WEBP_LOSSY, "WEBP (Lossy)"),
        WEBP_LOSSLESS("webp_ll", Bitmap.CompressFormat.WEBP_LOSSLESS, "WEBP (Lossless)"),
        BMP("bmp", Bitmap.CompressFormat.PNG, "BMP") // BMP uses custom writer
    }

    /** PDF extraction output format. */
    enum class PdfImageFormat(val extension: String, val compressFormat: Bitmap.CompressFormat, val label: String) {
        PNG("png", Bitmap.CompressFormat.PNG, "PNG images"),
        JPG("jpg", Bitmap.CompressFormat.JPEG, "JPG images")
    }

    // =========================================================================
    // IMAGE CONVERSIONS
    // =========================================================================

    /** Convert an image file to another image format. */
    fun convertImage(inputPath: String, outputFormat: ImageFormat, quality: Int = 90): ConvertResult {
        return try {
            val src = File(inputPath)
            if (!src.exists()) return ConvertResult(false, "", "Source file not found")

            val bitmap = BitmapFactory.decodeFile(inputPath)
                ?: return ConvertResult(false, "", "Cannot decode image")

            try {
                val ext = if (outputFormat == ImageFormat.WEBP_LOSSLESS) "webp" else outputFormat.extension
                val suffix = if (outputFormat == ImageFormat.WEBP_LOSSLESS) "_lossless" else ""
                val outputName = "${src.nameWithoutExtension}$suffix.$ext"
                val outputFile = File(src.parent, outputName)
                val finalFile = if (outputFile.absolutePath == src.absolutePath) {
                    File(src.parent, "${src.nameWithoutExtension}_converted.$ext")
                } else outputFile

                if (outputFormat == ImageFormat.BMP) {
                    writeBmp(bitmap, finalFile)
                } else {
                    finalFile.outputStream().buffered().use { out ->
                        bitmap.compress(outputFormat.compressFormat, quality, out)
                    }
                }
                ConvertResult(true, finalFile.absolutePath, "Converted to ${finalFile.name}")
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            ConvertResult(false, "", "Conversion failed: ${e.localizedMessage}")
        }
    }

    /** Write a bitmap as BMP format (uncompressed 24-bit). */
    private fun writeBmp(bitmap: Bitmap, outputFile: File) {
        val w = bitmap.width
        val h = bitmap.height
        val rowSize = ((24L * w + 31) / 32) * 4
        val imageSize = rowSize * h
        val fileSize = 54 + imageSize

        if (fileSize > Int.MAX_VALUE || imageSize > Int.MAX_VALUE) {
            throw IllegalArgumentException("Image too large for BMP format (${w}x${h})")
        }

        outputFile.outputStream().buffered().use { out ->
            // BMP header
            out.write(byteArrayOf('B'.code.toByte(), 'M'.code.toByte()))
            out.write(intToBytes(fileSize.toInt()))
            out.write(intToBytes(0)) // reserved
            out.write(intToBytes(54)) // offset

            // DIB header
            out.write(intToBytes(40)) // header size
            out.write(intToBytes(w))
            out.write(intToBytes(h))
            out.write(shortToBytes(1)) // planes
            out.write(shortToBytes(24)) // bits per pixel
            out.write(intToBytes(0)) // compression
            out.write(intToBytes(imageSize.toInt()))
            out.write(intToBytes(2835)) // h resolution
            out.write(intToBytes(2835)) // v resolution
            out.write(intToBytes(0)) // colors
            out.write(intToBytes(0)) // important colors

            // Pixel data (bottom-up)
            val row = ByteArray(rowSize.toInt())
            for (y in h - 1 downTo 0) {
                row.fill(0)
                for (x in 0 until w) {
                    val pixel = bitmap.getPixel(x, y)
                    row[x * 3] = (pixel and 0xFF).toByte()         // B
                    row[x * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()  // G
                    row[x * 3 + 2] = ((pixel shr 16) and 0xFF).toByte() // R
                }
                out.write(row)
            }
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    /** Resize an image to fit within maxWidth x maxHeight, preserving aspect ratio. */
    fun resizeImage(inputPath: String, maxWidth: Int, maxHeight: Int, outputFormat: ImageFormat, quality: Int = 90): ConvertResult {
        return try {
            val src = File(inputPath)
            if (!src.exists()) return ConvertResult(false, "", "Source file not found")

            val original = BitmapFactory.decodeFile(inputPath)
                ?: return ConvertResult(false, "", "Cannot decode image")

            var resized: Bitmap? = null
            try {
                val ratioW = maxWidth.toFloat() / original.width
                val ratioH = maxHeight.toFloat() / original.height
                val ratio = minOf(ratioW, ratioH, 1f)
                val newW = (original.width * ratio).toInt().coerceAtLeast(1)
                val newH = (original.height * ratio).toInt().coerceAtLeast(1)

                resized = try {
                    Bitmap.createScaledBitmap(original, newW, newH, true)
                } catch (_: OutOfMemoryError) {
                    return ConvertResult(false, "", "Image too large to resize")
                }

                val ext = if (outputFormat == ImageFormat.WEBP_LOSSLESS) "webp" else outputFormat.extension
                val outputFile = File(src.parent, "${src.nameWithoutExtension}_${newW}x${newH}.$ext")
                outputFile.outputStream().buffered().use { out ->
                    resized.compress(outputFormat.compressFormat, quality, out)
                }

                ConvertResult(true, outputFile.absolutePath, "Resized to ${newW}x${newH}")
            } finally {
                if (resized != null && resized !== original) resized.recycle()
                original.recycle()
            }
        } catch (e: Exception) {
            ConvertResult(false, "", "Resize failed: ${e.localizedMessage}")
        }
    }

    // =========================================================================
    // PDF CONVERSIONS
    // =========================================================================

    /** Convert one or more images to a multi-page PDF. */
    fun imagesToPdf(imagePaths: List<String>, outputPath: String): ConvertResult {
        if (imagePaths.isEmpty()) return ConvertResult(false, "", "No images provided")
        val doc = PdfDocument()
        return try {
            var pagesAdded = 0
            for ((index, path) in imagePaths.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(path) ?: continue
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    val page = doc.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    doc.finishPage(page)
                    pagesAdded++
                } finally {
                    bitmap.recycle()
                }
            }
            if (pagesAdded == 0) return ConvertResult(false, "", "No valid images to convert")
            File(outputPath).outputStream().buffered().use { out ->
                doc.writeTo(out)
            }
            ConvertResult(true, outputPath, "Created ${File(outputPath).name}")
        } catch (e: Exception) {
            ConvertResult(false, "", "PDF creation failed: ${e.localizedMessage}")
        } finally {
            doc.close()
        }
    }

    /** Extract all pages of a PDF to individual images. */
    fun pdfToImages(pdfPath: String, outputDir: String, format: PdfImageFormat = PdfImageFormat.PNG, quality: Int = 90): ConvertResult {
        val pdfFile = File(pdfPath)
        if (!pdfFile.exists()) return ConvertResult(false, "", "PDF not found")

        // B4: Extract to temp directory first, then rename on success to avoid partial results
        val outDir = File(outputDir)
        val tempDir = File(outDir.parent, "${outDir.name}_tmp_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            val pageCount = renderer.pageCount

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                // F-043: Cap scale factor so rendered bitmap never exceeds MAX_PDF_PAGE_PX
                // to prevent OOM on pages with unusual dimensions
                val maxPx = 4096
                val scale = minOf(2, maxPx / maxOf(page.width, page.height, 1))
                    .coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(
                    page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                )
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val pageFile = File(tempDir, "${pdfFile.nameWithoutExtension}_page_${i + 1}.${format.extension}")
                    pageFile.outputStream().buffered().use { out ->
                        bitmap.compress(format.compressFormat, quality, out)
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            // All pages extracted successfully — move temp dir to final location
            renderer?.close(); renderer = null
            fd?.close(); fd = null
            if (outDir.exists()) outDir.deleteRecursively()
            tempDir.renameTo(outDir)
            ConvertResult(true, outDir.absolutePath, "Extracted $pageCount pages to ${outDir.name}/")
        } catch (e: Exception) {
            // Clean up partial temp output on failure
            tempDir.deleteRecursively()
            ConvertResult(false, "", "PDF extraction failed: ${e.localizedMessage}")
        } finally {
            renderer?.close()
            fd?.close()
        }
    }

    // =========================================================================
    // TEXT / DOCUMENT -> PDF
    // =========================================================================

    /** Convert a text file to PDF (monospace rendering, A4 pages). */
    private const val MAX_TEXT_TO_PDF_BYTES = 10L * 1024 * 1024 // 10 MB

    fun textToPdf(inputPath: String, outputPath: String, fontSize: Float = 10f): ConvertResult {
        val src = File(inputPath)
        if (!src.exists()) return ConvertResult(false, "", "Source file not found")
        if (src.length() > MAX_TEXT_TO_PDF_BYTES) {
            return ConvertResult(false, "", "File too large (max ${MAX_TEXT_TO_PDF_BYTES / 1024 / 1024} MB)")
        }

        val doc = PdfDocument()
        return try {
            val lines = BufferedReader(InputStreamReader(src.inputStream(), Charsets.UTF_8)).use {
                it.readLines()
            }

            if (lines.isEmpty()) return ConvertResult(false, "", "Source file is empty")

            val paint = Paint().apply {
                typeface = Typeface.MONOSPACE
                textSize = fontSize
                color = Color.BLACK
                isAntiAlias = true
            }

            val pageWidth = 595 // A4 at 72 DPI
            val pageHeight = 842
            val margin = 40f
            val lineHeight = fontSize * 1.4f
            val usableHeight = pageHeight - margin * 2
            val linesPerPage = (usableHeight / lineHeight).toInt().coerceAtLeast(1)

            var pageNum = 1
            var lineIndex = 0

            while (lineIndex < lines.size) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(Color.WHITE)
                var y = margin + fontSize
                for (i in 0 until linesPerPage) {
                    if (lineIndex + i >= lines.size) break
                    canvas.drawText(lines[lineIndex + i], margin, y, paint)
                    y += lineHeight
                }

                doc.finishPage(page)
                lineIndex += linesPerPage
                pageNum++
            }

            File(outputPath).outputStream().buffered().use { doc.writeTo(it) }

            ConvertResult(true, outputPath, "Created ${File(outputPath).name} (${pageNum - 1} pages)")
        } catch (e: Exception) {
            ConvertResult(false, "", "Text to PDF failed: ${e.localizedMessage}")
        } finally {
            doc.close()
        }
    }

    /** Convert CSV to formatted text table. */
    fun csvToText(inputPath: String, outputPath: String, delimiter: Char = ','): ConvertResult {
        return try {
            val src = File(inputPath)
            if (!src.exists()) return ConvertResult(false, "", "Source file not found")

            val rows = src.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines().map { line -> parseCsvLine(line, delimiter) }
            }

            if (rows.isEmpty()) return ConvertResult(false, "", "CSV file is empty")

            val colCount = rows.maxOf { it.size }
            val colWidths = IntArray(colCount)
            for (row in rows) {
                for ((i, cell) in row.withIndex()) {
                    colWidths[i] = maxOf(colWidths[i], cell.length)
                }
            }

            val sb = StringBuilder()
            for ((rowIndex, row) in rows.withIndex()) {
                for (i in 0 until colCount) {
                    val cell = row.getOrElse(i) { "" }
                    sb.append(cell.padEnd(colWidths[i] + 2))
                }
                sb.appendLine()
                if (rowIndex == 0) {
                    for (i in 0 until colCount) {
                        sb.append("\u2500".repeat(colWidths[i] + 2))
                    }
                    sb.appendLine()
                }
            }

            File(outputPath).writeText(sb.toString(), Charsets.UTF_8)
            ConvertResult(true, outputPath, "Converted to ${File(outputPath).name}")
        } catch (e: Exception) {
            ConvertResult(false, "", "CSV conversion failed: ${e.localizedMessage}")
        }
    }

    private fun parseCsvLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        // RFC 4180: escaped quote ("") -> literal quote
                        current.append('"')
                        i++ // skip the second quote
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    cells.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
            i++
        }
        cells.add(current.toString().trim())
        return cells
    }

    // =========================================================================
    // VIDEO CONVERSIONS
    // =========================================================================

    /**
     * Get the duration of a video file in milliseconds.
     * Returns -1 if the duration cannot be determined.
     */
    fun getVideoDurationMs(inputPath: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Extract a single frame from a video at a specific timestamp.
     *
     * @param inputPath  path to the video file
     * @param timeMs     timestamp in milliseconds (0 = first frame)
     * @param outputFormat image format for the extracted frame
     * @param quality    compression quality (1-100, only affects JPG/WEBP)
     */
    fun extractFrameAtTime(
        inputPath: String,
        timeMs: Long = 0,
        outputFormat: ImageFormat = ImageFormat.PNG,
        quality: Int = 90
    ): ConvertResult {
        val src = File(inputPath)
        if (!src.exists()) return ConvertResult(false, "", "Source file not found")

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            val timeUs = timeMs * 1000
            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
                ?: return ConvertResult(false, "", "Cannot extract frame from video")

            try {
                val ext = if (outputFormat == ImageFormat.WEBP_LOSSLESS) "webp" else outputFormat.extension
                val timeSuffix = formatTimeSuffix(timeMs)
                val outputFile = File(src.parent, "${src.nameWithoutExtension}_frame_${timeSuffix}.$ext")
                outputFile.outputStream().buffered().use { out ->
                    bitmap.compress(outputFormat.compressFormat, quality, out)
                }
                ConvertResult(true, outputFile.absolutePath, "Frame at ${formatTimeDisplay(timeMs)}: ${outputFile.name}")
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            ConvertResult(false, "", "Frame extraction failed: ${e.localizedMessage}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Extract evenly-spaced key frames from a video.
     *
     * @param inputPath    path to the video file
     * @param outputDir    directory to store extracted frames
     * @param frameCount   number of frames to extract (evenly spaced across duration)
     * @param outputFormat image format for the extracted frames
     * @param quality      compression quality
     */
    fun extractKeyFrames(
        inputPath: String,
        outputDir: String,
        frameCount: Int = 10,
        outputFormat: ImageFormat = ImageFormat.JPG,
        quality: Int = 85
    ): ConvertResult {
        val src = File(inputPath)
        if (!src.exists()) return ConvertResult(false, "", "Source file not found")

        val outDir = File(outputDir)
        if (!outDir.mkdirs() && !outDir.isDirectory) {
            return ConvertResult(false, "", "Cannot create output directory")
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return ConvertResult(false, "", "Cannot determine video duration")
            val durationUs = durationMs * 1000
            val count = frameCount.coerceAtLeast(1)
            val interval = if (count == 1) durationUs / 2 else durationUs / count

            var extracted = 0
            for (i in 0 until count) {
                val timeUs = if (count == 1) interval else interval * i
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                try {
                    val ext = if (outputFormat == ImageFormat.WEBP_LOSSLESS) "webp" else outputFormat.extension
                    val frameFile = File(outDir, "${src.nameWithoutExtension}_frame_${i + 1}.$ext")
                    frameFile.outputStream().buffered().use { out ->
                        bitmap.compress(outputFormat.compressFormat, quality, out)
                    }
                    extracted++
                } finally {
                    bitmap.recycle()
                }
            }

            ConvertResult(true, outDir.absolutePath, "Extracted $extracted key frames to ${outDir.name}/")
        } catch (e: Exception) {
            ConvertResult(false, "", "Frame extraction failed: ${e.localizedMessage}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Format milliseconds as a filename-safe suffix, e.g. "1m30s". */
    private fun formatTimeSuffix(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "${min}m${sec}s" else "${sec}s"
    }

    /** Format milliseconds for display, e.g. "1:30". */
    fun formatTimeDisplay(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    // =========================================================================
    // VIDEO → GIF CONVERSION
    // =========================================================================

    /**
     * Converts a video segment to an animated GIF.
     *
     * @param inputPath Source video path
     * @param startMs   Start time in milliseconds (default: 0)
     * @param durationMs Duration to capture in milliseconds (default: 3000 = 3 seconds)
     * @param fps       Frames per second for the GIF (default: 10)
     * @param maxWidth  Maximum width in pixels (default: 320, aspect ratio preserved)
     */
    fun videoToGif(
        inputPath: String,
        startMs: Long = 0,
        durationMs: Long = 3000,
        fps: Int = 10,
        maxWidth: Int = 320
    ): ConvertResult {
        val src = File(inputPath)
        if (!src.exists()) return ConvertResult(false, "", "Source video not found")

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            val videoDurationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return ConvertResult(false, "", "Cannot determine video duration")

            val actualDuration = durationMs.coerceAtMost(videoDurationMs - startMs).coerceAtLeast(100)
            val frameCount = ((actualDuration / 1000.0) * fps).toInt().coerceIn(1, 150)
            val intervalMs = actualDuration / frameCount
            val delayCs = (1000 / fps.coerceIn(1, 30)) / 10 // centiseconds for GIF

            // Extract frames
            val frames = mutableListOf<Bitmap>()
            for (i in 0 until frameCount) {
                val timeUs = (startMs + i * intervalMs) * 1000
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue

                // Scale down to maxWidth preserving aspect ratio
                val scale = maxWidth.toFloat() / frame.width.coerceAtLeast(1)
                val scaledFrame = if (scale < 1f) {
                    val newW = (frame.width * scale).toInt().coerceAtLeast(1)
                    val newH = (frame.height * scale).toInt().coerceAtLeast(1)
                    try {
                        Bitmap.createScaledBitmap(frame, newW, newH, true).also {
                            if (it !== frame) frame.recycle()
                        }
                    } catch (_: OutOfMemoryError) {
                        frame.recycle()
                        continue
                    }
                } else {
                    frame
                }
                frames.add(scaledFrame)
            }

            if (frames.isEmpty()) {
                return ConvertResult(false, "", "No frames could be extracted")
            }

            // Encode as GIF
            val outputFile = File(src.parent, "${src.nameWithoutExtension}_${formatTimeSuffix(startMs)}.gif")
            outputFile.outputStream().buffered().use { out ->
                val encoder = SimpleGifEncoder(out)
                encoder.start(frames[0].width, frames[0].height, delayCs)
                for (frame in frames) {
                    encoder.addFrame(frame)
                    frame.recycle()
                }
                encoder.finish()
            }

            ConvertResult(true, outputFile.absolutePath,
                "Created GIF: ${frames.size} frames, ${outputFile.length() / 1024} KB")
        } catch (e: Exception) {
            ConvertResult(false, "", "GIF creation failed: ${e.localizedMessage}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // AUDIO CONVERSIONS
    // =========================================================================

    /** Extract album art from an audio file. */
    fun audioToAlbumArt(inputPath: String, outputFormat: ImageFormat = ImageFormat.PNG, quality: Int = 90): ConvertResult {
        val src = File(inputPath)
        if (!src.exists()) return ConvertResult(false, "", "Source file not found")

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            val art = retriever.embeddedPicture
                ?: return ConvertResult(false, "", "No album art found in this file")

            val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                ?: return ConvertResult(false, "", "Cannot decode album art")

            try {
                val ext = if (outputFormat == ImageFormat.WEBP_LOSSLESS) "webp" else outputFormat.extension
                val outputFile = File(src.parent, "${src.nameWithoutExtension}_cover.$ext")
                outputFile.outputStream().buffered().use { out ->
                    bitmap.compress(outputFormat.compressFormat, quality, out)
                }
                ConvertResult(true, outputFile.absolutePath, "Album art saved: ${outputFile.name}")
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            ConvertResult(false, "", "Album art extraction failed: ${e.localizedMessage}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // IMAGE TRANSFORMS
    // =========================================================================

    /** Rotate an image by [degrees] (90, 180, 270). */
    fun rotateImage(inputPath: String, degrees: Float): ConvertResult {
        return try {
            val src = File(inputPath)
            val bitmap = BitmapFactory.decodeFile(inputPath) ?: return ConvertResult(false, "", "Cannot decode image")
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()

            val ext = src.extension.ifEmpty { "jpg" }
            val output = File(src.parent, "${src.nameWithoutExtension}_rot${degrees.toInt()}.$ext")
            val format = when (ext.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.JPEG
            }
            output.outputStream().buffered().use { rotated.compress(format, 95, it) }
            rotated.recycle()
            ConvertResult(true, output.absolutePath, "Rotated ${degrees.toInt()}°")
        } catch (e: Exception) {
            ConvertResult(false, "", "Rotate failed: ${e.localizedMessage}")
        }
    }

    /** Flip an image horizontally or vertically. */
    fun flipImage(inputPath: String, horizontal: Boolean): ConvertResult {
        return try {
            val src = File(inputPath)
            val bitmap = BitmapFactory.decodeFile(inputPath) ?: return ConvertResult(false, "", "Cannot decode image")
            val matrix = android.graphics.Matrix().apply {
                if (horizontal) preScale(-1f, 1f) else preScale(1f, -1f)
            }
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()

            val dir = if (horizontal) "h" else "v"
            val ext = src.extension.ifEmpty { "jpg" }
            val output = File(src.parent, "${src.nameWithoutExtension}_flip${dir}.$ext")
            val format = when (ext.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.JPEG
            }
            output.outputStream().buffered().use { flipped.compress(format, 95, it) }
            flipped.recycle()
            ConvertResult(true, output.absolutePath, "Flipped ${if (horizontal) "horizontally" else "vertically"}")
        } catch (e: Exception) {
            ConvertResult(false, "", "Flip failed: ${e.localizedMessage}")
        }
    }

    /** Crop an image to a target aspect ratio (e.g., 16:9, 1:1, 4:3). */
    fun cropToAspectRatio(inputPath: String, ratioW: Int, ratioH: Int): ConvertResult {
        return try {
            val src = File(inputPath)
            val bitmap = BitmapFactory.decodeFile(inputPath) ?: return ConvertResult(false, "", "Cannot decode image")

            val targetRatio = ratioW.toFloat() / ratioH
            val currentRatio = bitmap.width.toFloat() / bitmap.height
            val (cropW, cropH) = if (currentRatio > targetRatio) {
                val w = (bitmap.height * targetRatio).toInt()
                w to bitmap.height
            } else {
                bitmap.width to (bitmap.width / targetRatio).toInt()
            }
            val x = (bitmap.width - cropW) / 2
            val y = (bitmap.height - cropH) / 2

            val cropped = Bitmap.createBitmap(bitmap, x, y, cropW, cropH)
            bitmap.recycle()

            val ext = src.extension.ifEmpty { "jpg" }
            val output = File(src.parent, "${src.nameWithoutExtension}_${ratioW}x${ratioH}.$ext")
            val format = when (ext.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.JPEG
            }
            output.outputStream().buffered().use { cropped.compress(format, 95, it) }
            cropped.recycle()
            ConvertResult(true, output.absolutePath, "Cropped to $ratioW:$ratioH (${cropW}×${cropH})")
        } catch (e: Exception) {
            ConvertResult(false, "", "Crop failed: ${e.localizedMessage}")
        }
    }

    /** Add a text watermark to an image. */
    fun addWatermark(inputPath: String, text: String, opacity: Float = 0.5f): ConvertResult {
        return try {
            val src = File(inputPath)
            val bitmap = BitmapFactory.decodeFile(inputPath)?.copy(Bitmap.Config.ARGB_8888, true)
                ?: return ConvertResult(false, "", "Cannot decode image")

            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = (opacity * 255).toInt()
                textSize = bitmap.width * 0.05f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }
            val x = bitmap.width * 0.05f
            val y = bitmap.height - bitmap.width * 0.05f
            canvas.drawText(text, x, y, paint)

            val ext = src.extension.ifEmpty { "jpg" }
            val output = File(src.parent, "${src.nameWithoutExtension}_watermark.$ext")
            val format = when (ext.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }
            output.outputStream().buffered().use { bitmap.compress(format, 95, it) }
            bitmap.recycle()
            ConvertResult(true, output.absolutePath, "Watermark added: \"$text\"")
        } catch (e: Exception) {
            ConvertResult(false, "", "Watermark failed: ${e.localizedMessage}")
        }
    }

    // =========================================================================
    // PDF OPERATIONS
    // =========================================================================

    /** Merge multiple PDF files into one. */
    fun mergePdfs(inputPaths: List<String>, outputPath: String): ConvertResult {
        return try {
            val outDoc = PdfDocument()
            var pageNum = 1

            for (path in inputPaths) {
                val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)

                for (i in 0 until renderer.pageCount) {
                    val srcPage = renderer.openPage(i)
                    val pageInfo = PdfDocument.PageInfo.Builder(srcPage.width, srcPage.height, pageNum++).create()
                    val destPage = outDoc.startPage(pageInfo)
                    srcPage.render(destPage.canvas, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    outDoc.finishPage(destPage)
                    srcPage.close()
                }
                renderer.close()
                fd.close()
            }

            File(outputPath).outputStream().use { outDoc.writeTo(it) }
            outDoc.close()
            ConvertResult(true, outputPath, "Merged ${inputPaths.size} PDFs ($pageNum pages)")
        } catch (e: Exception) {
            ConvertResult(false, "", "PDF merge failed: ${e.localizedMessage}")
        }
    }

    /** Split a PDF into individual single-page PDFs. */
    fun splitPdf(inputPath: String, outputDir: String): ConvertResult {
        return try {
            val src = File(inputPath)
            val outDir = File(outputDir)
            if (!outDir.mkdirs() && !outDir.isDirectory) {
                return ConvertResult(false, "", "Cannot create output directory")
            }

            val fd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val pageCount = renderer.pageCount

            for (i in 0 until pageCount) {
                val srcPage = renderer.openPage(i)
                val singleDoc = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(srcPage.width, srcPage.height, 1).create()
                val destPage = singleDoc.startPage(pageInfo)
                srcPage.render(destPage.canvas, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                singleDoc.finishPage(destPage)
                srcPage.close()

                val pageFile = File(outDir, "${src.nameWithoutExtension}_page_${i + 1}.pdf")
                pageFile.outputStream().use { singleDoc.writeTo(it) }
                singleDoc.close()
            }

            renderer.close()
            fd.close()
            ConvertResult(true, outDir.absolutePath, "Split into $pageCount pages")
        } catch (e: Exception) {
            ConvertResult(false, "", "PDF split failed: ${e.localizedMessage}")
        }
    }

    // =========================================================================
    // VIDEO CONTACT SHEET
    // =========================================================================

    /** Creates a thumbnail grid (contact sheet) from a video. */
    fun videoContactSheet(
        inputPath: String,
        columns: Int = 4,
        rows: Int = 4,
        thumbWidth: Int = 320
    ): ConvertResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputPath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return ConvertResult(false, "", "Cannot read video duration")

            val count = columns * rows
            val interval = durationMs / count
            val thumbs = mutableListOf<Bitmap>()

            for (i in 0 until count) {
                val timeUs = (interval * i) * 1000
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val scale = thumbWidth.toFloat() / frame.width
                val h = (frame.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(frame, thumbWidth, h, true)
                if (scaled !== frame) frame.recycle()
                thumbs.add(scaled)
            }

            if (thumbs.isEmpty()) return ConvertResult(false, "", "No frames extracted")

            val thumbH = thumbs[0].height
            val gridW = thumbWidth * columns
            val gridH = thumbH * rows
            val grid = Bitmap.createBitmap(gridW, gridH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(grid)
            canvas.drawColor(Color.BLACK)

            for ((idx, thumb) in thumbs.withIndex()) {
                val col = idx % columns
                val row = idx / columns
                if (row >= rows) break
                canvas.drawBitmap(thumb, (col * thumbWidth).toFloat(), (row * thumbH).toFloat(), null)
                thumb.recycle()
            }

            val src = File(inputPath)
            val output = File(src.parent, "${src.nameWithoutExtension}_contact_sheet.jpg")
            output.outputStream().buffered().use { grid.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            grid.recycle()
            ConvertResult(true, output.absolutePath, "Contact sheet: ${columns}x${rows} grid")
        } catch (e: Exception) {
            ConvertResult(false, "", "Contact sheet failed: ${e.localizedMessage}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // UTILITY CONVERSIONS
    // =========================================================================

    /** Convert an image to Base64 text string. */
    fun imageToBase64(inputPath: String): ConvertResult {
        return try {
            val src = File(inputPath)
            val bytes = src.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val ext = src.extension.lowercase()
            val mime = when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val dataUrl = "data:$mime;base64,$base64"

            val output = File(src.parent, "${src.nameWithoutExtension}_base64.txt")
            output.writeText(dataUrl)
            ConvertResult(true, output.absolutePath, "Base64: ${output.length() / 1024} KB text")
        } catch (e: Exception) {
            ConvertResult(false, "", "Base64 failed: ${e.localizedMessage}")
        }
    }

    /** Convert a vCard (.vcf) file to CSV. */
    fun vcardToCsv(inputPath: String): ConvertResult {
        return try {
            val src = File(inputPath)
            val lines = src.readLines()
            val contacts = mutableListOf<MutableMap<String, String>>()
            var current: MutableMap<String, String>? = null

            for (line in lines) {
                when {
                    line.startsWith("BEGIN:VCARD") -> current = mutableMapOf()
                    line.startsWith("END:VCARD") -> {
                        current?.let { contacts.add(it) }
                        current = null
                    }
                    line.startsWith("FN:") -> current?.set("Name", line.substringAfter("FN:"))
                    line.startsWith("N:") -> current?.putIfAbsent("Name", line.substringAfter("N:").replace(";", " ").trim())
                    line.startsWith("TEL") -> current?.set("Phone", line.substringAfter(":"))
                    line.startsWith("EMAIL") -> current?.set("Email", line.substringAfter(":"))
                    line.startsWith("ORG:") -> current?.set("Organization", line.substringAfter("ORG:"))
                    line.startsWith("TITLE:") -> current?.set("Title", line.substringAfter("TITLE:"))
                    line.startsWith("ADR") -> current?.set("Address", line.substringAfter(":").replace(";", ", ").trim())
                }
            }

            if (contacts.isEmpty()) return ConvertResult(false, "", "No contacts found in vCard")

            val headers = listOf("Name", "Phone", "Email", "Organization", "Title", "Address")
            val output = File(src.parent, "${src.nameWithoutExtension}.csv")
            output.bufferedWriter().use { writer ->
                writer.appendLine(headers.joinToString(","))
                for (contact in contacts) {
                    writer.appendLine(headers.joinToString(",") { "\"${contact[it] ?: ""}\"" })
                }
            }
            ConvertResult(true, output.absolutePath, "Converted ${contacts.size} contacts to CSV")
        } catch (e: Exception) {
            ConvertResult(false, "", "vCard conversion failed: ${e.localizedMessage}")
        }
    }

    // =========================================================================
    // BATCH IMAGE CONVERSION
    // =========================================================================

    /** Convert multiple images to the same format. */
    fun batchConvertImages(
        inputPaths: List<String>,
        outputFormat: ImageFormat,
        quality: Int = 90,
        onProgress: ((Int, Int) -> Unit)? = null
    ): ConvertResult {
        var success = 0
        var failed = 0
        for ((index, path) in inputPaths.withIndex()) {
            onProgress?.invoke(index, inputPaths.size)
            val result = convertImage(path, outputFormat, quality)
            if (result.success) success++ else failed++
        }
        onProgress?.invoke(inputPaths.size, inputPaths.size)
        return ConvertResult(true, "",
            "Converted $success images to ${outputFormat.label}" +
                if (failed > 0) " ($failed failed)" else "")
    }

    // =========================================================================
    // IMAGE COLOR OPERATIONS
    // =========================================================================

    /** Convert an image to grayscale (black & white). */
    fun toGrayscale(inputPath: String): ConvertResult {
        return try {
            val src = File(inputPath)
            val bitmap = BitmapFactory.decodeFile(inputPath) ?: return ConvertResult(false, "", "Cannot decode image")
            val gray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(gray)
            val paint = android.graphics.Paint()
            val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            bitmap.recycle()

            val output = File(src.parent, "${src.nameWithoutExtension}_bw.${src.extension.ifEmpty { "jpg" }}")
            val format = when (src.extension.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }
            output.outputStream().buffered().use { gray.compress(format, 95, it) }
            gray.recycle()
            ConvertResult(true, output.absolutePath, "Converted to grayscale")
        } catch (e: Exception) {
            ConvertResult(false, "", "Grayscale failed: ${e.localizedMessage}")
        }
    }

    /** Adjust image brightness. [factor] 0.0=black, 1.0=original, 2.0=double bright. */
    fun adjustBrightness(inputPath: String, factor: Float): ConvertResult {
        return try {
            val src = File(inputPath)
            val bitmap = BitmapFactory.decodeFile(inputPath)?.copy(Bitmap.Config.ARGB_8888, true)
                ?: return ConvertResult(false, "", "Cannot decode image")
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            val cm = android.graphics.ColorMatrix().apply { setScale(factor, factor, factor, 1f) }
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)

            val label = if (factor > 1f) "brightened" else "darkened"
            val output = File(src.parent, "${src.nameWithoutExtension}_${label}.${src.extension.ifEmpty { "jpg" }}")
            val format = when (src.extension.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }
            output.outputStream().buffered().use { bitmap.compress(format, 95, it) }
            bitmap.recycle()
            ConvertResult(true, output.absolutePath, "Image $label (${(factor * 100).toInt()}%)")
        } catch (e: Exception) {
            ConvertResult(false, "", "Brightness adjust failed: ${e.localizedMessage}")
        }
    }

    /** Invert image colors (negative). */
    fun invertColors(inputPath: String): ConvertResult {
        return try {
            val src = File(inputPath)
            val bitmap = BitmapFactory.decodeFile(inputPath) ?: return ConvertResult(false, "", "Cannot decode image")
            val inverted = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(inverted)
            val paint = android.graphics.Paint()
            val cm = android.graphics.ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            bitmap.recycle()

            val output = File(src.parent, "${src.nameWithoutExtension}_inverted.${src.extension.ifEmpty { "jpg" }}")
            val format = when (src.extension.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }
            output.outputStream().buffered().use { inverted.compress(format, 95, it) }
            inverted.recycle()
            ConvertResult(true, output.absolutePath, "Colors inverted")
        } catch (e: Exception) {
            ConvertResult(false, "", "Invert failed: ${e.localizedMessage}")
        }
    }

    // =========================================================================
    // DOCUMENT OPERATIONS
    // =========================================================================

    /** Extract text content from any text-based file for clipboard/sharing. */
    fun extractText(inputPath: String, maxBytes: Int = 500_000): ConvertResult {
        return try {
            val src = File(inputPath)
            val text = src.inputStream().bufferedReader().use { reader ->
                val buf = CharArray(maxBytes)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
            val output = File(src.parent, "${src.nameWithoutExtension}.txt")
            output.writeText(text)
            ConvertResult(true, output.absolutePath, "Extracted ${text.length} characters")
        } catch (e: Exception) {
            ConvertResult(false, "", "Extract failed: ${e.localizedMessage}")
        }
    }

    /** Combine multiple text files into one. */
    fun mergeTextFiles(inputPaths: List<String>, outputPath: String): ConvertResult {
        return try {
            File(outputPath).bufferedWriter().use { writer ->
                for ((index, path) in inputPaths.withIndex()) {
                    if (index > 0) writer.appendLine("\n--- ${File(path).name} ---\n")
                    File(path).bufferedReader().use { reader ->
                        reader.copyTo(writer)
                    }
                }
            }
            ConvertResult(true, outputPath, "Merged ${inputPaths.size} files")
        } catch (e: Exception) {
            ConvertResult(false, "", "Merge failed: ${e.localizedMessage}")
        }
    }

    /** Convert JSON to formatted/pretty-printed JSON. */
    fun prettyPrintJson(inputPath: String): ConvertResult {
        return try {
            val src = File(inputPath)
            val raw = src.readText()
            val json = org.json.JSONTokener(raw).nextValue()
            val pretty = when (json) {
                is org.json.JSONObject -> json.toString(2)
                is org.json.JSONArray -> json.toString(2)
                else -> raw
            }
            val output = File(src.parent, "${src.nameWithoutExtension}_formatted.json")
            output.writeText(pretty)
            ConvertResult(true, output.absolutePath, "JSON formatted (${output.length() / 1024} KB)")
        } catch (e: Exception) {
            ConvertResult(false, "", "JSON format failed: ${e.localizedMessage}")
        }
    }

    /** Minify a JSON file (remove whitespace). */
    fun minifyJson(inputPath: String): ConvertResult {
        return try {
            val src = File(inputPath)
            val raw = src.readText()
            val json = org.json.JSONTokener(raw).nextValue()
            val minified = json.toString()
            val output = File(src.parent, "${src.nameWithoutExtension}_min.json")
            output.writeText(minified)
            val savings = src.length() - output.length()
            ConvertResult(true, output.absolutePath,
                "Minified (saved ${UndoHelper.formatBytes(savings)})")
        } catch (e: Exception) {
            ConvertResult(false, "", "JSON minify failed: ${e.localizedMessage}")
        }
    }
}
