package com.filecleaner.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.utils.FileConverter
import com.filecleaner.app.ui.common.RoundedDialogBuilder

/**
 * Material Design conversion dialog for files.
 *
 * Shows contextually appropriate conversion options based on the source file type,
 * with clear descriptions explaining what each option does. For video files, provides
 * a seek bar to choose the exact timestamp for frame extraction instead of the
 * misleading "10s/20s frames" options.
 *
 * Supported conversions:
 * - Images: convert to PNG/JPG/WEBP/BMP, resize, export to PDF
 * - PDFs: extract pages as PNG or JPG images
 * - Videos: extract a single frame at a chosen timestamp, extract evenly-spaced key frames
 * - Audio: extract embedded album art as PNG or JPG
 * - Text/Code/Markdown/HTML/CSV: convert to PDF, CSV to formatted text table
 */
object ConvertDialog {

    /** Extensions that can be converted to PDF via text rendering. */
    private val TEXT_CONVERTIBLE = setOf(
        "txt", "log", "ini", "cfg", "conf", "properties",
        "yml", "yaml", "toml", "env",
        "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h",
        "rs", "go", "rb", "php", "swift", "dart", "lua", "r",
        "sh", "bash", "bat", "ps1",
        "sql", "graphql", "proto",
        "makefile", "cmake", "dockerfile",
        "tex", "latex", "diff", "patch",
        "xml", "json", "json5", "jsonc", "jsonl",
        "css", "scss", "sass", "less",
        "gradle", "groovy", "scala"
    )

    fun show(context: Context, item: FileItem, onResult: (FileConverter.ConvertResult) -> Unit) {
        val ext = item.extension
        val category = item.category

        // For video files, show the special video conversion dialog with seek bar
        if (category == FileCategory.VIDEO) {
            VideoConvertDialog.show(context, item, onResult)
            return
        }

        // For images, show the image conversion dialog with resize option
        if (category == FileCategory.IMAGE) {
            showImageConvertDialog(context, item, onResult)
            return
        }

        // For all other types, build a list of conversion options
        val options = buildOptionsForNonMediaFile(context, item)
        if (options.isEmpty()) {
            RoundedDialogBuilder(context)
                .setTitle(context.getString(R.string.convert_title))
                .setMessage(context.getString(R.string.convert_unsupported))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        ConvertDialogUtils.showOptionsList(context, options, onResult)
    }

    // =========================================================================
    // IMAGE CONVERSION DIALOG
    // =========================================================================

    private fun showImageConvertDialog(context: Context, item: FileItem, onResult: (FileConverter.ConvertResult) -> Unit) {
        val options = mutableListOf<ConvertOption>()

        // Image format conversions
        for (fmt in FileConverter.ImageFormat.entries) {
            val targetExt = if (fmt == FileConverter.ImageFormat.WEBP_LOSSLESS) "webp" else fmt.extension
            if (targetExt != item.extension || fmt == FileConverter.ImageFormat.WEBP_LOSSLESS) {
                if (fmt == FileConverter.ImageFormat.WEBP && item.extension == "webp") continue
                if (fmt == FileConverter.ImageFormat.WEBP_LOSSLESS && item.extension == "webp") continue
                options.add(ConvertOption(
                    title = context.getString(R.string.convert_to_format, fmt.label),
                    description = context.getString(R.string.convert_image_desc, fmt.label, fmt.extension),
                    action = { FileConverter.convertImage(item.path, fmt) }
                ))
            }
        }

        // Image to PDF
        options.add(ConvertOption(
            title = context.getString(R.string.convert_to_pdf),
            description = context.getString(R.string.convert_image_to_pdf_desc),
            action = {
                val outputPath = "${item.file.parent}/${item.file.nameWithoutExtension}.pdf"
                FileConverter.imagesToPdf(listOf(item.path), outputPath)
            }
        ))

        // Resize option -- handled specially with a sub-dialog
        options.add(ConvertOption(
            title = context.getString(R.string.convert_resize),
            description = context.getString(R.string.convert_resize_desc),
            action = null // Handled by custom click
        ))

        // Image transforms
        options.add(ConvertOption(
            title = context.getString(R.string.convert_rotate_90),
            description = context.getString(R.string.convert_rotate_desc),
            action = { FileConverter.rotateImage(item.path, 90f) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_rotate_180),
            description = context.getString(R.string.convert_rotate_desc),
            action = { FileConverter.rotateImage(item.path, 180f) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_flip_h),
            description = context.getString(R.string.convert_flip_desc),
            action = { FileConverter.flipImage(item.path, horizontal = true) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_flip_v),
            description = context.getString(R.string.convert_flip_desc),
            action = { FileConverter.flipImage(item.path, vertical = false) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_crop_square),
            description = context.getString(R.string.convert_crop_desc),
            action = { FileConverter.cropToAspectRatio(item.path, 1, 1) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_crop_16_9),
            description = context.getString(R.string.convert_crop_desc),
            action = { FileConverter.cropToAspectRatio(item.path, 16, 9) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_watermark),
            description = context.getString(R.string.convert_watermark_desc),
            action = { FileConverter.addWatermark(item.path, "\u00A9 ${java.time.Year.now().value}") }
        ))
        // Color operations
        options.add(ConvertOption(
            title = context.getString(R.string.convert_grayscale),
            description = context.getString(R.string.convert_grayscale_desc),
            action = { FileConverter.toGrayscale(item.path) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_invert),
            description = context.getString(R.string.convert_invert_desc),
            action = { FileConverter.invertColors(item.path) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_brighten),
            description = context.getString(R.string.convert_brighten_desc),
            action = { FileConverter.adjustBrightness(item.path, 1.3f) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_darken),
            description = context.getString(R.string.convert_darken_desc),
            action = { FileConverter.adjustBrightness(item.path, 0.7f) }
        ))
        options.add(ConvertOption(
            title = context.getString(R.string.convert_to_base64),
            description = context.getString(R.string.convert_base64_desc),
            action = { FileConverter.imageToBase64(item.path) }
        ))

        showOptionsListWithResize(context, item, options, onResult)
    }

    /**
     * Shows the options list for images, with special handling for the resize option
     * which opens a sub-dialog to choose dimensions.
     */
    private fun showOptionsListWithResize(
        context: Context,
        item: FileItem,
        options: List<ConvertOption>,
        onResult: (FileConverter.ConvertResult) -> Unit
    ) {
        val spacingSm = context.resources.getDimensionPixelSize(R.dimen.spacing_sm)
        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, spacingSm, 0, spacingSm)
        }
        scrollView.addView(container)

