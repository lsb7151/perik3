package com.coremotion.perik3.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ThreeStateIndicatorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // 0 = IDLE, 1 = STABLE, 2 = TREMOR
    private var stateId = -1

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 14f
        isFakeBoldText = true
    }

    fun updateState(newStateId: Int) {
        if (newStateId !in 0..2) return
        stateId = newStateId
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val h = height / 3f
        val w = width.toFloat()

        // 위 → 아래
        val items = listOf(
            Triple("IDLE",    0, 0xFFBDBDBD), // 회색
            Triple("TREMOR",  2, 0xFFFFD54F), // 노란색
            Triple("STABLE",  1, 0xFF81C784)  // 초록색
        )

        items.forEachIndexed { index, (label, id, activeColor) ->
            rectPaint.color =
                if (stateId == id) activeColor.toInt() else 0xFF444444.toInt()

            val top = index * h
            val bottom = (index + 1) * h

            // 배경
            canvas.drawRect(0f, top, w, bottom, rectPaint)

            // 텍스트 (세로 중앙 정렬)
            val textY = top + h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, w / 2f, textY, textPaint)
        }
    }
}