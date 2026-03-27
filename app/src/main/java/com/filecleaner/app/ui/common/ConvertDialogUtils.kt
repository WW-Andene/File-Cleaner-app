package com.filecleaner.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.filecleaner.app.R
import com.filecleaner.app.utils.file.FileConverter
import com.filecleaner.app.ui.common.RoundedDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared data class and UI helpers used by ConvertDialog and VideoConvertDialog.
 */
internal data class ConvertOption(
    val title: String,
    val description: String,
    val action: (() -> FileConverter.ConvertResult)?
)

internal object ConvertDialogUtils {

    /** Shows a simple scrollable list of conversion options. */
    fun showOptionsList(
        context: Context,
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
            val row = buildOptionRow(context, option)
            row.setOnClickListener {
                dialog.dismiss()
                option.action?.let { action -> runConversion(context, action, onResult) }
            }
            container.addView(row)
        }

        dialog.show()
    }

    /** Builds a tappable row with a title and description for a conversion option. */
    fun buildOptionRow(context: Context, option: ConvertOption): LinearLayout {
        val spacingMicro = context.resources.getDimensionPixelSize(R.dimen.spacing_micro)
        val spacingMd = context.resources.getDimensionPixelSize(R.dimen.spacing_md)
        val spacingXxl = context.resources.getDimensionPixelSize(R.dimen.spacing_xxl)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacingXxl, spacingMd, spacingXxl, spacingMd)

            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true
            contentDescription = option.title

            val titleView = TextView(context).apply {
                text = option.title
                setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
                setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            addView(titleView)

            val descView = TextView(context).apply {
                text = option.description
                setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_chip))
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = spacingMicro
                layoutParams = lp
            }
            addView(descView)
        }
    }

    /** Builds a Material-styled outlined action button. */
    fun buildActionButton(context: Context, label: String): com.google.android.material.button.MaterialButton {
        val spacingSm = context.resources.getDimensionPixelSize(R.dimen.spacing_sm)
        return com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body_small))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = spacingSm
            layoutParams = lp
            minHeight = context.resources.getDimensionPixelSize(R.dimen.touch_target_min)
        }
    }

    /** Runs a conversion action on a background thread with a progress dialog. */
    fun runConversion(
        context: Context,
        action: () -> FileConverter.ConvertResult,
        onResult: (FileConverter.ConvertResult) -> Unit
    ) {
        val spacingLg = context.resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val spacingXxl = context.resources.getDimensionPixelSize(R.dimen.spacing_xxl)
        val progressContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacingXxl, spacingLg, spacingXxl, spacingLg)

            addView(TextView(context).apply {
                text = context.getString(R.string.convert_progress)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_body))
                setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            })

            addView(ProgressBar(context).apply {
                isIndeterminate = true
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = spacingLg
                layoutParams = lp
            })
        }

        val progressDialog = RoundedDialogBuilder(context)
            .setView(progressContainer)
            .setCancelable(false)
            .show()

        val job = kotlinx.coroutines.SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main + job)
        progressDialog.setOnDismissListener { job.cancel() }
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            try {
                if (progressDialog.isShowing) progressDialog.dismiss()
            } catch (_: Exception) { /* Window already destroyed */ }
            onResult(result)
        }
    }
}
