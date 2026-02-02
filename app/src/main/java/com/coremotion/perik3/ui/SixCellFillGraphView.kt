package com.coremotion.perik3.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil

class SixCellFillGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cellOutlinePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        color = 0xFFBDBDBD.toInt()
    }

    private val cellFillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2D7DFF.toInt()
    }

    private val cellEmptyPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFEFEFEF.toInt()
    }

    private var normalizedValueZeroToOne: Float = 0f

    /**
     * ✅ Fragment가 호출하는 함수
     * 0.0 ~ 1.0
     */
    fun updateNormalizedValue(normalizedValue: Float) {
        normalizedValueZeroToOne = normalizedValue.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cellCount = 6
        val viewWidthFloat = width.toFloat()
        val viewHeightFloat = height.toFloat()

        val cellGap = resources.displayMetrics.density * 4f
        val totalGap = cellGap * (cellCount - 1)
        val cellHeight = (viewHeightFloat - totalGap) / cellCount
        val cellWidth = viewWidthFloat

        val filledCellCount = ceil(normalizedValueZeroToOne * cellCount).toInt()

        for (index in 0 until cellCount) {
            val top = viewHeightFloat - (index + 1) * cellHeight - index * cellGap
            val bottom = top + cellHeight

            val left = 0f
            val right = cellWidth

            val isFilled = index < filledCellCount
            canvas.drawRect(left, top, right, bottom, if (isFilled) cellFillPaint else cellEmptyPaint)
            canvas.drawRect(left, top, right, bottom, cellOutlinePaint)
        }
    }
}