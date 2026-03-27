package com.filecleaner.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.filecleaner.app.R

/**
 * Clickable breadcrumb bar showing the current path as tappable segments.
 * e.g., Storage › DCIM › Camera — tap any segment to navigate there.
 */
class BreadcrumbBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var onSegmentClick: ((path: String) -> Unit)? = null

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = resources.getDimensionPixelSize(R.dimen.spacing_sm)
        setPadding(pad, 0, pad, 0)
    }

    init {
        addView(container)
        isHorizontalScrollBarEnabled = false
    }

    fun setPath(segments: List<Pair<String, String>>) {
        container.removeAllViews()

        for ((index, pair) in segments.withIndex()) {
            val (label, path) = pair

            if (index > 0) {
                val sep = TextView(context).apply {
                    text = " › "
                    setTextColor(ContextCompat.getColor(context, R.color.textTertiary))
                    setTextAppearance(R.style.TextAppearance_FileCleaner_BodySmall)
                }
                container.addView(sep)
            }

            val segment = TextView(context).apply {
                text = label
                val isLast = index == segments.lastIndex
                setTextColor(ContextCompat.getColor(context,
                    if (isLast) R.color.colorPrimary else R.color.textSecondary))
                setTextAppearance(R.style.TextAppearance_FileCleaner_BodySmall)
                if (!isLast) {
                    isClickable = true
                    isFocusable = true
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener { onSegmentClick?.invoke(path) }
                }
                contentDescription = label
                val hPad = resources.getDimensionPixelSize(R.dimen.spacing_xs)
                setPadding(hPad, 0, hPad, 0)
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            container.addView(segment)
        }

        // Auto-scroll to end
        post { fullScroll(FOCUS_RIGHT) }
    }
}
