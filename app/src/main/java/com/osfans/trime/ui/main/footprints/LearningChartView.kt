/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main.footprints

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import com.osfans.trime.R

/** Small, non-interactive plot. The same values are available as readable text below it. */
internal class LearningChartView(
    context: Context,
    private val labels: List<String>,
    private val values: List<Int?>,
    description: String,
    private val percentages: Boolean = false,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val typeSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)

    init {
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight = (180 * density + typeSize * 3).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(minimumHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (labels.isEmpty()) return
        paint.textSize = typeSize
        val peak = values.filterNotNull().maxOrNull() ?: 0
        val maximum = if (percentages) 100 else ((peak.coerceAtLeast(1) + 1) / 2) * 2
        val left = paint.measureText(if (percentages) "100%" else maximum.toString()) + 12 * density
        val right = width - 12 * density
        val top = typeSize + 8 * density
        val bottom = height - typeSize * 2 - 8 * density
        val spacing = (right - left) / labels.size.coerceAtLeast(1)
        paint.strokeWidth = density
        (0..2).forEach { tick ->
            val y = bottom - (bottom - top) * tick / 2
            paint.color = ContextCompat.getColor(context, R.color.haohao_divider)
            canvas.drawLine(left, y, right, y, paint)
            paint.color = ContextCompat.getColor(context, R.color.haohao_cocoa_secondary)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText((maximum * tick / 2).toString() + if (percentages) "%" else "", left - 8 * density, y + typeSize / 3, paint)
        }
        values.forEachIndexed { index, value ->
            val x = left + spacing * (index + .5f)
            if (value != null) {
                val y = bottom - (bottom - top) * value.coerceIn(0, maximum) / maximum
                paint.color = ContextCompat.getColor(context, R.color.learning_word_ink)
                if (percentages) {
                    // No interpolation across missing samples and no fabricated forgetting curve.
                    canvas.drawCircle(x, y, 5 * density, paint)
                } else {
                    val half = (spacing * .30f).coerceAtMost(16 * density)
                    if (value > 0) canvas.drawRoundRect(x - half, y, x + half, bottom, 3 * density, 3 * density, paint)
                }
            }
            if (index == 0 || index == labels.lastIndex || (index == labels.size / 2 && labels.size > 3)) {
                paint.color = ContextCompat.getColor(context, R.color.haohao_cocoa_secondary)
                paint.textAlign = when (index) {
                    0 -> Paint.Align.LEFT
                    labels.lastIndex -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                val textX = when (index) {
                    0 -> left
                    labels.lastIndex -> right
                    else -> x
                }
                canvas.drawText(labels[index], textX, bottom + typeSize * 1.6f, paint)
            }
        }
    }
}
