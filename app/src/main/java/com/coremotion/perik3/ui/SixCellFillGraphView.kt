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

    private var labelText: String = "T2"
    private var unitText: String = ""

    fun setLabel(text: String) { labelText = text; invalidate() }
    fun setUnit(text: String) { unitText = text; invalidate() }

    // (선택) 라벨 표시 여부
    private var showLabel: Boolean = true
    fun setShowLabel(show: Boolean) { showLabel = show; invalidate() }

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

    // ✅ 텍스트: 검은색
    private val smallTextPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        alpha = 220
        textSize = 18f
    }

    private var normalizedValueZeroToOne: Float = 0f

    /**
     *  Fragment가 호출하는 함수
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

        // ✅ 텍스트 영역 확보(단위는 하단)
        val topLabelH = if (showLabel) 22f else 0f
        val bottomUnitH = 22f

        val contentLeft = 0f
        val contentRight = viewWidthFloat
        val contentTop = topLabelH
        val contentBottom = viewHeightFloat - bottomUnitH

        val contentW = (contentRight - contentLeft).coerceAtLeast(1f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(1f)

        val cellGap = resources.displayMetrics.density * 4f
        val totalGap = cellGap * (cellCount - 1)
        val cellHeight = (contentH - totalGap) / cellCount
        val cellWidth = contentW

        val filledCellCount = ceil(normalizedValueZeroToOne * cellCount).toInt()

        // 아래에서 위로 채우는 방식 유지
        for (index in 0 until cellCount) {
            val top = contentBottom - (index + 1) * cellHeight - index * cellGap
            val bottom = top + cellHeight

            val left = contentLeft
            val right = contentLeft + cellWidth

            val isFilled = index < filledCellCount
            canvas.drawRect(left, top, right, bottom, if (isFilled) cellFillPaint else cellEmptyPaint)
            canvas.drawRect(left, top, right, bottom, cellOutlinePaint)
        }

        // (선택) 상단 라벨(우측 정렬)
        if (showLabel && labelText.isNotBlank()) {
            val w = smallTextPaint.measureText(labelText)
            canvas.drawText(labelText, (viewWidthFloat - w - 4f).coerceAtLeast(0f), contentTop - 4f, smallTextPaint)
        }

        // ✅ 하단 단위(중앙)
        if (unitText.isNotBlank()) {
            val uw = smallTextPaint.measureText(unitText)
            canvas.drawText(unitText, (viewWidthFloat - uw) / 2f, viewHeightFloat - 6f, smallTextPaint)
        }
    }
}