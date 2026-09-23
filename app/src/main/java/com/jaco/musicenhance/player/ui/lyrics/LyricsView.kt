package com.jaco.musicenhance.player.ui.lyrics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jaco.musicenhance.player.lyrics.LyricsInteractionState
import com.jaco.musicenhance.player.model.LyricTimeline
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import kotlin.math.abs

/** App-independent lyric presentation: scrolling, row selection and blur animation. */
@SuppressLint("ViewConstructor")
internal class LyricsView(context: Context) : ScrollView(context) {
    var onSeekRequested: ((Long) -> Unit)? = null
    var onBackgroundClick: (() -> Unit)? = null

    private data class AnimatedRow(
        val view: LyricRowView,
        var blurLevel: Float = 0f,
        var startBlurLevel: Float = 0f,
        var targetBlurLevel: Float = 0f,
        var appliedBlurStep: Int = -1,
    )

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = mutableListOf<AnimatedRow>()
    private val interaction = LyricsInteractionState(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val blurEffects = LyricBlurEffects(resources.displayMetrics.density)
    private val motionCurve = PathInterpolator(0.22f, 0f, 0.2f, 1f)
    private var lyrics: LyricsSnapshot? = null
    private var activeLineIndex = -1
    private var selectedLineIndex = -1
    private var isBrowsingLyrics = false
    private var followAfterLayout = false
    private var scrollAnimator: ValueAnimator? = null
    private var blurAnimator: ValueAnimator? = null

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(22f).toInt())
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(snapshot: LyricsSnapshot, positionMs: Long) {
        if (snapshot != lyrics) {
            lyrics = snapshot
            rebuildRows(snapshot)
        }
        if (rows.isEmpty()) return
        val nowMs = SystemClock.elapsedRealtime()
        val nextActiveLine = LyricTimeline.activeIndex(snapshot.lines, positionMs)
        val resumeFollowing = interaction.consumeFollowRequest(nowMs)
        if (interaction.canFollow(nowMs) && (activeLineIndex != nextActiveLine || resumeFollowing)) {
            followLine(nextActiveLine.coerceAtLeast(0), animate = true)
        }
        activeLineIndex = nextActiveLine
        refreshInteractionAppearance(nowMs)
    }

