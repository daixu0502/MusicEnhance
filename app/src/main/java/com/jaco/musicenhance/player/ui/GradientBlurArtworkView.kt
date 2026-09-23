package com.jaco.musicenhance.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Keeps the center artwork sharp and progressively increases blur toward the title edge. */
internal class GradientBlurArtworkView(context: Context) : View(context) {
    private var artworkGeneration = 0
    var artwork: Bitmap? = null
        set(value) {
            val generation = value?.generationId ?: 0
            if (field === value && artworkGeneration == generation) return
            field = value
            artworkGeneration = generation
            invalidate()
        }

    var blurTowardTop: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val destination = RectF()
    private val blurNodes = Array(3) { index -> RenderNode("musicenhance-gradient-blur-$index") }

    override fun onDraw(canvas: Canvas) {
        val bitmap = artwork?.takeUnless { it.isRecycled } ?: return
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        if (!canvas.isHardwareAccelerated) return

        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        destination.set(
            (width - drawnWidth) / 2f,
            (height - drawnHeight) / 2f,
            (width + drawnWidth) / 2f,
            (height + drawnHeight) / 2f,
        )

        val shortSide = min(width, height).toFloat()
        drawBlurLayer(canvas, bitmap, blurNodes[0], shortSide * 0.024f, 0.68f)
        drawBlurLayer(canvas, bitmap, blurNodes[1], shortSide * 0.060f, 0.52f)
        drawBlurLayer(canvas, bitmap, blurNodes[2], shortSide * 0.105f, 0.36f)
        maskPaint.shader = null
    }

    private fun drawBlurLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        node: RenderNode,
        radius: Float,
        coverage: Float,
    ) {
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        recording.drawBitmap(bitmap, null, destination, imagePaint)
        node.endRecording()
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR))

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRenderNode(node)

        maskPaint.shader = if (blurTowardTop) {
            LinearGradient(
                0f,
                0f,
                0f,
                height * coverage,
                Color.BLACK,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                0f,
                height * (1f - coverage),
                0f,
                height.toFloat(),
                Color.TRANSPARENT,
                Color.BLACK,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
    }
}
