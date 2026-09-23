package com.jaco.musicenhance.player.lyrics

import kotlin.math.abs

/** Touch policy independent of Android Views and music apps. All times use a monotonic clock. */
internal class LyricsInteractionState(private val touchSlop: Float) {
    var isTouching = false
        private set
    var isDragging = false
        private set
    private var blankTapCandidate = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var manualUntilMs = 0L
    private var followRequested = false

    fun isBrowsing(nowMs: Long) = isDragging || nowMs < manualUntilMs
    fun canFollow(nowMs: Long) = !isTouching && !isBrowsing(nowMs)

    fun beginTouch(x: Float, y: Float, hitsText: Boolean) {
        touchStartX = x
        touchStartY = y
        isTouching = true
        isDragging = false
        blankTapCandidate = !hitsText
        followRequested = true
    }

    /** True once per gesture, when a vertical drag first crosses the native threshold. */
    fun moveTouch(x: Float, y: Float): Boolean {
        if (!isTouching) return false
        val verticalDrag = abs(y - touchStartY) > touchSlop
        if (verticalDrag || abs(x - touchStartX) > touchSlop) blankTapCandidate = false
        if (isDragging || !verticalDrag) return false
        isDragging = true
        return true
    }

    fun cancelBlankTap() { blankTapCandidate = false }

    /** Return true only for a completed tap that began and ended outside every lyric hit box. */
    fun endTouch(x: Float, y: Float, hitsText: Boolean, cancelled: Boolean, nowMs: Long): Boolean {
        val blankTap = !cancelled && isTouching && blankTapCandidate && !hitsText &&
            abs(x - touchStartX) <= touchSlop && abs(y - touchStartY) <= touchSlop
        if (isDragging) manualUntilMs = nowMs + MANUAL_BROWSE_TIMEOUT_MS
        isTouching = false
        isDragging = false
        blankTapCandidate = false
        return blankTap
    }

    fun consumeFollowRequest(nowMs: Long): Boolean {
        if (!canFollow(nowMs)) return false
        val requested = followRequested || manualUntilMs != 0L
        followRequested = false
        manualUntilMs = 0L
        return requested
    }

    fun reset() {
        isTouching = false
        isDragging = false
        blankTapCandidate = false
        manualUntilMs = 0L
        followRequested = false
    }

    private companion object {
        const val MANUAL_BROWSE_TIMEOUT_MS = 4_000L
    }
}
