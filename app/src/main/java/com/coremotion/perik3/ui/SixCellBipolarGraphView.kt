package com.coremotion.perik3.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class SixCellBipolarGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // -1..+1 (LD 기반)
    private var targetNormalizedValue: Float = 0f
    private var animatedNormalizedValue: Float = 0f

    // 표시 텍스트
    private var labelText: String = "T3"
    private var unitText: String = ""           // 하단 단위
    private var bottomValueText: String = ""    // ✅ LV 표시용 (예: "3.1 mm/s")

    fun setLabel(text: String) { labelText = text; invalidate() }
    fun setUnit(text: String) { unitText = text; invalidate() }

    // ✅ 추가
    fun setBottomValueText(text: String) {
        bottomValueText = text
        invalidate()
    }

    private val cellOffPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2A2F38.toInt()
    }

    private val positiveCellOnPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF81C784.toInt()
    }

    private val negativeCellOnPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE57373.toInt()
    }

    private val dividerPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF000000.toInt()
        alpha = 200
    }

    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        alpha = 220
        textSize = 20f
    }

    private val smallTextPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        alpha = 220
        textSize = 18f
    }

    fun updateNormalizedValue(newNormalizedValue: Float, shouldAnimate: Boolean = true) {
        targetNormalizedValue = newNormalizedValue.coerceIn(-1f, 1f)

        if (!shouldAnimate) {
            animatedNormalizedValue = targetNormalizedValue
            invalidate()
            return
        }

        ValueAnimator.ofFloat(animatedNormalizedValue, targetNormalizedValue).apply {
            duration = 180L
            addUpdateListener {
                animatedNormalizedValue = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cellCount = 6
        val positiveCellCount = 4
        val negativeCellCount = 2

        val sideGutter = 16f
        val topLabelH = 22f

        // ===== 하단 텍스트 영역 =====
        val unitH = if (unitText.isNotBlank()) 22f else 0f
        val valueH = if (bottomValueText.isNotBlank()) 22f else 0f

        // ✅ unit ↔ value 사이 패딩
        val bottomTextPadding = 8f

        val bottomReserved =
            unitH +
                    valueH +
                    if (unitH > 0f && valueH > 0f) bottomTextPadding else 0f

        // ===== 콘텐츠 영역 =====
        val contentLeft = sideGutter
        val contentRight = width.toFloat()
        val contentTop = topLabelH
        val contentBottom = height.toFloat() - bottomReserved

        val contentW = (contentRight - contentLeft).coerceAtLeast(1f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(1f)

        val gapPx = 10f
        val totalGapHeight = gapPx * (cellCount - 1)
        val cellHeight = (contentH - totalGapHeight) / cellCount
        val cellWidth = contentW

        // ===== 셀 활성 계산 =====
        val shouldFillCell = BooleanArray(cellCount) { false }

        val value = animatedNormalizedValue
        val magnitude = abs(value).coerceIn(0f, 1f)

        if (value >= 0f) {
            val filled = (magnitude * positiveCellCount).toInt().coerceIn(0, positiveCellCount)
            for (step in 0 until filled) {
                val idx = (positiveCellCount - 1) - step
                if (idx in 0 until cellCount) shouldFillCell[idx] = true
            }
        } else {
            val filled = (magnitude * negativeCellCount).toInt().coerceIn(0, negativeCellCount)
            for (step in 0 until filled) {
                val idx = positiveCellCount + step
                if (idx in 0 until cellCount) shouldFillCell[idx] = true
            }
        }

        // ===== 셀 그리기 =====
        for (i in 0 until cellCount) {
            val cellTop = contentTop + i * (cellHeight + gapPx)
            val cellBottom = cellTop + cellHeight

            val paint = when {
                !shouldFillCell[i] -> cellOffPaint
                value >= 0f -> positiveCellOnPaint
                else -> negativeCellOnPaint
            }

            canvas.drawRoundRect(
                contentLeft,
                cellTop,
                contentLeft + cellWidth,
                cellBottom,
                14f,
                14f,
                paint
            )
        }

        // ===== 기준선 =====
        val boundaryBottomOfIndex3 =
            contentTop + (positiveCellCount - 1) * (cellHeight + gapPx) + cellHeight
        val dividerY = boundaryBottomOfIndex3 + (gapPx / 2f)
        canvas.drawLine(contentLeft, dividerY, contentLeft + cellWidth, dividerY, dividerPaint)

        // ===== + / - =====
        canvas.drawText("+", 3f, contentTop + 18f, textPaint)
        canvas.drawText("-", 4f, contentBottom - 6f, textPaint)

        // ===== 라벨 =====
        if (labelText.isNotBlank()) {
            val w = smallTextPaint.measureText(labelText)
            canvas.drawText(
                labelText,
                (width - w - 4f).coerceAtLeast(contentLeft),
                contentTop - 4f,
                smallTextPaint
            )
        }

        // ===== (위) unitText =====
        if (unitText.isNotBlank()) {
            val uw = smallTextPaint.measureText(unitText)
            val x = (width - uw) / 2f
            val y = height.toFloat() - valueH - bottomTextPadding - 6f
            canvas.drawText(unitText, x, y, smallTextPaint)
        }

        // ===== (최하단) LV 값 =====
        if (bottomValueText.isNotBlank()) {
            val vw = smallTextPaint.measureText(bottomValueText)
            val x = (width - vw) / 2f
            val y = height.toFloat() - 6f
            canvas.drawText(bottomValueText, x, y, smallTextPaint)
        }
    }
}