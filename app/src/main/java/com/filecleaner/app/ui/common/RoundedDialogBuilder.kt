package com.filecleaner.app.ui.common

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.filecleaner.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Drop-in replacement for MaterialAlertDialogBuilder that uses a
 * MaterialCardView for guaranteed rounded corners matching the app's
 * card design language (radius_card = 24dp).
 *
 * Usage is identical to MaterialAlertDialogBuilder:
 * ```
 * RoundedDialogBuilder(context)
 *     .setTitle("Title")
 *     .setMessage("Message")
 *     .setPositiveButton("OK") { _, _ -> }
 *     .show()
 * ```
 */
class RoundedDialogBuilder(private val context: Context) {

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var customView: View? = null
    private var cancelable = true

    private var positiveText: CharSequence? = null
    private var positiveListener: DialogInterface.OnClickListener? = null
    private var negativeText: CharSequence? = null
    private var negativeListener: DialogInterface.OnClickListener? = null
    private var neutralText: CharSequence? = null
    private var neutralListener: DialogInterface.OnClickListener? = null

    private var items: Array<CharSequence>? = null
    private var itemsListener: DialogInterface.OnClickListener? = null

    fun setTitle(title: CharSequence): RoundedDialogBuilder { this.title = title; return this }
    fun setTitle(resId: Int): RoundedDialogBuilder { this.title = context.getString(resId); return this }
    fun setMessage(message: CharSequence): RoundedDialogBuilder { this.message = message; return this }
    fun setMessage(resId: Int): RoundedDialogBuilder { this.message = context.getString(resId); return this }
    fun setView(view: View): RoundedDialogBuilder { this.customView = view; return this }
    fun setCancelable(cancelable: Boolean): RoundedDialogBuilder { this.cancelable = cancelable; return this }

    fun setPositiveButton(text: CharSequence, listener: DialogInterface.OnClickListener?): RoundedDialogBuilder {
        positiveText = text; positiveListener = listener; return this
    }
    fun setPositiveButton(resId: Int, listener: DialogInterface.OnClickListener?): RoundedDialogBuilder {
        positiveText = context.getString(resId); positiveListener = listener; return this
    }
    fun setNegativeButton(text: CharSequence, listener: DialogInterface.OnClickListener?): RoundedDialogBuilder {
        negativeText = text; negativeListener = listener; return this
    }
    fun setNegativeButton(resId: Int, listener: DialogInterface.OnClickListener?): RoundedDialogBuilder {
        negativeText = context.getString(resId); negativeListener = listener; return this
    }
    fun setNeutralButton(text: CharSequence, listener: DialogInterface.OnClickListener?): RoundedDialogBuilder {
        neutralText = text; neutralListener = listener; return this
    }
    fun setNeutralButton(resId: Int, listener: DialogInterface.OnClickListener?): RoundedDialogBuilder {
        neutralText = context.getString(resId); neutralListener = listener; return this
    }

    fun setItems(items: Array<CharSequence>, listener: DialogInterface.OnClickListener): RoundedDialogBuilder {
        this.items = items; this.itemsListener = listener; return this
    }

    fun create(): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(cancelable)

        val card = buildCard(dialog)
        dialog.setContentView(card)

        return dialog
    }

    fun show(): Dialog {
        val dialog = create()
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }

    private fun buildCard(dialog: Dialog): MaterialCardView {
        val paddingLg = context.resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val paddingXxl = context.resources.getDimensionPixelSize(R.dimen.spacing_xxl)
        val spacingSm = context.resources.getDimensionPixelSize(R.dimen.spacing_sm)
        val spacingMd = context.resources.getDimensionPixelSize(R.dimen.spacing_md)
        val spacingXl = context.resources.getDimensionPixelSize(R.dimen.spacing_xl)

        val card = MaterialCardView(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = paddingLg
                marginEnd = paddingLg
                topMargin = spacingXl
                bottomMargin = spacingXl
            }
            radius = context.resources.getDimension(R.dimen.radius_card)
            cardElevation = context.resources.getDimension(R.dimen.elevation_raised)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surfaceElevated))
            strokeWidth = context.resources.getDimensionPixelSize(R.dimen.stroke_default)
            strokeColor = ContextCompat.getColor(context, R.color.borderSubtle)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingXxl, paddingXxl, paddingXxl, paddingLg)
        }

        // Title
        if (title != null) {
            content.addView(TextView(context).apply {
                text = title
                setTextAppearance(R.style.TextAppearance_FileCleaner_Headline)
                setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            })
        }

        // Message
        if (message != null) {
            content.addView(TextView(context).apply {
                text = message
                setTextAppearance(R.style.TextAppearance_FileCleaner_Body)
                setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = spacingMd
                layoutParams = lp
                lineSpacingMultiplier = 1.4f
            })
        }

        // Items list
        if (items != null) {
            val scrollView = ScrollView(context).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = spacingMd
                layoutParams = lp
            }
            val itemsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            for ((index, item) in items!!.withIndex()) {
                itemsContainer.addView(TextView(context).apply {
                    text = item
                    setTextAppearance(R.style.TextAppearance_FileCleaner_Body)
                    setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                    setPadding(0, spacingSm, 0, spacingSm)
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        itemsListener?.onClick(dialog, index)
                        dialog.dismiss()
                    }
                })
            }
            scrollView.addView(itemsContainer)
            content.addView(scrollView)
        }

        // Custom view
        if (customView != null) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = spacingMd
            customView!!.layoutParams = lp
            content.addView(customView)
        }

        // Buttons row
        val hasButtons = positiveText != null || negativeText != null || neutralText != null
        if (hasButtons) {
            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = spacingXl
                layoutParams = lp
            }

            if (neutralText != null) {
                btnRow.addView(makeButton(neutralText!!, dialog) { neutralListener?.onClick(dialog, DialogInterface.BUTTON_NEUTRAL) })
            }
            // Spacer
            if (neutralText != null && (negativeText != null || positiveText != null)) {
                btnRow.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
            }
            if (negativeText != null) {
                btnRow.addView(makeButton(negativeText!!, dialog) { negativeListener?.onClick(dialog, DialogInterface.BUTTON_NEGATIVE) })
            }
            if (positiveText != null) {
                btnRow.addView(makeButton(positiveText!!, dialog, bold = true) { positiveListener?.onClick(dialog, DialogInterface.BUTTON_POSITIVE) })
            }

            content.addView(btnRow)
        }

        card.addView(content)
        return card
    }

    private fun makeButton(text: CharSequence, dialog: Dialog, bold: Boolean = false, onClick: () -> Unit): MaterialButton {
        return MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            this.text = text
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener {
                onClick()
                dialog.dismiss()
            }
        }
    }
}
