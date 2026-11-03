package com.puli.islanddrop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/** The pulsing "radar" circle on the home screen — concentric rings animating outward behind a
 *  solid center dot, matching the "Ready to receive" card. */
class RadarView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#0A84FF")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A84FF")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f
        val dotR = maxR * 0.42f

        for (i in 0 until 2) {
            val p = (phase + i * 0.5f) % 1f
            val r = dotR + (maxR - dotR) * p
            ringPaint.alpha = ((1f - p) * 150).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        dotPaint.alpha = 255
        canvas.drawCircle(cx, cy, dotR, dotPaint)

        // simple phone-with-down-arrow glyph
        val w = dotR * 0.5f
        val h = dotR * 0.8f
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 6f, 6f, iconPaint)
        canvas.drawLine(cx, cy - h * 0.15f, cx, cy + h * 0.1f, iconPaint)
        canvas.drawLine(cx, cy + h * 0.1f, cx - w * 0.2f, cy - h * 0.05f, iconPaint)
        canvas.drawLine(cx, cy + h * 0.1f, cx + w * 0.2f, cy - h * 0.05f, iconPaint)
    }
}
