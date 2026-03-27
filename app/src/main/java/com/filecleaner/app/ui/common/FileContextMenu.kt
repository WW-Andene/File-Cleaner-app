package com.filecleaner.app.ui.common

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.filecleaner.app.ui.common.RoundedDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.utils.security.FileShredder
import com.filecleaner.app.data.FileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.filecleaner.app.data.UserPreferences
import com.filecleaner.app.utils.FileOpener
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.viewmodel.ClipboardManager
import com.filecleaner.app.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.filecleaner.app.utils.styleAsError
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.DateFormat
import java.util.Date

object FileContextMenu {

    interface Callback {
        fun onDelete(item: FileItem)
        fun onRename(item: FileItem, newName: String)
        fun onCompress(item: FileItem)
        fun onExtract(item: FileItem)
        fun onOpenInTree(item: FileItem)
        fun onBrowseFolder(folderPath: String) {}
        fun onCut(item: FileItem) {}
        fun onCopy(item: FileItem) {}
        fun onPaste(targetDirPath: String) {}
        fun onMoveTo(item: FileItem) {}
        fun onSelect(item: FileItem) {}
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
        onSelect: (FileItem) -> Unit = {},
        onRefresh: () -> Unit = {}
    ): Callback = object : Callback {
        override fun onDelete(item: FileItem) { vm.deleteFiles(listOf(item)) }
        override fun onRename(item: FileItem, newName: String) { vm.renameFile(item.path, newName) }
        override fun onCompress(item: FileItem) { vm.compressFile(item.path) }
        override fun onExtract(item: FileItem) { vm.extractArchive(item.path) }
        override fun onOpenInTree(item: FileItem) { onOpenInTree(item) }
        override fun onBrowseFolder(folderPath: String) { vm.requestBrowseFolder(folderPath) }
        override fun onCut(item: FileItem) { vm.setCutFile(item) }
        override fun onCopy(item: FileItem) { vm.setCopyFile(item) }
        override fun onPaste(targetDirPath: String) {
            val entry = vm.clipboardEntry.value ?: return
            when (entry.mode) {
                ClipboardManager.ClipboardMode.CUT -> {
                    vm.moveFile(entry.item.path, targetDirPath)
                    vm.clearClipboard()
                }
                ClipboardManager.ClipboardMode.COPY -> {
                    vm.copyFile(entry.item.path, targetDirPath)
                }
            }
        }
        override fun onMoveTo(item: FileItem) { onMoveTo(item) }
        override fun onSelect(item: FileItem) { onSelect(item) }
        override fun onRefresh() { onRefresh() }
    }

