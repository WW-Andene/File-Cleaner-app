package com.filecleaner.app.utils

import android.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads EXIF metadata from image files for the photo info viewer.
 * Extracts camera info, GPS coordinates, date, dimensions, etc.
 */
object ExifReader {

    data class ExifData(
        val make: String?,
        val model: String?,
        val dateTime: String?,
        val width: Int,
        val height: Int,
        val iso: String?,
        val focalLength: String?,
        val aperture: String?,
        val exposureTime: String?,
        val flash: String?,
        val latitude: Double?,
        val longitude: Double?,
        val orientation: Int,
        val fileSize: Long
    ) {
        val hasGps: Boolean get() = latitude != null && longitude != null
        val cameraInfo: String get() = listOfNotNull(make, model).joinToString(" ").ifEmpty { "Unknown" }
        val resolution: String get() = if (width > 0 && height > 0) "${width}×${height}" else "Unknown"
    }

    /** Read EXIF data from an image file. Returns null if not a valid image. */
    fun read(filePath: String): ExifData? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null
            val exif = ExifInterface(filePath)

            val latLong = exif.latLong

            ExifData(
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                flash = formatFlash(exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)),
                latitude = latLong?.get(0),
                longitude = latLong?.get(1),
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL),
                fileSize = file.length()
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Formats EXIF data as a human-readable multi-line string. */
    fun formatReadable(data: ExifData): String = buildString {
        appendLine("Camera: ${data.cameraInfo}")
        appendLine("Resolution: ${data.resolution}")
        if (data.dateTime != null) appendLine("Date: ${data.dateTime}")
        if (data.iso != null) appendLine("ISO: ${data.iso}")
        if (data.aperture != null) appendLine("Aperture: f/${data.aperture}")
        if (data.exposureTime != null) appendLine("Exposure: ${data.exposureTime}s")
        if (data.focalLength != null) appendLine("Focal Length: ${data.focalLength}mm")
        if (data.flash != null) appendLine("Flash: ${data.flash}")
        if (data.hasGps) appendLine("GPS: ${data.latitude}, ${data.longitude}")
        appendLine("Size: ${UndoHelper.formatBytes(data.fileSize)}")
    }

    private fun formatFlash(value: Int): String? = when (value) {
        0 -> "Off"
        1 -> "Fired"
        5 -> "Fired, no return"
        7 -> "Fired, return"
        -1 -> null
        else -> "Mode $value"
    }
}