    private fun rebuildRows(snapshot: LyricsSnapshot) {
        scrollAnimator?.cancel()
        blurAnimator?.cancel()
        fling(0)
        column.removeAllViews()
        rows.clear()
        interaction.reset()
        activeLineIndex = -1
        selectedLineIndex = -1
        isBrowsingLyrics = false
        followAfterLayout = true
        if (snapshot.lines.isEmpty()) {
            showEmptyState(snapshot.status)
        } else {
            snapshot.lines.forEachIndexed { index, line ->
                val row = LyricRowView(context, line, lyricTextSize()) {
                    interaction.reset()
                    onSeekRequested?.invoke(line.startMs)
                    followLine(index, animate = true)
                    refreshInteractionAppearance(SystemClock.elapsedRealtime())
                }
                rows += AnimatedRow(row)
                column.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        scrollTo(0, 0)
        updateScrollPadding()
    }

    private fun showEmptyState(status: LyricsStatus) {
        column.setPadding(0, 0, 0, 0)
        column.addView(TextView(context).apply {
            text = when (status) {
                LyricsStatus.LOADING -> "正在加载歌词…"
                LyricsStatus.UNSUPPORTED -> "此播放器暂不支持歌词"
                else -> "暂无可用的逐句歌词"
            }
            setTextColor(0xBBFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { onBackgroundClick?.invoke() }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, height.coerceAtLeast(1)))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) rows.forEach { it.view.setLyricTextSize(lyricTextSize()) }
        updateScrollPadding()
        if (rows.isEmpty()) column.getChildAt(0)?.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, h)
        followAfterLayout = true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (followAfterLayout && rows.firstOrNull()?.view?.height?.let { it > 0 } == true) {
            followAfterLayout = false
            followLine(activeLineIndex.coerceAtLeast(0), animate = false)
            updateVisibleRows()
        }
    }

    private fun updateScrollPadding() {
        val firstRow = rows.firstOrNull()?.view ?: return
        // First/last rows remain reachable while manually scrolling, and the final pair can
        // still sit at the top when following the current sentence.
        column.setPadding(0, height / 2, 0, (height - firstRow.singleLineHeight).coerceAtLeast(height / 2))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interaction.beginTouch(event.x, event.y, hitsLyric(event.x, event.y))
                scrollAnimator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                if (interaction.moveTouch(event.x, event.y)) refreshInteractionAppearance(SystemClock.elapsedRealtime())
            }
            MotionEvent.ACTION_POINTER_DOWN -> interaction.cancelBlankTap()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val returnToPlayer = interaction.endTouch(
                    event.x, event.y, hitsLyric(event.x, event.y),
                    cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                    nowMs = SystemClock.elapsedRealtime(),
                )
                if (returnToPlayer) {
                    cancelNativeGesture(event)
                    onBackgroundClick?.invoke()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancelNativeGesture(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun hitsLyric(x: Float, y: Float): Boolean {
        if (x < 0 || x >= width || y < 0 || y >= height) return false
        return rows.any { row ->
            val localX = x + scrollX - column.left - row.view.left
            val localY = y + scrollY - column.top - row.view.top
            localX >= 0 && localX < row.view.width && localY >= 0 && localY < row.view.height &&
                row.view.hitsText(localX, localY)
        }
    }

    private fun refreshInteractionAppearance(nowMs: Long) {
        isBrowsingLyrics = interaction.isBrowsing(nowMs)
        selectedLineIndex = if (isBrowsingLyrics) centerLineIndex() else activeLineIndex.coerceAtLeast(0)
        updateBlurTargets()
        updateVisibleRows()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (isBrowsingLyrics) selectedLineIndex = centerLineIndex()
        updateVisibleRows()
    }

    private fun centerLineIndex(): Int {
        val viewportCenter = scrollY + height / 2
        return rows.indices.minByOrNull { index ->
            abs(rows[index].view.run { top + height / 2 } - viewportCenter)
        } ?: -1
    }

    private fun followLine(index: Int, animate: Boolean) {
        val row = rows.getOrNull(index)?.view ?: return
        if (row.height == 0) return
        val previousRow = rows.getOrNull(index - 1)?.view
        val targetScrollY = (previousRow?.top ?: (row.top - row.singleLineHeight)).coerceAtLeast(0)
        scrollAnimator?.cancel()
        fling(0)
        if (!animate) { scrollTo(0, targetScrollY); return }
        scrollAnimator = ValueAnimator.ofInt(scrollY, targetScrollY).apply {
            duration = LyricsAppearance.FOLLOW_DURATION_MS
            interpolator = motionCurve
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun updateBlurTargets() {
        var changed = false
        rows.forEachIndexed { index, row ->
            val target = if (isBrowsingLyrics) 0f else LyricsAppearance.distance(index, activeLineIndex).toFloat()
            if (row.targetBlurLevel != target) changed = true
            row.targetBlurLevel = target
        }
        if (!changed) return
        blurAnimator?.cancel()
        rows.forEach { it.startBlurLevel = it.blurLevel }
        blurAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LyricsAppearance.BLUR_DURATION_MS
            interpolator = motionCurve
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                rows.forEach { it.blurLevel = it.startBlurLevel + (it.targetBlurLevel - it.startBlurLevel) * progress }
                updateVisibleRows()
            }
            start()
        }
    }

    private fun updateVisibleRows() {
        rows.forEachIndexed { index, row ->
            if (row.view.bottom < scrollY || row.view.top > scrollY + height) return@forEachIndexed
            val blurStep = blurEffects.step(row.blurLevel)
            if (row.appliedBlurStep != blurStep) {
                row.appliedBlurStep = blurStep
                row.view.setLyricBlur(blurEffects.at(blurStep))
            }
            row.view.updateAppearance(
                textAlpha = LyricsAppearance.textAlpha(
                    LyricsAppearance.distance(index, activeLineIndex),
                    focused = index == activeLineIndex || index == selectedLineIndex,
                    browsing = isBrowsingLyrics,
                ),
                showTime = isBrowsingLyrics && index == selectedLineIndex,
            )
        }
    }

    override fun onDetachedFromWindow() {
        scrollAnimator?.cancel()
        blurAnimator?.cancel()
        interaction.reset()
        isBrowsingLyrics = false
        // Discard targets as well as the animation, so reattachment starts a fresh transition.
        rows.forEach { it.targetBlurLevel = -1f }
        super.onDetachedFromWindow()
    }

    private fun lyricTextSize() = (width * 0.077f).coerceIn(dp(16f), dp(23f))
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
