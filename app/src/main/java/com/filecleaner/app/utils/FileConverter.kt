package com.filecleaner.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Format conversion utilities using only Android SDK APIs (no external dependencies).
 *
 * Supported conversions:
 * - Image -> Image (PNG, JPG, WEBP)
 * - Image(s) -> PDF
 * - PDF -> Images (one PNG per page)
 */
object FileConverter {

    data class ConvertResult(val success: Boolean, val outputPath: String, val message: String)

    /** Supported image output formats. */
    enum class ImageFormat(val extension: String, val compressFormat: Bitmap.CompressFormat) {
        PNG("png", Bitmap.CompressFormat.PNG),
        JPG("jpg", Bitmap.CompressFormat.JPEG),
        WEBP("webp", Bitmap.CompressFormat.WEBP_LOSSY)
    }

    /** Convert an image file to another image format. */
    fun convertImage(inputPath: String, outputFormat: ImageFormat, quality: Int = 90): ConvertResult {
        return try {
            val src = File(inputPath)
            if (!src.exists()) return ConvertResult(false, "", "Source file not found")

            val bitmap = BitmapFactory.decodeFile(inputPath)
                ?: return ConvertResult(false, "", "Cannot decode image")

            val outputName = "${src.nameWithoutExtension}.${outputFormat.extension}"
            val outputFile = File(src.parent, outputName)
            // Avoid overwriting source
            val finalFile = if (outputFile.absolutePath == src.absolutePath) {
                File(src.parent, "${src.nameWithoutExtension}_converted.${outputFormat.extension}")
            } else outputFile

            finalFile.outputStream().buffered().use { out ->
                bitmap.compress(outputFormat.compressFormat, quality, out)
            }
            bitmap.recycle()
            ConvertResult(true, finalFile.absolutePath, "Converted to ${finalFile.name}")
        } catch (e: Exception) {
            ConvertResult(false, "", "Conversion failed: ${e.localizedMessage}")
        }
    }

    /** Convert one or more images to a multi-page PDF. */
    fun imagesToPdf(imagePaths: List<String>, outputPath: String): ConvertResult {
        return try {
            val doc = PdfDocument()
            for ((index, path) in imagePaths.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(path)
                    ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                doc.finishPage(page)
                bitmap.recycle()
            }
            File(outputPath).outputStream().buffered().use { out ->
                doc.writeTo(out)
            }
            doc.close()
            ConvertResult(true, outputPath, "Created ${File(outputPath).name}")
        } catch (e: Exception) {
            ConvertResult(false, "", "PDF creation failed: ${e.localizedMessage}")
        }
    }

    /** Extract all pages of a PDF to individual PNG images. */
    fun pdfToImages(pdfPath: String, outputDir: String): ConvertResult {
        return try {
            val pdfFile = File(pdfPath)
            if (!pdfFile.exists()) return ConvertResult(false, "", "PDF not found")

            val outDir = File(outputDir)
            outDir.mkdirs()

            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val pageCount = renderer.pageCount

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val scale = 2
                val bitmap = Bitmap.createBitmap(
                    page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageFile = File(outDir, "${pdfFile.nameWithoutExtension}_page_${i + 1}.png")
                pageFile.outputStream().buffered().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
            }

            renderer.close()
            fd.close()

            ConvertResult(true, outDir.absolutePath, "Extracted $pageCount pages to ${outDir.name}/")
        } catch (e: Exception) {
            ConvertResult(false, "", "PDF extraction failed: ${e.localizedMessage}")
        }
    }
}
