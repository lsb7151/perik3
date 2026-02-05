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

    // -1..+1
    private var targetNormalizedValue: Float = 0f
    private var animatedNormalizedValue: Float = 0f

    // 표시 텍스트
    private var labelText: String = "T3"
    private var unitText: String = ""          //  단위는 하단에
    private var showValueText: Boolean = false // 필요하면 true로

    fun setLabel(text: String) { labelText = text; invalidate() }
    fun setUnit(text: String) { unitText = text; invalidate() }
    fun setShowValueText(show: Boolean) { showValueText = show; invalidate() }

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

    //  기준선(구분선): 검은색
    private val dividerPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF000000.toInt()
        alpha = 200
    }

    // 텍스트(+/-/라벨/단위): 검은색
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
        val positiveCellCount = 4  // 위 4칸 (+)
        val negativeCellCount = 2  // 아래 2칸 (-)

        //  “셀 밖 텍스트”를 위한 여백
        val sideGutter = 16f          // 좌측(+/-) 영역
        val topLabelH = 22f           // 상단 라벨 영역(선택)
        val bottomUnitH = 22f         // 하단 단위 영역

        // 셀 그릴 수 있는 영역(텍스트 영역 제외)
        val contentLeft = sideGutter
        val contentRight = width.toFloat()
        val contentTop = topLabelH
        val contentBottom = height.toFloat() - bottomUnitH

        val contentW = (contentRight - contentLeft).coerceAtLeast(1f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(1f)

        val gapPx = 10f
        val totalGapHeight = gapPx * (cellCount - 1)
        val cellHeight = (contentH - totalGapHeight) / cellCount.toFloat()
        val cellWidth = contentW

        // 어떤 셀을 켤지 계산
        val shouldFillCell = BooleanArray(cellCount) { false }

        val value = animatedNormalizedValue
        val magnitude = abs(value).coerceIn(0f, 1f)

        if (value >= 0f) {
            val filled = (magnitude * positiveCellCount).toInt().coerceIn(0, positiveCellCount)
            for (step in 0 until filled) {
                val idx = (positiveCellCount - 1) - step // 3,2,1,0
                if (idx in 0 until cellCount) shouldFillCell[idx] = true
            }
        } else {
            val filled = (magnitude * negativeCellCount).toInt().coerceIn(0, negativeCellCount)
            for (step in 0 until filled) {
                val idx = positiveCellCount + step // 4,5
                if (idx in 0 until cellCount) shouldFillCell[idx] = true
            }
        }

        // 셀 그리기(위->아래)
        for (i in 0 until cellCount) {
            val cellTop = contentTop + i * (cellHeight + gapPx)
            val cellBottom = cellTop + cellHeight

            val paint = when {
                !shouldFillCell[i] -> cellOffPaint
                value >= 0f -> positiveCellOnPaint
                else -> negativeCellOnPaint
            }

            canvas.drawRoundRect(
                contentLeft, cellTop,
                contentLeft + cellWidth, cellBottom,
                14f, 14f, paint
            )
        }

        //  기준선(4칸/2칸 경계) 검은색
        val boundaryBottomOfIndex3 = contentTop + (positiveCellCount - 1) * (cellHeight + gapPx) + cellHeight
        val dividerY = boundaryBottomOfIndex3 + (gapPx / 2f)
        canvas.drawLine(contentLeft, dividerY, contentLeft + cellWidth, dividerY, dividerPaint)

        // + / - : 셀 밖(좌측 여백)에 검은색
        // +는 위쪽, -는 아래쪽에 배치
        canvas.drawText("+", 3f, contentTop + 18f, textPaint)
        canvas.drawText("-", 4f, contentBottom - 6f, textPaint)

        // (선택) 라벨은 상단 우측에
        if (labelText.isNotBlank()) {
            val w = smallTextPaint.measureText(labelText)
            canvas.drawText(labelText, (width - w - 4f).coerceAtLeast(contentLeft), contentTop - 4f, smallTextPaint)
        }

        //  단위는 하단 중앙 (T2/T3 공통 요구)
        if (unitText.isNotBlank()) {
            val uw = smallTextPaint.measureText(unitText)
            val x = (width - uw) / 2f
            val y = height.toFloat() - 6f
            canvas.drawText(unitText, x, y, smallTextPaint)
        }

        // (선택) 값 표시를 하단 위쪽에 (원하면)
        if (showValueText) {
            val vText = String.format("%.2f", value)
            val vw = smallTextPaint.measureText(vText)
            canvas.drawText(vText, (width - vw) / 2f, dividerY - 6f, smallTextPaint)
        }
    }
}