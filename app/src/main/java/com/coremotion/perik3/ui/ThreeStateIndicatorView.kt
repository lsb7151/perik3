package com.coremotion.perik3.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ThreeStateIndicatorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var stateId = -1

    fun updateState(newStateId: Int) {
        stateId = newStateId
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val paint = Paint()
        val colors = listOf(
            if (stateId == 0) 0xFFE7E7E7 else 0xFF444444,
            if (stateId == 1) 0xFF4FC3F7 else 0xFF444444,
            if (stateId == 2) 0xFFFFD54F else 0xFF444444
        )
        val h = height / 3f
        colors.forEachIndexed { i, c ->
            paint.color = c.toInt()
            canvas.drawRect(0f, i * h, width.toFloat(), (i + 1) * h, paint)
        }
    }
}