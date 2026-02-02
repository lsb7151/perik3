package com.coremotion.perik3.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class OverlayMarkerDirectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val markerFillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerStrokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val directionLinePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val directionArrowPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var markerNormalizedX: Float = 0.5f
    private var markerNormalizedY: Float = 0.5f
    private var markerVisible: Boolean = false

    private var directionVectorX: Float = 0f
    private var directionVectorY: Float = 0f
    private var directionVisible: Boolean = false

    init {
        // 기본 파란색(요청한 파란 원 느낌)
        markerFillPaint.color = 0xFF2D7DFF.toInt()
        markerStrokePaint.color = 0xFFFFFFFF.toInt()

        directionLinePaint.color = 0xFF2D7DFF.toInt()
        directionArrowPaint.color = 0xFF2D7DFF.toInt()
    }

    /**
     * ✅ Fragment가 호출하는 함수
     * normalizedX/Y: 0.0 ~ 1.0 (뷰 영역 기준)
     */
    fun updateNormalizedPoint(normalizedX: Float, normalizedY: Float) {
        markerNormalizedX = normalizedX.coerceIn(0f, 1f)
        markerNormalizedY = normalizedY.coerceIn(0f, 1f)
        markerVisible = true
        invalidate()
    }

    /**
     * ✅ Fragment가 호출하는 함수
     * directionVectorX/Y: 임의 스케일(정규화 안 되어도 됨) → 내부에서 방향만 사용
     */
    fun updateDirectionVector(directionVectorX: Float, directionVectorY: Float, isVisible: Boolean) {
        this.directionVectorX = directionVectorX
        this.directionVectorY = directionVectorY
        this.directionVisible = isVisible
        invalidate()
    }

    fun setMarkerVisible(isVisible: Boolean) {
        markerVisible = isVisible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!markerVisible) return

        val viewWidthFloat = width.toFloat()
        val viewHeightFloat = height.toFloat()

        val markerCenterX = markerNormalizedX * viewWidthFloat
        val markerCenterY = markerNormalizedY * viewHeightFloat

        val markerRadius = min(viewWidthFloat, viewHeightFloat) * 0.035f

        // 원(채움 + 흰 테두리)
        canvas.drawCircle(markerCenterX, markerCenterY, markerRadius, markerFillPaint)
        canvas.drawCircle(markerCenterX, markerCenterY, markerRadius, markerStrokePaint)

        if (!directionVisible) return

        val vectorLength = sqrt(directionVectorX * directionVectorX + directionVectorY * directionVectorY)
        if (vectorLength < 1e-6f) return

        val normalizedDirectionX = directionVectorX / vectorLength
        val normalizedDirectionY = directionVectorY / vectorLength

        val arrowLineLength = max(viewWidthFloat, viewHeightFloat) * 0.10f
        val endX = markerCenterX + normalizedDirectionX * arrowLineLength
        val endY = markerCenterY + normalizedDirectionY * arrowLineLength

        // 방향 선
        canvas.drawLine(markerCenterX, markerCenterY, endX, endY, directionLinePaint)

        // 화살촉
        drawArrowHead(canvas, endX, endY, normalizedDirectionX, normalizedDirectionY)
    }

    private fun drawArrowHead(
        canvas: Canvas,
        endX: Float,
        endY: Float,
        normalizedDirectionX: Float,
        normalizedDirectionY: Float
    ) {
        val angle = atan2(normalizedDirectionY, normalizedDirectionX)
        val arrowSize = resources.displayMetrics.density * 10f

        val leftAngle = angle + (Math.PI.toFloat() * 0.75f)
        val rightAngle = angle - (Math.PI.toFloat() * 0.75f)

        val leftX = endX + cos(leftAngle) * arrowSize
        val leftY = endY + sin(leftAngle) * arrowSize

        val rightX = endX + cos(rightAngle) * arrowSize
        val rightY = endY + sin(rightAngle) * arrowSize

        val arrowPath = Path().apply {
            moveTo(endX, endY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }

        canvas.drawPath(arrowPath, directionArrowPaint)
    }
}