package com.filecleaner.app.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.view.DragEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.filecleaner.app.data.FileItem
import java.io.File

/**
 * Enables drag-and-drop file moving within the direct browse mode.
 *
 * Long-press a file to start dragging, drop on a folder to move it.
 * Visual feedback: folders highlight when a drag enters them.
 */
object DragDropHelper {

    private const val MIME_FILE_PATH = "text/x-file-path"

    /**
     * Makes a view draggable by long-press with a file path payload.
     */
    fun makeDraggable(view: View, filePath: String) {
        view.setOnLongClickListener {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val clipData = ClipData(
                "file",
                arrayOf(MIME_FILE_PATH),
                ClipData.Item(filePath)
            )
            val shadow = View.DragShadowBuilder(view)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                view.startDragAndDrop(clipData, shadow, null, View.DRAG_FLAG_OPAQUE)
            } else {
                @Suppress("DEPRECATION")
                view.startDrag(clipData, shadow, null, 0)
            }
            true
        }
    }

    /**
     * Makes a view a drop target that accepts file moves.
     * @param targetDirPath The directory path this view represents
     * @param onFileMoved Called when a file is dropped (sourcePath, targetDir)
     */
    fun makeDropTarget(
        view: View,
        targetDirPath: String,
        onFileMoved: (sourcePath: String, targetDir: String) -> Unit
    ) {
        view.setOnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    event.clipDescription?.hasMimeType(MIME_FILE_PATH) == true
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.alpha = 0.7f // Visual feedback
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    v.alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    v.alpha = 1f
                    val sourcePath = event.clipData?.getItemAt(0)?.text?.toString()
                    if (sourcePath != null) {
                        val sourceFile = File(sourcePath)
                        val targetDir = File(targetDirPath)
                        // Don't drop a file into its own parent
                        if (sourceFile.parent != targetDir.absolutePath) {
                            onFileMoved(sourcePath, targetDirPath)
                        }
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }
    }
}
