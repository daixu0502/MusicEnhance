package com.jaco.musicenhance.player.ui.lyrics

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ScrollView
import com.jaco.musicenhance.player.lyrics.LyricSpring
import kotlin.math.roundToInt

/** One frame clock for the scroll and staggered visible rows, independent of lyric providers. */
internal class LyricsFollowAnimator(private val viewport: ScrollView, private val onFrame: () -> Unit) {
    private data class MovingRow(val view: View, val initialOffset: Float, val delayMs: Long = 0L)

    private var movingRows = emptyList<MovingRow>()
    private var animator: ValueAnimator? = null

    fun moveTo(rows: List<View>, requestedScrollY: Int, animate: Boolean) {
        cancelAnimation()
        viewport.fling(0)
        val contentHeight = viewport.getChildAt(0)?.height ?: 0
        val targetScrollY = requestedScrollY.coerceIn(0, (contentHeight - viewport.height).coerceAtLeast(0))
        val startScrollY = viewport.scrollY
        val scrollDistance = targetScrollY - startScrollY
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            clearRowOffsets()
            rows.forEach { it.translationY = 0f }
            viewport.scrollTo(0, targetScrollY)
            onFrame()
            return
        }
        val nextRows = selectMovingRows(rows, startScrollY, targetScrollY)
        // Capture existing offsets before resetting to keep rapid retargets continuous.
        clearRowOffsets()
        movingRows = nextRows
        nextRows.forEach { it.view.translationY = it.initialOffset }
        if (scrollDistance == 0 && nextRows.all { it.initialOffset == 0f }) {
            clearRowOffsets()
            onFrame()
            return
        }
        animateFollow(nextRows, targetScrollY, scrollDistance)
    }

    private fun selectMovingRows(rows: List<View>, startScrollY: Int, targetScrollY: Int): List<MovingRow> {
        // Include outgoing and incoming rows, not every intervening line on a long seek.
        // Use actual View bounds so wrapped lyrics and in-flight translations remain correct.
        val visibleRows = rows.filter {
            isVisibleAt(it, startScrollY, it.translationY) || isVisibleAt(it, targetScrollY)
        }.let { if (targetScrollY < startScrollY) it.asReversed() else it }
        return visibleRows.mapIndexed { index, view ->
            MovingRow(view, view.translationY, index.coerceAtMost(MAX_STAGGER_ROWS) * ROW_STAGGER_MS)
        }
    }

    private fun isVisibleAt(row: View, scrollY: Int, translationY: Float = 0f): Boolean =
        row.bottom + translationY >= scrollY && row.top + translationY <= scrollY + viewport.height

    private fun animateFollow(rows: List<MovingRow>, targetScrollY: Int, scrollDistance: Int) {
        val durationMs = LyricSpring.SETTLE_MS + (rows.lastOrNull()?.delayMs ?: 0L)
        animator = ValueAnimator.ofFloat(0f, durationMs.toFloat()).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsedMs = (it.animatedValue as Float).toLong()
                val remainingScroll = scrollDistance * LyricSpring.remainingDisplacement(elapsedMs)
                viewport.scrollTo(0, (targetScrollY - remainingScroll).roundToInt())
                rows.forEach { row ->
                    // Compensate for the shared viewport scroll to give each row its own delay.
                    row.view.translationY = if (elapsedMs >= durationMs) 0f else
                        viewport.scrollY - targetScrollY + (scrollDistance + row.initialOffset) *
                            LyricSpring.remainingDisplacement(elapsedMs - row.delayMs)
                }
                onFrame()
            }
            start()
        }
    }

    fun stopForTouch() {
        cancelAnimation()
        val offsets = movingRows.map { MovingRow(it.view, it.view.translationY) }
        if (offsets.all { it.initialOffset == 0f }) return
        // Stop driving scrollY immediately. Only inter-row lag settles while native scrolling
        // takes over, so putting a finger down does not jump to the final lyric.
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = TOUCH_SETTLE_MS
            interpolator = PathInterpolator(0.22f, 0f, 0.2f, 1f)
            addUpdateListener {
                val remainingFraction = it.animatedValue as Float
                offsets.forEach { row -> row.view.translationY = row.initialOffset * remainingFraction }
                onFrame()
            }
            start()
        }
    }

    fun reset() {
        cancelAnimation()
        clearRowOffsets()
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun clearRowOffsets() {
        movingRows.forEach { it.view.translationY = 0f }
        movingRows = emptyList()
    }

    private companion object {
        const val ROW_STAGGER_MS = 40L
        const val MAX_STAGGER_ROWS = 6
        const val TOUCH_SETTLE_MS = 160L
    }
}
