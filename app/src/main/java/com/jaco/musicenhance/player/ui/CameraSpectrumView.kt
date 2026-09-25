package com.jaco.musicenhance.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.jaco.musicenhance.player.audio.PauseSpectrumRelease
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

// Created programmatically with the host controller, never inflated from XML.
@SuppressLint("ViewConstructor")
internal class CameraSpectrumView(
    context: Context,
    private val bassLevel: () -> Float,
) : View(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val cameraPill = RectF()
    private var smoothBass = 0f
    private val pauseRelease = PauseSpectrumRelease()
    var playing: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                pauseRelease.start(smoothBass, SystemClock.elapsedRealtime())
            }
            invalidate()
        }
    var spectrumColor: Int = 0xFFE82A1F.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val referenceLengths = intArrayOf(
        208, 168, 136, 112, 86, 66, 52, 44, 34, 30, 24, 20, 20,
        20, 24, 30, 34, 44, 52, 66, 86, 112, 136, 168, 208,
    )
    private val animator = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 16)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(animator)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(animator)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val targetBass = if (playing) bassLevel() else 0f
        if (playing) {
            smoothBass = if (targetBass >= smoothBass) {
                targetBass
            } else {
                smoothBass + (targetBass - smoothBass) * 0.18f
            }
        } else {
            smoothBass = pauseRelease.level(SystemClock.elapsedRealtime())
        }

        val verticalPill = height >= width
        cameraPill.set(0f, 0f, width.toFloat(), height.toFloat())
        val minorLength = if (verticalPill) width.toFloat() else height.toFloat()
        val start = minorLength * (13.5f / 341f)
        val end = minorLength * (1f - 14.5f / 341f)
        val barCount = referenceLengths.size
        val gap = (end - start) / (barCount - 1)
        val longAxis = if (verticalPill) height.toFloat() else width.toFloat()
        barPaint.strokeWidth = minorLength * (7f / 341f)

        val pillRadius = min(cameraPill.width(), cameraPill.height()) / 2f
        canvas.drawRoundRect(cameraPill, pillRadius, pillRadius, gapPaint)

        val visualLevel = (smoothBass * 1.12f).coerceIn(0f, 1f).pow(0.62f)
        val activeReach = visualLevel * (barCount / 2f)
        val centerRed = mixChannel(Color.red(spectrumColor), 255, 0.52f)
        val centerGreen = mixChannel(Color.green(spectrumColor), 255, 0.52f)
        val centerBlue = mixChannel(Color.blue(spectrumColor), 255, 0.52f)
        val edgeRed = Color.red(spectrumColor)
        val edgeGreen = Color.green(spectrumColor)
        val edgeBlue = Color.blue(spectrumColor)

        referenceLengths.forEachIndexed { index, referenceLength ->
            val fromCenter = abs(index - referenceLengths.lastIndex / 2f)
            val activation = (activeReach - fromCenter + 0.55f).coerceIn(0f, 1f)
            val gradientPosition = (fromCenter / (barCount / 2f)).coerceIn(0f, 1f).pow(0.7f)
            val activeRed = mixChannel(centerRed, edgeRed, gradientPosition)
            val activeGreen = mixChannel(centerGreen, edgeGreen, gradientPosition)
            val activeBlue = mixChannel(centerBlue, edgeBlue, gradientPosition)
            barPaint.color = Color.rgb(
                mixChannel(30, activeRed, activation),
                mixChannel(30, activeGreen, activation),
                mixChannel(30, activeBlue, activation),
            )
            barPaint.alpha = 255
            val offset = start + index * gap
            val halfLength = referenceLength * longAxis / 702f / 2f
            if (verticalPill) {
                val centerY = cameraPill.centerY()
                canvas.drawLine(offset, centerY - halfLength, offset, centerY + halfLength, barPaint)
            } else {
                val centerX = cameraPill.centerX()
                canvas.drawLine(centerX - halfLength, offset, centerX + halfLength, offset, barPaint)
            }
        }
    }

    private fun mixChannel(from: Int, to: Int, amount: Float): Int =
        (from + (to - from) * amount).toInt().coerceIn(0, 255)

}
