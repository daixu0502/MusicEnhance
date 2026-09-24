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
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import kotlin.math.max
import kotlin.math.min

/** Keeps the center artwork sharp and progressively increases blur toward the title edge. */
internal class GradientBlurArtworkView(context: Context) : FrameLayout(context) {
    // Keep the lyrics layer in the View tree. Android owns its display-list lifetime and
    // rebuilds it when alpha changes from zero, even after it has been hidden for a while.
    private val fullBlurArtworkView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        foreground = 0x26000000.toDrawable()
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    var fullBlurProgress = 0f
        set(value) {
            val progress = value.coerceIn(0f, 1f)
            if (field == progress) return
            field = progress
            fullBlurArtworkView.alpha = progress
        }
    private var imageLayersDirty = true
    private var gradientMasksDirty = true
    private val gradientMasks = arrayOfNulls<LinearGradient>(3)
    private var artworkGeneration = 0
    var artwork: Bitmap? = null
        set(value) {
            val generation = value?.generationId ?: 0
            if (field === value && artworkGeneration == generation) return
            field = value
            artworkGeneration = generation
            fullBlurArtworkView.setImageBitmap(value)
            imageLayersDirty = true
            if (value == null) discardImageLayers()
            invalidate()
        }

    var blurTowardTop: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            gradientMasksDirty = true
            invalidate()
        }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val destination = RectF()
    private val blurNodes = Array(3) { index -> RenderNode("musicenhance-gradient-blur-$index") }

    init {
        setWillNotDraw(false)
        addView(fullBlurArtworkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        imageLayersDirty = true
        gradientMasksDirty = true
        val radius = min(w, h) * FULL_BLUR_RADIUS_RATIO
        fullBlurArtworkView.setRenderEffect(
            if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR) else null,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = artwork?.takeUnless { it.isRecycled } ?: return
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        if (!canvas.isHardwareAccelerated) return

        if (imageLayersDirty) prepareImageLayers(bitmap)
        if (gradientMasksDirty) prepareGradientMasks()
        for (index in blurNodes.indices) drawBlurLayer(canvas, blurNodes[index], gradientMasks[index])
        maskPaint.shader = null
    }

    /** Bitmap recording and effect allocation happen only on artwork or size changes. */
    private fun prepareImageLayers(bitmap: Bitmap) {
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
        for (index in blurNodes.indices) {
            recordImageLayer(blurNodes[index], bitmap, shortSide * BLUR_RADIUS_RATIOS[index])
        }
        imageLayersDirty = false
    }

    private fun recordImageLayer(node: RenderNode, bitmap: Bitmap, radius: Float) {
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        recording.drawBitmap(bitmap, null, destination, imagePaint)
        node.endRecording()
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR))
    }

    private fun drawBlurLayer(canvas: Canvas, node: RenderNode, gradient: LinearGradient?) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRenderNode(node)
        maskPaint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
    }

    private fun prepareGradientMasks() {
        for (index in gradientMasks.indices) {
            val coverage = GRADIENT_COVERAGE[index]
            gradientMasks[index] = if (blurTowardTop) {
                LinearGradient(0f, 0f, 0f, height * coverage, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, height * (1f - coverage), 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            }
        }
        gradientMasksDirty = false
    }

    override fun onDetachedFromWindow() {
        discardImageLayers()
        super.onDetachedFromWindow()
    }

    private fun discardImageLayers() {
        blurNodes.forEach { it.discardDisplayList() }
        imageLayersDirty = true
    }

    private companion object {
        val BLUR_RADIUS_RATIOS = floatArrayOf(0.024f, 0.060f, 0.105f)
        val GRADIENT_COVERAGE = floatArrayOf(0.68f, 0.52f, 0.36f)
        const val FULL_BLUR_RADIUS_RATIO = 0.14f
    }
}
