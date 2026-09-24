package com.jaco.musicenhance.player.ui.lyrics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import com.jaco.musicenhance.player.model.LyricLine
import java.util.Locale
import kotlin.math.abs

/** One accessible lyric action. Physical taps are limited to the actual visible text bounds. */
@SuppressLint("ViewConstructor")
internal class LyricRowView(
    context: Context,
    line: LyricLine,
    textSizePx: Float,
    onSeek: () -> Unit,
) : LinearLayout(context) {
    private val lyricLabel = TextView(context).apply {
        text = line.text
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        val blurPadding = dp(LyricsAppearance.BLUR_PADDING_DP).toInt()
        setPadding(0, blurPadding, 0, blurPadding)
        setTextColor(LyricsAppearance.INACTIVE_TEXT_COLOR)
        alpha = LyricsAppearance.INACTIVE_TEXT_ALPHA
        setLineSpacing(dp(3f), 1f)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
    }
    private val timeLabel = TextView(context).apply {
        alpha = 0f
        text = String.format(Locale.ROOT, "%02d:%02d", line.startMs / 60_000, line.startMs / 1_000 % 60)
        textSize = 10f
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setTextColor(0xDDFFFFFF.toInt())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val hitPadding = dp(4f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tapAccepted = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var targetTextAlpha = LyricsAppearance.INACTIVE_TEXT_ALPHA
    private var targetTextColor = LyricsAppearance.INACTIVE_TEXT_COLOR
    private var textAppearanceAnimator: ValueAnimator? = null
    private var timeVisible = false

    val singleLineHeight: Int get() = lyricLabel.lineHeight + lyricLabel.paddingTop +
        lyricLabel.paddingBottom + paddingTop + paddingBottom

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        // Move vertical space inside the text's render layer so blur cannot crop the glyphs.
        // Keep the same total row spacing and the existing text-only touch bounds.
        val rowPadding = dp(18f).toInt() - lyricLabel.paddingTop
        setPadding(dp(3f).toInt(), rowPadding, dp(3f).toInt(), rowPadding)
        addView(lyricLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(timeLabel, LayoutParams(dp(38f).toInt(), LayoutParams.WRAP_CONTENT))
        isFocusable = true
        contentDescription = "${line.text}，${timeLabel.text}，点击从此句播放"
        setOnClickListener { onSeek() }
    }

    fun setLyricTextSize(sizePx: Float) = lyricLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    fun setLyricBlur(effect: RenderEffect?) = lyricLabel.setRenderEffect(effect)

    fun updateAppearance(textAlpha: Float, active: Boolean, showTime: Boolean) {
        val textColor = if (active) Color.WHITE else LyricsAppearance.INACTIVE_TEXT_COLOR
        if (targetTextAlpha != textAlpha || targetTextColor != textColor) {
            textAppearanceAnimator?.cancel()
            targetTextAlpha = textAlpha
            targetTextColor = textColor
            val startAlpha = lyricLabel.alpha
            // Retarget from the visible state so a rapid seek never flashes white or gray.
            if (!ValueAnimator.areAnimatorsEnabled()) {
                lyricLabel.alpha = textAlpha
                lyricLabel.setTextColor(textColor)
                textAppearanceAnimator = null
            } else {
                textAppearanceAnimator = ValueAnimator.ofArgb(lyricLabel.currentTextColor, textColor).apply {
                    duration = LyricsAppearance.TEXT_FADE_DURATION_MS
                    interpolator = LyricsAppearance.TEXT_FADE_CURVE
                    addUpdateListener {
                        lyricLabel.setTextColor(it.animatedValue as Int)
                        lyricLabel.alpha = startAlpha + (textAlpha - startAlpha) * it.animatedFraction
                    }
                    start()
                }
            }
        }
        if (timeVisible != showTime) {
            timeVisible = showTime
            timeLabel.alpha = if (showTime) 1f else 0f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapAccepted = hitsText(event.x, event.y)
                if (!tapAccepted) return false // The parent still handles scrolling from gaps.
                touchStartX = event.x
                touchStartY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (tapAccepted && (abs(event.x - touchStartX) > touchSlop || abs(event.y - touchStartY) > touchSlop)) {
                    cancelTap(event)
                }
                if (!tapAccepted) return true
            }
            MotionEvent.ACTION_UP -> {
                if (tapAccepted && !hitsText(event.x, event.y)) cancelTap(event)
                if (!tapAccepted) return true
                tapAccepted = false
            }
            MotionEvent.ACTION_CANCEL -> tapAccepted = false
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun cancelTap(event: MotionEvent) {
        tapAccepted = false
        val cancel = MotionEvent.obtain(event)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    fun hitsText(x: Float, y: Float): Boolean =
        hitsText(lyricLabel, x, y) || (timeVisible && hitsText(timeLabel, x, y))

    private fun hitsText(label: TextView, x: Float, y: Float): Boolean {
        val textLayout = label.layout ?: return false
        val textX = x - label.left - label.totalPaddingLeft + label.scrollX
        val textY = y - label.top - label.totalPaddingTop + label.scrollY
        // Each wrapped line has its own hit box; empty trailing width is not interactive.
        for (line in 0 until textLayout.lineCount) {
            val left = textLayout.getLineLeft(line)
            val right = textLayout.getLineRight(line)
            if (right > left && textX >= left - hitPadding && textX <= right + hitPadding &&
                textY >= textLayout.getLineTop(line) - hitPadding && textY <= textLayout.getLineBottom(line) + hitPadding
            ) return true
        }
        return false
    }

    override fun onDetachedFromWindow() {
        textAppearanceAnimator?.cancel()
        textAppearanceAnimator = null
        targetTextAlpha = Float.NaN
        tapAccepted = false
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
