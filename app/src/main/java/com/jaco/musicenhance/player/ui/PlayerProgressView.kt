package com.jaco.musicenhance.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
internal class PlayerProgressView(context: Context) : View(context) {
    var onTrackingChanged: ((Boolean) -> Unit)? = null
    var onSeekRequested: ((Float) -> Unit)? = null
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x52FFFFFF
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(2f)
        val thumbRadius = dp(4.2f)
        val left = thumbRadius
        val right = width - thumbRadius
        val centerY = height / 2f
        val progressX = left + (right - left).coerceAtLeast(0f) * fraction
        canvas.drawRoundRect(left, centerY - radius, right, centerY + radius, radius, radius, trackPaint)
        canvas.drawRoundRect(left, centerY - radius, progressX, centerY + radius, radius, radius, progressPaint)
        canvas.drawCircle(progressX, centerY, thumbRadius, progressPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTrackingChanged?.invoke(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x)
                onTrackingChanged?.invoke(false)
                onSeekRequested?.invoke(fraction)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                onTrackingChanged?.invoke(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float) {
        val inset = dp(4.2f)
        fraction = ((x - inset) / (width - inset * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
