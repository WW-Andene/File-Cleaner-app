package com.filecleaner.app.utils

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Lists available storage devices (internal, SD card, USB) for switching
 * in the file browser.
 */
object StorageDeviceSwitcher {

    data class StorageDevice(
        val path: String,
        val name: String,
        val totalBytes: Long,
        val freeBytes: Long,
        val isRemovable: Boolean,
        val isPrimary: Boolean
    )

    /** Returns all mounted storage devices. */
    fun getDevices(context: Context): List<StorageDevice> {
        val devices = mutableListOf<StorageDevice>()

        // Primary internal storage
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        val primaryStat = android.os.StatFs(primary.absolutePath)
        devices.add(StorageDevice(
            path = primary.absolutePath,
            name = "Internal Storage",
            totalBytes = primaryStat.totalBytes,
            freeBytes = primaryStat.freeBytes,
            isRemovable = false,
            isPrimary = true
        ))

        // Additional volumes (SD card, USB)
        try {
            val sm = context.getSystemService(StorageManager::class.java)
            for (volume in sm.storageVolumes) {
                if (volume.isPrimary || volume.state != "mounted") continue
                val volumePath = try {
                    volume.directory?.absolutePath
                } catch (_: Exception) { null } ?: continue

                val stat = try { android.os.StatFs(volumePath) } catch (_: Exception) { continue }
                devices.add(StorageDevice(
                    path = volumePath,
                    name = volume.getDescription(context) ?: "External Storage",
                    totalBytes = stat.totalBytes,
                    freeBytes = stat.freeBytes,
                    isRemovable = volume.isRemovable,
                    isPrimary = false
                ))
            }
        } catch (_: Exception) { }

        return devices
    }

    /** Returns true if more than one storage device is available. */
    fun hasMultipleDevices(context: Context): Boolean = getDevices(context).size > 1
}
