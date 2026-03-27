package com.filecleaner.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.filecleaner.app.R
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.utils.FileConverter
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Video-specific conversion dialog extracted from ConvertDialog.
 *
 * Shows two sections:
 * 1. Extract a single frame at a user-selected timestamp (with seek bar)
 * 2. Extract evenly-spaced key frames (with configurable count)
 */
internal object VideoConvertDialog {

    fun show(context: Context, item: FileItem, onResult: (FileConverter.ConvertResult) -> Unit) {
        val spacingXs = context.resources.getDimensionPixelSize(R.dimen.spacing_xs)
        val spacingSm = context.resources.getDimensionPixelSize(R.dimen.spacing_sm)
        val spacingMd = context.resources.getDimensionPixelSize(R.dimen.spacing_md)
        val spacingLg = context.resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val spacingXl = context.resources.getDimensionPixelSize(R.dimen.spacing_xl)
        val spacingXxl = context.resources.getDimensionPixelSize(R.dimen.spacing_xxl)
        val strokeDefault = context.resources.getDimensionPixelSize(R.dimen.stroke_default)
        val durationMs = FileConverter.getVideoDurationMs(item.path)

        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacingXxl, spacingLg, spacingXxl, spacingSm)
        }
        scrollView.addView(container)

        // ---- Section 1: Extract single frame ----
        container.addView(buildSectionTitle(context, R.string.convert_video_section_single_frame))
        container.addView(buildSectionDesc(context, R.string.convert_video_single_frame_desc, spacingXs))

        // Timestamp display
        val timeDisplay = TextView(context).apply {
            text = context.getString(R.string.convert_video_timestamp, "0:00",
                if (durationMs > 0) FileConverter.formatTimeDisplay(durationMs) else "?:??")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = spacingMd
            layoutParams = lp
        }
        container.addView(timeDisplay)

        // Seek bar for timestamp selection
        var selectedTimeMs = 0L
        val seekBar = SeekBar(context).apply {
            max = if (durationMs > 0) durationMs.toInt() else 100
            progress = 0
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = spacingXs
            layoutParams = lp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedTimeMs = progress.toLong()
                    timeDisplay.text = context.getString(
                        R.string.convert_video_timestamp,
                        FileConverter.formatTimeDisplay(selectedTimeMs),
                        if (durationMs > 0) FileConverter.formatTimeDisplay(durationMs) else "?:??"
                    )
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        if (durationMs <= 0) seekBar.isEnabled = false
        container.addView(seekBar)

        // Format choice row for single frame
        val formatRow1 = buildHorizontalRow(context, spacingMd)
        val btnExtractPng = ConvertDialogUtils.buildActionButton(context, context.getString(R.string.convert_extract_as_png))
        val btnExtractJpg = ConvertDialogUtils.buildActionButton(context, context.getString(R.string.convert_extract_as_jpg))
        formatRow1.addView(btnExtractPng)
        formatRow1.addView(btnExtractJpg)
        container.addView(formatRow1)

        // ---- Divider ----
        container.addView(View(context).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, strokeDefault)
            lp.topMargin = spacingXl
            lp.bottomMargin = spacingLg
            layoutParams = lp
            setBackgroundColor(ContextCompat.getColor(context, R.color.borderDefault))
        })

        // ---- Section 2: Extract key frames ----
        container.addView(buildSectionTitle(context, R.string.convert_video_section_key_frames))
        container.addView(buildSectionDesc(context, R.string.convert_video_key_frames_desc, spacingXs))

        // Frame count input
        val countRow = buildHorizontalRow(context, spacingMd)
        countRow.addView(TextView(context).apply {
            text = context.getString(R.string.convert_video_frame_count)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val countInput = EditText(context).apply {
            hint = "10"
            setText("10")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subtitle))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                context.resources.getDimensionPixelSize(R.dimen.convert_count_input_width),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        countRow.addView(countInput)
        container.addView(countRow)

        // Format choice row for key frames
        val formatRow2 = buildHorizontalRow(context, spacingMd)
        val btnKeyPng = ConvertDialogUtils.buildActionButton(context, context.getString(R.string.convert_extract_key_png))
        val btnKeyJpg = ConvertDialogUtils.buildActionButton(context, context.getString(R.string.convert_extract_key_jpg))
        formatRow2.addView(btnKeyPng)
        formatRow2.addView(btnKeyJpg)
        container.addView(formatRow2)

        // Show the dialog
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.convert_title))
            .setView(scrollView)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()

        // Wire button actions
        btnExtractPng.setOnClickListener {
            dialog.dismiss()
            ConvertDialogUtils.runConversion(context, {
                FileConverter.extractFrameAtTime(item.path, selectedTimeMs, FileConverter.ImageFormat.PNG)
            }, onResult)
        }
        btnExtractJpg.setOnClickListener {
            dialog.dismiss()
            ConvertDialogUtils.runConversion(context, {
                FileConverter.extractFrameAtTime(item.path, selectedTimeMs, FileConverter.ImageFormat.JPG, quality = 90)
            }, onResult)
        }
        btnKeyPng.setOnClickListener {
            dialog.dismiss()
            val count = countInput.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 10
            val outDir = "${item.file.parent}/${item.file.nameWithoutExtension}_frames"
            ConvertDialogUtils.runConversion(context, {
                FileConverter.extractKeyFrames(item.path, outDir, count, FileConverter.ImageFormat.PNG)
            }, onResult)
        }
        btnKeyJpg.setOnClickListener {
            dialog.dismiss()
            val count = countInput.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 10
            val outDir = "${item.file.parent}/${item.file.nameWithoutExtension}_frames"
            ConvertDialogUtils.runConversion(context, {
                FileConverter.extractKeyFrames(item.path, outDir, count, FileConverter.ImageFormat.JPG, quality = 85)
            }, onResult)
        }

        dialog.show()
    }

    private fun buildSectionTitle(context: Context, stringRes: Int): TextView =
        TextView(context).apply {
            text = context.getString(stringRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_subtitle))
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun buildSectionDesc(context: Context, stringRes: Int, topMargin: Int): TextView =
        TextView(context).apply {
            text = context.getString(stringRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_chip))
            setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = topMargin
            layoutParams = lp
        }

    private fun buildHorizontalRow(context: Context, topMargin: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = topMargin
            layoutParams = lp
        }
}
