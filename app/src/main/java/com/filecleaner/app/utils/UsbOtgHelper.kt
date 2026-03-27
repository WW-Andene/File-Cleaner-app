package com.filecleaner.app.utils

import android.content.Context
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Detects and provides access to USB OTG (On-The-Go) storage devices.
 * Works on Android 10+ (API 29+) using StorageManager.
 */
object UsbOtgHelper {

    data class ExternalVolume(
        val path: String,
        val description: String,
        val isRemovable: Boolean,
        val isEmulated: Boolean
    )

    /** Returns all mounted external storage volumes (USB drives, SD cards). */
    fun getExternalVolumes(context: Context): List<ExternalVolume> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes
            .filter { it.state == "mounted" }
            .mapNotNull { volume ->
                val path = getVolumePath(volume) ?: return@mapNotNull null
                ExternalVolume(
                    path = path,
                    description = volume.getDescription(context) ?: "External Storage",
                    isRemovable = volume.isRemovable,
                    isEmulated = volume.isEmulated
                )
            }
            .filter { !it.isEmulated } // Exclude internal emulated storage
    }

    /** Returns true if any USB/external volume is connected. */
    fun hasExternalStorage(context: Context): Boolean =
        getExternalVolumes(context).isNotEmpty()

    private fun getVolumePath(volume: StorageVolume): String? {
        return try {
            val getPath = StorageVolume::class.java.getMethod("getPath")
            getPath.invoke(volume) as? String
        } catch (_: Exception) {
            try {
                volume.directory?.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }
}
