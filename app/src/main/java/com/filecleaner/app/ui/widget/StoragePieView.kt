package com.filecleaner.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.filecleaner.app.R

/**
 * Donut-style pie chart showing storage category breakdown.
 * Material Design 3 styled with rounded segment caps.
 */
class StoragePieView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Segment(val value: Long, val colorRes: Int, val label: String)

    private val segments = mutableListOf<Segment>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.borderSubtle)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.textPrimary)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private var centerText = ""
    private var subText = ""

    fun setData(segments: List<Segment>, centerLabel: String = "", subLabel: String = "") {
        this.segments.clear()
        this.segments.addAll(segments)
        this.centerText = centerLabel
        this.subText = subLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val strokeWidth = width * 0.12f
        paint.strokeWidth = strokeWidth
        bgPaint.strokeWidth = strokeWidth

        val padding = strokeWidth / 2 + 4f
        rect.set(padding, padding, width - padding, height - padding)

        // Background ring
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)

        // Segments
        val total = segments.sumOf { it.value }.toFloat()
        if (total <= 0) return

        var startAngle = -90f
        val gap = 2f // Gap between segments

        for (segment in segments) {
            val sweep = (segment.value / total) * (360f - gap * segments.size)
            if (sweep < 1f) continue
            paint.color = ContextCompat.getColor(context, segment.colorRes)
            canvas.drawArc(rect, startAngle, sweep, false, paint)
            startAngle += sweep + gap
        }

        // Center text
        if (centerText.isNotEmpty()) {
            textPaint.textSize = width * 0.15f
            textPaint.color = ContextCompat.getColor(context, R.color.textPrimary)
            canvas.drawText(centerText, width / 2f, height / 2f, textPaint)
        }
        if (subText.isNotEmpty()) {
            textPaint.textSize = width * 0.08f
            textPaint.color = ContextCompat.getColor(context, R.color.textSecondary)
            canvas.drawText(subText, width / 2f, height / 2f + width * 0.12f, textPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
            .coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            .coerceAtMost(resources.getDimensionPixelSize(R.dimen.pie_chart_max_size))
        setMeasuredDimension(size, size)
    }
}
