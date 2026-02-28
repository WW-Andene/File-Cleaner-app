package com.filecleaner.app.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.File

enum class FileCategory(val displayName: String, val emoji: String) {
    IMAGE("Images", "\uD83D\uDDBC\uFE0F"),
    VIDEO("Videos", "\uD83C\uDFAC"),
    AUDIO("Audio", "\uD83C\uDFB5"),
    DOCUMENT("Documents", "\uD83D\uDCC4"),
    APK("APKs", "\uD83D\uDCE6"),
    ARCHIVE("Archives", "\uD83D\uDDDC\uFE0F"),
    DOWNLOAD("Downloads", "\u2B07\uFE0F"),
    OTHER("Other", "\uD83D\uDCC1")
}

@Parcelize
data class FileItem(
    val path: String,
    val name: String,
    val size: Long,          // bytes
    val lastModified: Long,  // epoch ms
    val category: FileCategory,
    val duplicateGroup: Int = -1  // -1 = not a duplicate
) : Parcelable {

    val file: File get() = File(path)

    val sizeReadable: String get() = when {
        size >= 1_073_741_824 -> "%.1f GB".format(size / 1_073_741_824.0)
        size >= 1_048_576     -> "%.1f MB".format(size / 1_048_576.0)
        size >= 1_024         -> "%.0f KB".format(size / 1_024.0)
        else                  -> "$size B"
    }
}
