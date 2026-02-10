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

    // ====== ✅ “기본(무효값일 때)” 위치: 외부에서 지정 가능 ======
    private var defaultX: Float = 0.5f
    private var defaultY: Float = 0.0f

    /**
     * 예)
     *  - T2 최상단 중앙 포인트가 (0.5, 0.30) 이면 -> setDefaultMarkerPositionNormalized(0.5f, 0.30f)
     *  - T3 최상단 중앙 포인트가 (0.5, 0.40) 이면 -> setDefaultMarkerPositionNormalized(0.5f, 0.40f)
     */
    fun setDefaultMarkerPositionNormalized(x: Float, y: Float, snapNow: Boolean = true) {
        defaultX = x.coerceIn(0f, 1f)
        defaultY = y.coerceIn(0f, 1f)

        if (snapNow) {
            markerNormalizedX = defaultX
            markerNormalizedY = defaultY
            animatedX = defaultX
            animatedY = defaultY
            markerVisible = true
            postInvalidateOnAnimation()
        }
    }

    // 목표(입력) 0..1
    private var markerNormalizedX: Float = defaultX
    private var markerNormalizedY: Float = defaultY
    private var markerVisible: Boolean = true

    // ✅ 화면용 보간 좌표(튀는 느낌 완화)
    private var animatedX: Float = defaultX
    private var animatedY: Float = defaultY
    private val smoothFactor: Float = 0.18f   // 0~1 (클수록 빨리 따라감)

    private var directionVectorX: Float = 0f
    private var directionVectorY: Float = 0f
    private var directionVisible: Boolean = false

    private var imageIntrinsicW: Int = 0
    private var imageIntrinsicH: Int = 0

    fun setFitCenterImageSize(intrinsicW: Int, intrinsicH: Int) {
        imageIntrinsicW = max(0, intrinsicW)
        imageIntrinsicH = max(0, intrinsicH)
        invalidate()
    }

    /**
     * ✅ (0,0) 이 “무효값”이라면 defaultX/defaultY로 이동
     * ✅ 그리고 animatedX/Y도 함께 스냅시켜서 "아래 왼쪽 잔상" 제거
     *
     * 주의: BLE에서 아주 작은 값(0.0001 등)이 올 수도 있어서 epsilon 넉넉히 잡음
     */
    fun updateNormalizedPoint(normalizedX: Float, normalizedY: Float) {
        val eps = 1e-3f
        val isZeroPoint =
            kotlin.math.abs(normalizedX) <= eps &&
                    kotlin.math.abs(normalizedY) <= eps

        if (isZeroPoint) {
            markerNormalizedX = defaultX
            markerNormalizedY = defaultY

            // ✅ 핵심: 보간 잔상 제거(즉시 위치 고정)
            animatedX = defaultX
            animatedY = defaultY
        } else {
            markerNormalizedX = normalizedX.coerceIn(0f, 1f)
            markerNormalizedY = normalizedY.coerceIn(0f, 1f)
        }

        markerVisible = true
        postInvalidateOnAnimation()
    }

    /**
     * direction (0,0) → 화살표만 숨김, 마커는 유지
     */
    fun updateDirectionVector(directionVectorX: Float, directionVectorY: Float, isVisible: Boolean) {
        val eps = 1e-3f
        val isZeroVec =
            kotlin.math.abs(directionVectorX) <= eps &&
                    kotlin.math.abs(directionVectorY) <= eps

        this.directionVectorX = directionVectorX
        this.directionVectorY = directionVectorY
        this.directionVisible = isVisible && !isZeroVec

        postInvalidateOnAnimation()
    }

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

        val (contentLeft, contentTop, contentW, contentH) = computeFitCenterContentRect(viewW, viewH)

        // ✅ 보간 (스냅된 경우에는 markerNormalized==animated라서 움직이지 않음)
        animatedX += (markerNormalizedX - animatedX) * smoothFactor
        animatedY += (markerNormalizedY - animatedY) * smoothFactor

        val markerCenterX = contentLeft + animatedX * contentW
        val markerCenterY = contentTop + animatedY * contentH

        val markerRadius = min(contentW, contentH) * 0.035f

        canvas.drawCircle(markerCenterX, markerCenterY, markerRadius, markerFillPaint)
        canvas.drawCircle(markerCenterX, markerCenterY, markerRadius, markerStrokePaint)

        if (directionVisible) {
            val vecLen = sqrt(directionVectorX * directionVectorX + directionVectorY * directionVectorY)
            if (vecLen >= 1e-6f) {
                val ndx = directionVectorX / vecLen
                val ndy = directionVectorY / vecLen

                val arrowLineLength = max(contentW, contentH) * 0.05f
                val endX = markerCenterX + ndx * arrowLineLength
                val endY = markerCenterY + ndy * arrowLineLength

                canvas.drawLine(markerCenterX, markerCenterY, endX, endY, directionLinePaint)
                drawArrowHead(canvas, endX, endY, ndx, ndy)
            }
        }

        if (kotlin.math.abs(markerNormalizedX - animatedX) > 0.001f ||
            kotlin.math.abs(markerNormalizedY - animatedY) > 0.001f) {
            postInvalidateOnAnimation()
        }
    }

    private fun computeFitCenterContentRect(viewW: Float, viewH: Float): RectInfo {
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

    private fun drawArrowHead(canvas: Canvas, endX: Float, endY: Float, ndx: Float, ndy: Float) {
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