        val dialog = RoundedDialogBuilder(context)
            .setTitle(context.getString(R.string.convert_title))
            .setView(scrollView)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()

        for (option in options) {
            val row = ConvertDialogUtils.buildOptionRow(context, option)
            row.setOnClickListener {
                dialog.dismiss()
                if (option.action != null) {
                    ConvertDialogUtils.runConversion(context, option.action, onResult)
                } else {
                    // This is the resize option -- show resize sub-dialog
                    showResizeDialog(context, item, onResult)
                }
            }
            container.addView(row)
        }

        dialog.show()
    }

    private fun showResizeDialog(context: Context, item: FileItem, onResult: (FileConverter.ConvertResult) -> Unit) {
        val spacingSm = context.resources.getDimensionPixelSize(R.dimen.spacing_sm)
        val spacingMd = context.resources.getDimensionPixelSize(R.dimen.spacing_md)
        val spacingLg = context.resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val spacingXxl = context.resources.getDimensionPixelSize(R.dimen.spacing_xxl)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacingXxl, spacingLg, spacingXxl, spacingSm)
        }

        val descText = TextView(context).apply {
            text = context.getString(R.string.convert_resize_instruction)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
        }
        container.addView(descText)

        // Width input
        val widthLabel = TextView(context).apply {
            text = context.getString(R.string.convert_max_width)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = spacingLg
            layoutParams = lp
        }
        container.addView(widthLabel)

        val widthInput = EditText(context).apply {
            hint = "1920"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subtitle))
        }
        container.addView(widthInput)

        // Height input
        val heightLabel = TextView(context).apply {
            text = context.getString(R.string.convert_max_height)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = spacingSm
            layoutParams = lp
        }
        container.addView(heightLabel)

        val heightInput = EditText(context).apply {
            hint = "1080"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subtitle))
        }
        container.addView(heightInput)

        // Format selector note
        val noteText = TextView(context).apply {
            text = context.getString(R.string.convert_resize_format_note)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body_small))
            setTextColor(ContextCompat.getColor(context, R.color.textTertiary))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = spacingMd
            layoutParams = lp
        }
        container.addView(noteText)

        RoundedDialogBuilder(context)
            .setTitle(context.getString(R.string.convert_resize))
            .setView(container)
            .setPositiveButton(context.getString(R.string.convert_action)) { _, _ ->
                val maxW = widthInput.text.toString().toIntOrNull() ?: 1920
                val maxH = heightInput.text.toString().toIntOrNull() ?: 1080
                if (maxW <= 0 || maxH <= 0) return@setPositiveButton
                // Keep the same format as the original file
                val fmt = when (item.extension) {
                    "png" -> FileConverter.ImageFormat.PNG
                    "webp" -> FileConverter.ImageFormat.WEBP
                    "bmp" -> FileConverter.ImageFormat.BMP
                    else -> FileConverter.ImageFormat.JPG
                }
                ConvertDialogUtils.runConversion(context, { FileConverter.resizeImage(item.path, maxW, maxH, fmt) }, onResult)
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    // =========================================================================
    // NON-MEDIA FILE OPTIONS
    // =========================================================================

    private fun buildOptionsForNonMediaFile(context: Context, item: FileItem): List<ConvertOption> {
        val options = mutableListOf<ConvertOption>()
        val ext = item.extension

        // PDF operations
        if (ext == "pdf") {
            for (fmt in FileConverter.PdfImageFormat.entries) {
                options.add(ConvertOption(
                    title = context.getString(R.string.convert_pdf_to_images, fmt.label),
                    description = context.getString(R.string.convert_pdf_to_images_desc, fmt.label),
                    action = {
                        val outputDir = "${item.file.parent}/${item.file.nameWithoutExtension}_pages"
                        FileConverter.pdfToImages(item.path, outputDir, fmt)
                    }
                ))
            }
            // PDF split
            options.add(ConvertOption(
                title = context.getString(R.string.convert_pdf_split),
                description = context.getString(R.string.convert_pdf_split_desc),
                action = {
                    val outputDir = "${item.file.parent}/${item.file.nameWithoutExtension}_split"
                    FileConverter.splitPdf(item.path, outputDir)
                }
            ))
        }

        // vCard → CSV
        if (ext == "vcf" || ext == "vcard") {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_vcard_to_csv),
                description = context.getString(R.string.convert_vcard_desc),
                action = { FileConverter.vcardToCsv(item.path) }
            ))
        }

        // Audio -> Album art
        if (item.category == FileCategory.AUDIO) {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_extract_album_art_png),
                description = context.getString(R.string.convert_extract_album_art_desc),
                action = { FileConverter.audioToAlbumArt(item.path, FileConverter.ImageFormat.PNG) }
            ))
            options.add(ConvertOption(
                title = context.getString(R.string.convert_extract_album_art_jpg),
                description = context.getString(R.string.convert_extract_album_art_desc),
                action = { FileConverter.audioToAlbumArt(item.path, FileConverter.ImageFormat.JPG, quality = 90) }
            ))
        }

        // Text/Code -> PDF
        if (ext in TEXT_CONVERTIBLE) {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_to_pdf),
                description = context.getString(R.string.convert_text_to_pdf_desc),
                action = {
                    val outputPath = "${item.file.parent}/${item.file.nameWithoutExtension}.pdf"
                    FileConverter.textToPdf(item.path, outputPath)
                }
            ))
        }

        // HTML -> PDF
        if (ext == "html" || ext == "htm") {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_to_pdf),
                description = context.getString(R.string.convert_html_to_pdf_desc),
                action = {
                    val outputPath = "${item.file.parent}/${item.file.nameWithoutExtension}.pdf"
                    FileConverter.textToPdf(item.path, outputPath)
                }
            ))
        }

        // Markdown -> PDF
        if (ext == "md" || ext == "markdown" || ext == "mdown" || ext == "mkd") {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_to_pdf),
                description = context.getString(R.string.convert_markdown_to_pdf_desc),
                action = {
                    val outputPath = "${item.file.parent}/${item.file.nameWithoutExtension}.pdf"
                    FileConverter.textToPdf(item.path, outputPath)
                }
            ))
        }

        // CSV -> Formatted table
        if (ext == "csv") {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_csv_to_table),
                description = context.getString(R.string.convert_csv_to_table_desc),
                action = {
                    val outputPath = "${item.file.parent}/${item.file.nameWithoutExtension}_table.txt"
                    FileConverter.csvToText(item.path, outputPath)
                }
            ))
        }

        // JSON pretty print / minify
        if (ext == "json" || ext == "jsonl" || ext == "json5") {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_json_pretty),
                description = context.getString(R.string.convert_json_pretty_desc),
                action = { FileConverter.prettyPrintJson(item.path) }
            ))
            options.add(ConvertOption(
                title = context.getString(R.string.convert_json_minify),
                description = context.getString(R.string.convert_json_minify_desc),
                action = { FileConverter.minifyJson(item.path) }
            ))
        }

        // Extract to plain text (for any text-based file)
        if (ext in TEXT_CONVERTIBLE && ext != "txt") {
            options.add(ConvertOption(
                title = context.getString(R.string.convert_extract_text),
                description = context.getString(R.string.convert_extract_text_desc),
                action = { FileConverter.extractText(item.path) }
            ))
        }

        return options
    }

}
