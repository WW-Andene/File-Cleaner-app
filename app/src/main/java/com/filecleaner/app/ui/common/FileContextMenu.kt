package com.filecleaner.app.ui.common

import android.content.Context
import android.content.Intent
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.utils.FileOpener
import com.filecleaner.app.viewmodel.MainViewModel
import java.io.File

object FileContextMenu {

    interface Callback {
        fun onDelete(item: FileItem)
        fun onRename(item: FileItem, newName: String)
        fun onCompress(item: FileItem)
        fun onExtract(item: FileItem)
        fun onOpenInTree(item: FileItem)
        fun onCut(item: FileItem) {}
        fun onCopy(item: FileItem) {}
        fun onPaste(targetDirPath: String) {}
        fun onMoveTo(item: FileItem) {}
        fun onRefresh()
    }

    /**
     * Creates the standard Callback wired to ViewModel operations.
     * [onMoveTo] must be provided by the fragment to show the directory picker.
     */
    fun defaultCallback(
        vm: MainViewModel,
        onOpenInTree: (FileItem) -> Unit = { vm.requestTreeHighlight(it.path) },
        onMoveTo: (FileItem) -> Unit = {},
        onRefresh: () -> Unit = {}
    ): Callback = object : Callback {
        override fun onDelete(item: FileItem) { vm.deleteFiles(listOf(item)) }
        override fun onRename(item: FileItem, newName: String) { vm.renameFile(item.path, newName) }
        override fun onCompress(item: FileItem) { vm.compressFile(item.path) }
        override fun onExtract(item: FileItem) { vm.extractArchive(item.path) }
        override fun onOpenInTree(item: FileItem) { onOpenInTree(item) }
        override fun onCut(item: FileItem) { vm.setCutFile(item) }
        override fun onCopy(item: FileItem) { vm.setCopyFile(item) }
        override fun onPaste(targetDirPath: String) {
            val entry = vm.clipboardEntry.value ?: return
            when (entry.mode) {
                MainViewModel.ClipboardMode.CUT -> {
                    vm.moveFile(entry.item.path, targetDirPath)
                    vm.clearClipboard()
                }
                MainViewModel.ClipboardMode.COPY -> {
                    vm.copyFile(entry.item.path, targetDirPath)
                    // Don't clear clipboard on copy — allows pasting multiple times
                }
            }
        }
        override fun onMoveTo(item: FileItem) { onMoveTo(item) }
        override fun onRefresh() { onRefresh() }
    }

    fun show(context: Context, anchor: View, item: FileItem, callback: Callback, hasClipboard: Boolean = false) {
        val popup = PopupMenu(context, anchor)
        var order = 0
        popup.menu.apply {
            add(0, 1, order++, context.getString(R.string.ctx_open))
            add(0, 10, order++, context.getString(R.string.ctx_copy))
            add(0, 5, order++, context.getString(R.string.ctx_cut))
            if (hasClipboard) {
                val targetDir = File(item.path).parent
                if (targetDir != null) {
                    add(0, 9, order++, context.getString(R.string.ctx_paste_here))
                }
            }
            add(0, 11, order++, context.getString(R.string.ctx_move_to))
            add(0, 3, order++, context.getString(R.string.ctx_rename))
            add(0, 4, order++, context.getString(R.string.ctx_share))
            add(0, 6, order++, context.getString(R.string.ctx_compress))
            if (item.category == FileCategory.ARCHIVE) {
                add(0, 7, order++, context.getString(R.string.ctx_extract))
            }
            add(0, 2, order++, context.getString(R.string.ctx_delete))
            add(0, 8, order++, context.getString(R.string.ctx_open_in_tree))
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> { // Open
                    FileOpener.open(context, item.file)
                    true
                }
                2 -> { // Delete
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.confirm_delete_title))
                        .setMessage(context.getString(R.string.confirm_delete_message))
                        .setPositiveButton(context.getString(R.string.delete)) { _, _ ->
                            callback.onDelete(item)
                        }
                        .setNegativeButton(context.getString(R.string.cancel), null)
                        .show()
                    true
                }
                3 -> { // Rename
                    val editText = EditText(context).apply {
                        setText(item.name)
                        selectAll()
                    }
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.ctx_rename))
                        .setView(editText)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val newName = editText.text.toString().trim()
                            if (newName.isNotEmpty() && newName != item.name) {
                                callback.onRename(item, newName)
                            }
                        }
                        .setNegativeButton(context.getString(R.string.cancel), null)
                        .show()
                    true
                }
                4 -> { // Share
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", item.file
                    )
                    val ext = item.name.substringAfterLast('.', "").lowercase()
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.ctx_share)))
                    true
                }
                5 -> { // Cut
                    callback.onCut(item)
                    Toast.makeText(context,
                        context.getString(R.string.cut_hint, item.name),
                        Toast.LENGTH_SHORT).show()
                    true
                }
                6 -> { // Compress
                    callback.onCompress(item)
                    true
                }
                7 -> { // Extract
                    callback.onExtract(item)
                    true
                }
                8 -> { // Show in Tree
                    callback.onOpenInTree(item)
                    true
                }
                9 -> { // Paste here
                    val targetDir = File(item.path).parent
                    if (targetDir != null) {
                        callback.onPaste(targetDir)
                    }
                    true
                }
                10 -> { // Copy
                    callback.onCopy(item)
                    Toast.makeText(context,
                        context.getString(R.string.copy_hint, item.name),
                        Toast.LENGTH_SHORT).show()
                    true
                }
                11 -> { // Move to...
                    callback.onMoveTo(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
