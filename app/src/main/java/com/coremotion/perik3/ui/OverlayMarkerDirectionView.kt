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
        color = 0xFF2D7DFF.toInt()
    }

    private val markerStrokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = 0xFFFFFFFF.toInt()
    }

    private val directionLinePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
        strokeCap = Paint.Cap.ROUND
        color = 0xFF2D7DFF.toInt()
    }

    private val directionArrowPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2D7DFF.toInt()
    }

    // 0..1 (이미지 컨텐츠 기준으로 찍을 값)
    private var markerNormalizedX: Float = 0.5f
    private var markerNormalizedY: Float = 0.5f
    private var markerVisible: Boolean = false

    private var directionVectorX: Float = 0f
    private var directionVectorY: Float = 0f
    private var directionVisible: Boolean = false

    // fitCenter 기준을 맞추기 위한 “원본 이미지 크기”
    // (ImageView drawable의 intrinsicWidth/Height)
    private var imageIntrinsicW: Int = 0
    private var imageIntrinsicH: Int = 0

    /**
     *  ImageView(scaleType=fitCenter)에 들어가는 drawable의 intrinsic size를 넘겨줘야
     * 뷰 안에서 실제 이미지가 차지하는 영역(rect)을 계산할 수 있음.
     */
    fun setFitCenterImageSize(intrinsicW: Int, intrinsicH: Int) {
        imageIntrinsicW = max(0, intrinsicW)
        imageIntrinsicH = max(0, intrinsicH)
        invalidate()
    }

    /**
     * normalizedX/Y: 0.0 ~ 1.0 ( “이미지 컨텐츠 영역” 기준)
     */
    fun updateNormalizedPoint(normalizedX: Float, normalizedY: Float) {
        markerNormalizedX = normalizedX.coerceIn(0f, 1f)
        markerNormalizedY = normalizedY.coerceIn(0f, 1f)
        markerVisible = true
        invalidate()
    }

    /**
     * directionVectorX/Y: 방향 벡터(스케일 상관 없음)
     */
    fun updateDirectionVector(directionVectorX: Float, directionVectorY: Float, isVisible: Boolean) {
        this.directionVectorX = directionVectorX
        this.directionVectorY = directionVectorY
        this.directionVisible = isVisible
        invalidate()
    }

    // === 기존 Fragment 호환 alias ===
    fun setMarkerPositionNormalized(x: Float, y: Float) = updateNormalizedPoint(x, y)
    fun setMarkerDirection(dx: Float, dy: Float, visible: Boolean = true) =
        updateDirectionVector(dx, dy, visible)

    fun setMarkerVisible(isVisible: Boolean) {
        markerVisible = isVisible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!markerVisible) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 1f || viewH <= 1f) return

        //  fitCenter로 실제 이미지가 그려지는 rect 계산(여백 제외)
        val (contentLeft, contentTop, contentW, contentH) = computeFitCenterContentRect(viewW, viewH)

        val markerCenterX = contentLeft + markerNormalizedX * contentW
        val markerCenterY = contentTop + markerNormalizedY * contentH

        val markerRadius = min(contentW, contentH) * 0.035f

        canvas.drawCircle(markerCenterX, markerCenterY, markerRadius, markerFillPaint)
        canvas.drawCircle(markerCenterX, markerCenterY, markerRadius, markerStrokePaint)

        if (!directionVisible) return

        val vecLen = sqrt(directionVectorX * directionVectorX + directionVectorY * directionVectorY)
        if (vecLen < 1e-6f) return

        val ndx = directionVectorX / vecLen
        val ndy = directionVectorY / vecLen

        val arrowLineLength = max(contentW, contentH) * 0.05f
        val endX = markerCenterX + ndx * arrowLineLength
        val endY = markerCenterY + ndy * arrowLineLength

        canvas.drawLine(markerCenterX, markerCenterY, endX, endY, directionLinePaint)
        drawArrowHead(canvas, endX, endY, ndx, ndy)
    }

    /**
     * 반환: left, top, contentWidth, contentHeight
     */
    private fun computeFitCenterContentRect(viewW: Float, viewH: Float): RectInfo {
        // 이미지 크기를 모르면 "전체 뷰"를 컨텐츠로 간주 (fallback)
        if (imageIntrinsicW <= 0 || imageIntrinsicH <= 0) {
            return RectInfo(0f, 0f, viewW, viewH)
        }

        val imgW = imageIntrinsicW.toFloat()
        val imgH = imageIntrinsicH.toFloat()

        val scale = min(viewW / imgW, viewH / imgH)
        val drawnW = imgW * scale
        val drawnH = imgH * scale

        val left = (viewW - drawnW) * 0.5f
        val top = (viewH - drawnH) * 0.5f

        return RectInfo(left, top, drawnW, drawnH)
    }

    private data class RectInfo(val left: Float, val top: Float, val w: Float, val h: Float)

    private fun drawArrowHead(
        canvas: Canvas,
        endX: Float,
        endY: Float,
        ndx: Float,
        ndy: Float
    ) {
        val angle = atan2(ndy, ndx)
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

    fun setDirectionFromRotationDeg(rotationDeg: Float, visible: Boolean = true) {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val dx = kotlin.math.sin(rad).toFloat()
        val dy = (-kotlin.math.cos(rad)).toFloat()
        updateDirectionVector(dx, dy, visible)
    }
}