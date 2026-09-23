package com.jaco.musicenhance.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.jaco.musicenhance.player.model.RepeatMode
import kotlin.math.min

@SuppressLint("ViewConstructor")
internal class PlayerControlView(
    context: Context,
    private val kind: Kind,
) : View(context) {
    enum class Kind { REPEAT, PREVIOUS, PLAY_PAUSE, NEXT, FAVORITE, DISMISS }

    var playing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var repeatMode: RepeatMode = RepeatMode.UNKNOWN
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Every glyph uses the same 24dp canvas, 18dp optical bounds and 1.8dp stroke.
        // Touch targets retain their existing, larger layout bounds in both orientations.
        val unit = min(resources.displayMetrics.density, min(width, height) / 24f)
        val checkpoint = canvas.save()
        canvas.translate(width / 2f - 12f * unit, height / 2f - 12f * unit)
        canvas.scale(unit, unit)
        paint.color = if (active) 0xFF53E2B7.toInt() else Color.WHITE
        paint.alpha = if (isEnabled) 255 else 110
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        when (kind) {
            Kind.REPEAT -> when (repeatMode) {
                RepeatMode.SHUFFLE -> drawShuffle(canvas)
                RepeatMode.SEQUENTIAL -> drawOrder(canvas)
                else -> drawRepeat(canvas)
            }
            Kind.PREVIOUS -> drawSkip(canvas, false)
            Kind.PLAY_PAUSE -> if (playing) drawPause(canvas) else drawPlay(canvas)
            Kind.NEXT -> drawSkip(canvas, true)
            Kind.FAVORITE -> drawHeart(canvas)
            Kind.DISMISS -> drawDismiss(canvas)
        }
        canvas.restoreToCount(checkpoint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                alpha = 0.55f
                return true
            }
            MotionEvent.ACTION_UP -> {
                val click = isPressed
                isPressed = false
                alpha = 1f
                if (click) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                alpha = 1f
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawPlay(canvas: Canvas) {
        path.reset()
        path.moveTo(6.5f, 3.5f)
        path.lineTo(20.5f, 12f)
        path.lineTo(6.5f, 20.5f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawPause(canvas: Canvas) {
        canvas.drawRoundRect(5f, 3.5f, 9f, 20.5f, 1f, 1f, paint)
        canvas.drawRoundRect(15f, 3.5f, 19f, 20.5f, 1f, 1f, paint)
    }

    private fun drawSkip(canvas: Canvas, forward: Boolean) {
        val checkpoint = canvas.save()
        if (!forward) {
            canvas.translate(24f, 0f)
            canvas.scale(-1f, 1f)
        }
        path.reset()
        path.moveTo(4f, 4f)
        path.lineTo(15.5f, 12f)
        path.lineTo(4f, 20f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawLine(20f, 4f, 20f, 20f, paint)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawRepeat(canvas: Canvas) {
        path.reset()
        path.moveTo(3f, 11f)
        path.lineTo(3f, 10f)
        path.quadTo(3f, 7f, 6f, 7f)
        path.lineTo(21f, 7f)
        path.moveTo(17f, 3f)
        path.lineTo(21f, 7f)
        path.lineTo(17f, 11f)
        path.moveTo(21f, 13f)
        path.lineTo(21f, 14f)
        path.quadTo(21f, 17f, 18f, 17f)
        path.lineTo(3f, 17f)
        path.moveTo(7f, 13f)
        path.lineTo(3f, 17f)
        path.lineTo(7f, 21f)
        canvas.drawPath(path, paint)
        if (repeatMode == RepeatMode.SINGLE_LOOP) {
            path.reset()
            path.moveTo(10.5f, 10.5f)
            path.lineTo(12.5f, 9f)
            path.lineTo(12.5f, 14.5f)
            canvas.drawPath(path, paint)
        }
    }

    private fun drawShuffle(canvas: Canvas) {
        path.reset()
        path.moveTo(3f, 6f)
        path.lineTo(5f, 6f)
        path.cubicTo(10f, 6f, 14f, 18f, 19f, 18f)
        path.lineTo(21f, 18f)
        // Leave a small gap at the crossing to keep the two directions legible.
        path.moveTo(3f, 18f)
        path.lineTo(5f, 18f)
        path.cubicTo(7f, 18f, 8.5f, 16f, 10f, 14f)
        path.moveTo(14f, 10f)
        path.cubicTo(16f, 7f, 17.5f, 6f, 19f, 6f)
        path.lineTo(21f, 6f)
        path.moveTo(18f, 3f)
        path.lineTo(21f, 6f)
        path.lineTo(18f, 9f)
        path.moveTo(18f, 15f)
        path.lineTo(21f, 18f)
        path.lineTo(18f, 21f)
        canvas.drawPath(path, paint)
    }

    private fun drawOrder(canvas: Canvas) {
        path.reset()
        for (y in floatArrayOf(5f, 12f, 19f)) {
            path.moveTo(3f, y)
            path.lineTo(11f, y)
        }
        path.moveTo(18f, 4f)
        path.lineTo(18f, 20f)
        path.moveTo(14.5f, 16.5f)
        path.lineTo(18f, 20f)
        path.lineTo(21.5f, 16.5f)
        canvas.drawPath(path, paint)
    }

    private fun drawHeart(canvas: Canvas) {
        path.reset()
        path.moveTo(12f, 20.5f)
        path.cubicTo(9f, 18f, 3f, 13.5f, 3f, 8f)
        path.cubicTo(3f, 3.5f, 8.5f, 2.5f, 12f, 7f)
        path.cubicTo(15.5f, 2.5f, 21f, 3.5f, 21f, 8f)
        path.cubicTo(21f, 13.5f, 15f, 18f, 12f, 20.5f)
        path.close()
        if (active) {
            val originalAlpha = paint.alpha
            paint.style = Paint.Style.FILL
            paint.alpha = (originalAlpha * 0.22f).toInt()
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.alpha = originalAlpha
        }
        canvas.drawPath(path, paint)
    }

    private fun drawDismiss(canvas: Canvas) {
        path.reset()
        path.moveTo(16f, 4f)
        path.lineTo(8f, 12f)
        path.lineTo(16f, 20f)
        canvas.drawPath(path, paint)
    }
}
