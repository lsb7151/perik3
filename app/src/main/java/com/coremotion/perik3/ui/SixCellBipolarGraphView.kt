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
        val positiveCellCount = 4  // 위쪽 4칸 (+)
        val negativeCellCount = 2  // 아래쪽 2칸 (-)

        val gapPx = 10f
        val totalGapHeight = gapPx * (cellCount - 1)
        val cellHeight = (height - totalGapHeight) / cellCount.toFloat()
        val cellWidth = width.toFloat()

        // 어떤 셀을 켤지 계산
        val shouldFillCell: BooleanArray = BooleanArray(cellCount) { false }

        val value = animatedNormalizedValue
        val magnitude = abs(value).coerceIn(0f, 1f)

        if (value >= 0f) {
            //  +면 기준선(아래2칸 위)에서 "위로" 채움
            // 기준선 바로 위 셀은 index 3, 그 위로 2,1,0
            val positiveFilledCellCount = (magnitude * positiveCellCount.toFloat()).toInt().coerceIn(0, positiveCellCount)

            for (stepFromBaselineUp in 0 until positiveFilledCellCount) {
                val cellIndexFromTop = (positiveCellCount - 1) - stepFromBaselineUp // 3,2,1,0
                if (cellIndexFromTop in 0 until cellCount) {
                    shouldFillCell[cellIndexFromTop] = true
                }
            }
        } else {
            //  -면 기준선에서 "아래로" 채움
            // 기준선 바로 아래 셀은 index 4, 그 아래로 5
            val negativeFilledCellCount = (magnitude * negativeCellCount.toFloat()).toInt().coerceIn(0, negativeCellCount)

            for (stepFromBaselineDown in 0 until negativeFilledCellCount) {
                val cellIndexFromTop = positiveCellCount + stepFromBaselineDown // 4,5
                if (cellIndexFromTop in 0 until cellCount) {
                    shouldFillCell[cellIndexFromTop] = true
                }
            }
        }

        // 실제 그리기(위에서 아래로 0..5)
        for (indexFromTop in 0 until cellCount) {
            val cellTop = indexFromTop * (cellHeight + gapPx)
            val cellBottom = cellTop + cellHeight

            val paint = when {
                !shouldFillCell[indexFromTop] -> cellOffPaint
                value >= 0f -> positiveCellOnPaint
                else -> negativeCellOnPaint
            }

            canvas.drawRoundRect(0f, cellTop, cellWidth, cellBottom, 14f, 14f, paint)
        }
    }
}