    fun show(context: Context, anchor: View, item: FileItem, callback: Callback, hasClipboard: Boolean = false) {
        val dialog = BottomSheetDialog(context, R.style.Theme_FileCleaner_BottomSheet)
        val contentView = View.inflate(context, R.layout.dialog_file_context, null)

        // Header
        val tvName = contentView.findViewById<TextView>(R.id.tv_file_name)
        val tvInfo = contentView.findViewById<TextView>(R.id.tv_file_info)
        val ivIcon = contentView.findViewById<ImageView>(R.id.iv_file_icon)

        tvName.text = item.name
        tvInfo.text = "${UndoHelper.formatBytes(item.size)} \u2022 ${context.getString(item.category.displayNameRes)}"

        val iconRes = when (item.category) {
            FileCategory.IMAGE -> R.drawable.ic_image
            FileCategory.VIDEO -> R.drawable.ic_video
            FileCategory.AUDIO -> R.drawable.ic_audio
            FileCategory.DOCUMENT -> R.drawable.ic_document
            FileCategory.APK -> R.drawable.ic_apk
            FileCategory.ARCHIVE -> R.drawable.ic_archive
            FileCategory.DOWNLOAD -> R.drawable.ic_download
            FileCategory.OTHER -> R.drawable.ic_file
        }
        ivIcon.setImageResource(iconRes)

        // §G1: Set contentDescription on the header icon
        ivIcon.contentDescription = context.getString(R.string.a11y_file_icon,
            context.getString(item.category.displayNameRes))

        val container = contentView.findViewById<LinearLayout>(R.id.menu_container)
        val res = context.resources
        val buttonHeight = res.getDimensionPixelSize(R.dimen.button_height)
        // §H3: Ensure minimum 48dp touch target for menu items
        val minTouchTarget = res.getDimensionPixelSize(R.dimen.touch_target_min)
        val effectiveHeight = maxOf(buttonHeight, minTouchTarget)
        val spacingXl = res.getDimensionPixelSize(R.dimen.spacing_xl)
        val spacingLg = res.getDimensionPixelSize(R.dimen.spacing_lg)
        val spacingXs = res.getDimensionPixelSize(R.dimen.spacing_xs)
        val iconNav = res.getDimensionPixelSize(R.dimen.icon_nav)
        val strokeDefault = res.getDimensionPixelSize(R.dimen.stroke_default)
        val bodySize = res.getDimension(R.dimen.text_body)

        fun addItem(label: String, iconDrawable: Int, action: () -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    effectiveHeight  // §H3: Ensure minimum 48dp touch target
                )
                setPadding(spacingXl, 0, spacingXl, 0)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }

            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconNav, iconNav)
                setImageResource(iconDrawable)
                setColorFilter(ContextCompat.getColor(context, R.color.textSecondary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                // §G1: Icon is decorative; row has contentDescription
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(icon)

            val text = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = spacingLg
                }
                this.text = label
                setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize)
            }
            row.addView(text)

            container.addView(row)
        }

        fun addDivider() {
            val div = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, strokeDefault
                ).apply {
                    topMargin = spacingXs
                    bottomMargin = spacingXs
                    marginStart = spacingXl
                    marginEnd = spacingXl
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.borderDefault))
            }
            container.addView(div)
        }

        // -- Menu items --
        val isFolder = item.file.isDirectory
        if (isFolder) {
            addItem(context.getString(R.string.ctx_browse_folder), R.drawable.ic_nav_browse) {
                callback.onBrowseFolder(item.path)
            }
        } else {
            addItem(context.getString(R.string.ctx_open), R.drawable.ic_open) {
                FileOpener.open(context, item.file)
            }
            addItem(context.getString(R.string.ctx_share), R.drawable.ic_share) {
                shareFile(context, item)
            }
            addItem(context.getString(R.string.ctx_preview), R.drawable.ic_preview) {
                FilePreviewDialog.show(context, item)
            }
            val parentDir = item.file.parent
            if (parentDir != null) {
                addItem(context.getString(R.string.ctx_browse_folder), R.drawable.ic_nav_browse) {
                    callback.onBrowseFolder(parentDir)
                }
            }
        }

        addDivider()

        addItem(context.getString(R.string.ctx_copy), R.drawable.ic_copy) {
            callback.onCopy(item)
            Snackbar.make(anchor, context.getString(R.string.copy_hint, item.name), Snackbar.LENGTH_SHORT).show()
        }
        addItem(context.getString(R.string.ctx_cut), R.drawable.ic_cut) {
            callback.onCut(item)
            Snackbar.make(anchor, context.getString(R.string.cut_hint, item.name), Snackbar.LENGTH_SHORT).show()
        }
        if (hasClipboard) {
            val targetDir = File(item.path).parent
            if (targetDir != null) {
                addItem(context.getString(R.string.ctx_paste_here), R.drawable.ic_paste) {
                    callback.onPaste(targetDir)
                }
            }
        }
        addItem(context.getString(R.string.ctx_move_to), R.drawable.ic_move) {
            callback.onMoveTo(item)
        }
        addItem(context.getString(R.string.ctx_rename), R.drawable.ic_edit) {
            val editText = EditText(context).apply {
                setText(item.name)
                selectAll()
            }
            RoundedDialogBuilder(context)
                .setTitle(context.getString(R.string.ctx_rename))
                .setView(editText)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newName = editText.text.toString().trim()
                    // C2: UI-layer defense-in-depth for invalid filesystem characters
                    val invalidChars = charArrayOf('/', '\u0000', ':', '*', '?', '"', '<', '>', '|')
                    if (newName.isNotEmpty() && invalidChars.any { it in newName }) {
                        Snackbar.make(anchor, context.getString(R.string.op_invalid_name), Snackbar.LENGTH_SHORT).styleAsError().show()
                    } else if (newName.isNotEmpty() && newName != item.name) {
                        callback.onRename(item, newName)
                    }
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .show()
        }

        addDivider()

        addItem(context.getString(R.string.ctx_share), R.drawable.ic_share) {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", item.file
            )
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.extension) ?: "*/*"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.ctx_share)))
        }
        addItem(context.getString(R.string.ctx_compress), R.drawable.ic_archive) {
            callback.onCompress(item)
        }
        // Show convert option for images, PDFs, videos, audio, and text/code files
        val convertibleTextExts = setOf(
            "txt", "log", "csv", "json", "xml", "html", "htm", "md", "markdown",
            "py", "js", "ts", "kt", "java", "c", "cpp", "rs", "go", "rb", "php",
            "sh", "sql", "css", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "swift", "dart", "lua", "tex", "diff", "patch", "gradle", "makefile"
        )
        if (item.category == FileCategory.IMAGE || item.category == FileCategory.VIDEO ||
            item.category == FileCategory.AUDIO || item.extension == "pdf" ||
            item.extension in convertibleTextExts) {
            addItem(context.getString(R.string.ctx_convert), R.drawable.ic_file) {
                ConvertDialog.show(context, item) { result ->
                    val msg = if (result.success) result.message
                    else context.getString(R.string.convert_failed, result.message)
                    Snackbar.make(anchor, msg, Snackbar.LENGTH_SHORT).show()
                    if (result.success) callback.onRefresh()
                }
            }
        }
        if (item.category == FileCategory.ARCHIVE) {
            addItem(context.getString(R.string.ctx_extract), R.drawable.ic_archive) {
                callback.onExtract(item)
            }
        }

        addDivider()

        val isFav = try { UserPreferences.isFavorite(item.path) } catch (_: Exception) { false }
        addItem(context.getString(if (isFav) R.string.ctx_unstar else R.string.ctx_star),
            R.drawable.ic_star) {
            UserPreferences.toggleFavorite(item.path)
            val nowFav = UserPreferences.isFavorite(item.path)
            Snackbar.make(anchor, context.getString(
                if (nowFav) R.string.favorite_added else R.string.favorite_removed, item.name),
                Snackbar.LENGTH_SHORT).show()
        }
        val isProt = try { UserPreferences.isProtected(item.path) } catch (_: Exception) { false }
        addItem(context.getString(if (isProt) R.string.ctx_unprotect else R.string.ctx_protect),
            R.drawable.ic_lock) {
            UserPreferences.toggleProtected(item.path)
            val nowProt = UserPreferences.isProtected(item.path)
            Snackbar.make(anchor, context.getString(
                if (nowProt) R.string.protected_added else R.string.protected_removed, item.name),
                Snackbar.LENGTH_SHORT).show()
        }

        addDivider()

        addItem(context.getString(R.string.ctx_select), R.drawable.ic_select_all) {
            callback.onSelect(item)
        }
        addItem(context.getString(R.string.ctx_open_in_tree), R.drawable.ic_folder) {
            callback.onOpenInTree(item)
        }
        // Quick copy path
        addItem(context.getString(R.string.ctx_copy_path), R.drawable.ic_info) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("File path", item.path))
            android.widget.Toast.makeText(context, context.getString(R.string.hash_copied), android.widget.Toast.LENGTH_SHORT).show()
        }
        // Set as wallpaper (images only)
        if (item.category == FileCategory.IMAGE) {
            addItem(context.getString(R.string.ctx_set_wallpaper), R.drawable.ic_image) {
                setAsWallpaper(context, item)
            }
        }
        // Encrypt/Decrypt
        if (item.name.endsWith(".encrypted")) {
            addItem(context.getString(R.string.decrypt_title), R.drawable.ic_info) {
                showEncryptDecryptDialog(context, item, decrypt = true)
            }
        } else {
            addItem(context.getString(R.string.encrypt_title), R.drawable.ic_info) {
                showEncryptDecryptDialog(context, item, decrypt = false)
            }
        }
        // Secure delete (file shredder)
        addItem(context.getString(R.string.file_shredder_title), R.drawable.ic_delete) {
            RoundedDialogBuilder(context)
                .setTitle(context.getString(R.string.file_shredder_title))
                .setMessage(context.getString(R.string.file_shredder_confirm, item.name))
                .setPositiveButton(context.getString(R.string.ctx_delete)) { _, _ ->
                    CoroutineScope(Dispatchers.Main).launch {
                        FileShredder.shred(item.path)
                        android.widget.Toast.makeText(context,
                            context.getString(R.string.file_shredded),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .show()
        }
        // EXIF for images
        if (item.category == com.filecleaner.app.data.FileCategory.IMAGE) {
            addItem(context.getString(R.string.exif_title), R.drawable.ic_info) {
                showExifDialog(context, item)
            }
        }
        addItem(context.getString(R.string.hash_title), R.drawable.ic_info) {
            showHashDialog(context, item)
        }
        addItem(context.getString(R.string.ctx_properties), R.drawable.ic_info) {
            showProperties(context, item)
        }

        // Delete at the end, styled distinctly
        val deleteRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                effectiveHeight  // §H3: Ensure minimum 48dp touch target
            )
            setPadding(spacingXl, 0, spacingXl, 0)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.ctx_delete)
            setOnClickListener {
                dialog.dismiss()
                val undoSec = try { UserPreferences.undoTimeoutMs / 1000 } catch (_: Exception) { 8 }
                val detail = context.resources.getQuantityString(
                    R.plurals.confirm_delete_detail, 1, 1, UndoHelper.formatBytes(item.size), undoSec)
                RoundedDialogBuilder(context)
                    .setTitle(context.resources.getQuantityString(R.plurals.delete_n_files_title, 1, 1))
                    .setMessage("${item.name}\n\n$detail")
                    .setPositiveButton(context.getString(R.string.delete)) { _, _ ->
                        callback.onDelete(item)
                    }
                    .setNegativeButton(context.getString(R.string.cancel), null)
                    .show()
            }
        }
        val deleteIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconNav, iconNav)
            setImageResource(R.drawable.ic_delete)
            setColorFilter(ContextCompat.getColor(context, R.color.colorError))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            // §G1: Icon is decorative; row has contentDescription
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        deleteRow.addView(deleteIcon)
        val deleteText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = spacingLg
            }
            text = context.getString(R.string.ctx_delete)
            setTextColor(ContextCompat.getColor(context, R.color.colorError))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, bodySize)
            typeface = Typeface.DEFAULT_BOLD
        }
        deleteRow.addView(deleteText)
        container.addView(deleteRow)

        dialog.setContentView(contentView)
        dialog.show()
    }

    private fun showProperties(context: Context, item: FileItem) {
        // F-081: Use centralized date formatting
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.extension) ?: "unknown"
        val parentDir = File(item.path).parent ?: "/"

        val info = buildString {
            appendLine("${context.getString(R.string.prop_name)}: ${item.name}")
            appendLine("${context.getString(R.string.prop_path)}: ${item.path}")
            appendLine("${context.getString(R.string.prop_size)}: ${UndoHelper.formatBytes(item.size)} (${item.size} bytes)")
            appendLine("${context.getString(R.string.prop_modified)}: ${com.filecleaner.app.utils.DateFormatUtils.formatDateTime(item.lastModified)}")
            appendLine("${context.getString(R.string.prop_category)}: ${context.getString(item.category.displayNameRes)}")
            appendLine("${context.getString(R.string.prop_type)}: $mimeType")
            appendLine("${context.getString(R.string.prop_folder)}: $parentDir")
        }

        RoundedDialogBuilder(context)
            .setTitle(context.getString(R.string.ctx_properties))
            .setMessage(info)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.ctx_copy_path) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("File path", item.path))
            }
            .show()
    }

    /** Show encrypt/decrypt password dialog. */
    private fun showEncryptDecryptDialog(context: Context, item: FileItem, decrypt: Boolean) {
        val input = android.widget.EditText(context).apply {
            hint = context.getString(R.string.encrypt_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val pad = context.resources.getDimensionPixelSize(R.dimen.spacing_xxl)
            setPadding(pad, pad, pad, context.resources.getDimensionPixelSize(R.dimen.spacing_sm))
        }
        val title = if (decrypt) R.string.decrypt_title else R.string.encrypt_title
        RoundedDialogBuilder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text.toString()
                if (password.isEmpty()) return@setPositiveButton
                CoroutineScope(Dispatchers.Main).launch {
                    val result = if (decrypt)
                        com.filecleaner.app.utils.security.FileEncryptor.decrypt(item.path, password)
                    else
                        com.filecleaner.app.utils.security.FileEncryptor.encrypt(item.path, password)
                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Show EXIF metadata for images. */
    private fun showExifDialog(context: Context, item: FileItem) {
        val data = com.filecleaner.app.utils.ExifReader.read(item.path)
        val message = if (data != null) {
            com.filecleaner.app.utils.ExifReader.formatReadable(data)
        } else {
            context.getString(R.string.exif_no_data)
        }
        RoundedDialogBuilder(context)
            .setTitle(context.getString(R.string.exif_title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Show file hash (MD5, SHA-1, SHA-256) with copy button. */
    private fun showHashDialog(context: Context, item: FileItem) {
        // Show loading dialog first
        val loadingDialog = RoundedDialogBuilder(context)
            .setTitle(context.getString(R.string.hash_title))
            .setMessage(context.getString(R.string.hash_computing, "SHA-256"))
            .setCancelable(false)
            .show()

        CoroutineScope(Dispatchers.Main).launch {
            val results = mutableListOf<com.filecleaner.app.utils.security.FileHasher.HashResult>()
            for (algo in com.filecleaner.app.utils.security.FileHasher.Algorithm.entries) {
                val result = com.filecleaner.app.utils.security.FileHasher.computeHash(item.path, algo)
                results.add(result)
            }
            val text = results.joinToString("\n\n") { r ->
                "${r.algorithm.label}:\n${r.hash}"
            }

            if (loadingDialog.isShowing) loadingDialog.dismiss()

            // Show results dialog with copy button
            RoundedDialogBuilder(context)
                .setTitle(context.getString(R.string.hash_title))
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(context.getString(R.string.hash_copy)) { _, _ ->
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Hash", text))
                }
                .show()
        }
    }

    /** Set image as device wallpaper. */
    private fun setAsWallpaper(context: Context, item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("mimeType", "image/*")
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.ctx_set_wallpaper)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Cannot set wallpaper: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Share a file via Android share sheet. */
    private fun shareFile(context: Context, item: FileItem) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", item.file)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(item.extension) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.ctx_share)))
    }
}
