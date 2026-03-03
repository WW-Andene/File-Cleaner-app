package com.filecleaner.app.ui.common

import android.content.Context
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.utils.FileConverter

/**
 * Dialog to choose a target format for file conversion.
 * Shows only applicable formats based on the source file type.
 */
object ConvertDialog {

    data class ConvertAction(val label: String, val action: () -> FileConverter.ConvertResult)

    fun show(context: Context, item: FileItem, onResult: (FileConverter.ConvertResult) -> Unit) {
        val actions = buildActions(item)
        if (actions.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.convert_title))
                .setMessage(context.getString(R.string.convert_unsupported))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = actions.map { it.label }
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, labels)

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.convert_title))
            .setAdapter(adapter) { _, which ->
                val result = actions[which].action()
                onResult(result)
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    private fun buildActions(item: FileItem): List<ConvertAction> {
        val actions = mutableListOf<ConvertAction>()
        val ext = item.extension

        if (item.category == FileCategory.IMAGE) {
            // Image -> other image formats
            for (fmt in FileConverter.ImageFormat.entries) {
                if (fmt.extension != ext) {
                    actions.add(ConvertAction("${fmt.extension.uppercase()} (.${fmt.extension})") {
                        FileConverter.convertImage(item.path, fmt)
                    })
                }
            }
            // Image -> PDF
            actions.add(ConvertAction("PDF (.pdf)") {
                val outputPath = "${item.file.parent}/${item.file.nameWithoutExtension}.pdf"
                FileConverter.imagesToPdf(listOf(item.path), outputPath)
            })
        }

        if (ext == "pdf") {
            // PDF -> Images
            actions.add(ConvertAction("PNG images (one per page)") {
                val outputDir = "${item.file.parent}/${item.file.nameWithoutExtension}_pages"
                FileConverter.pdfToImages(item.path, outputDir)
            })
        }

        return actions
    }
}
