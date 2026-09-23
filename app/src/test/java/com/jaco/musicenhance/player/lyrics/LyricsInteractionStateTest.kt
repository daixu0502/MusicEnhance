package com.jaco.musicenhance.player.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsInteractionStateTest {
    private val state = LyricsInteractionState(touchSlop = 8f)

    @Test fun blankTapReturnsToPlayerWithoutEnteringManualBrowse() {
        state.beginTouch(20f, 30f, hitsText = false)
        assertFalse(state.isBrowsing(100))
        assertFalse(state.canFollow(100))
        assertTrue(state.endTouch(22f, 32f, hitsText = false, cancelled = false, nowMs = 120))
        assertFalse(state.isBrowsing(120))
        assertTrue(state.consumeFollowRequest(120))
        assertFalse(state.consumeFollowRequest(121))
    }

    @Test fun textTapAndReleaseOutsideTextNeverBecomeBackgroundClicks() {
        state.beginTouch(20f, 30f, hitsText = true)
        assertFalse(state.endTouch(21f, 31f, hitsText = false, cancelled = false, nowMs = 100))
        assertFalse(state.isBrowsing(100))
    }

    @Test fun dragStartingInBlankSpaceBrowsesInsteadOfClosing() {
        state.beginTouch(20f, 30f, hitsText = false)
        assertFalse(state.moveTouch(20f, 38f))
        assertTrue(state.moveTouch(20f, 39f))
        assertFalse(state.moveTouch(20f, 50f))
        assertTrue(state.isBrowsing(10_000)) // Holding a drag never times out under the finger.
        assertFalse(state.endTouch(20f, 50f, hitsText = false, cancelled = false, nowMs = 10_100))
        assertTrue(state.isBrowsing(14_099))
        assertFalse(state.isBrowsing(14_100))
        assertTrue(state.consumeFollowRequest(14_100))
    }

    @Test fun swipeReturningToItsStartingPointCannotBecomeATap() {
        state.beginTouch(20f, 30f, hitsText = false)
        state.moveTouch(20f, 100f)
        state.moveTouch(20f, 30f)
        assertFalse(state.endTouch(20f, 30f, hitsText = false, cancelled = false, nowMs = 100))
    }

    @Test fun horizontalSwipeDoesNotCancelBlurOrCloseThePage() {
        state.beginTouch(20f, 30f, hitsText = false)
        assertFalse(state.moveTouch(60f, 30f))
        assertFalse(state.isBrowsing(100))
        assertFalse(state.endTouch(20f, 30f, hitsText = false, cancelled = false, nowMs = 100))
    }

    @Test fun cancelledGestureAndMultiplePointersCannotCloseThePage() {
        state.beginTouch(20f, 30f, hitsText = false)
        assertFalse(state.endTouch(20f, 30f, hitsText = false, cancelled = true, nowMs = 100))
        state.beginTouch(20f, 30f, hitsText = false)
        state.cancelBlankTap()
        assertFalse(state.endTouch(20f, 30f, hitsText = false, cancelled = false, nowMs = 200))
    }

    @Test fun seekOrTrackChangeClearsManualBrowsingAndPendingTouches() {
        state.beginTouch(20f, 30f, hitsText = true)
        state.moveTouch(20f, 100f)
        state.reset()
        assertFalse(state.isBrowsing(100))
        assertTrue(state.canFollow(100))
        assertFalse(state.consumeFollowRequest(100))
        assertFalse(state.endTouch(20f, 30f, hitsText = false, cancelled = false, nowMs = 200))
    }

    @Test fun touchDuringManualBrowseDoesNotExtendTimeoutWithoutDragging() {
        state.beginTouch(20f, 30f, hitsText = true)
        state.moveTouch(20f, 60f)
        state.endTouch(20f, 60f, hitsText = true, cancelled = false, nowMs = 100)
        state.beginTouch(20f, 30f, hitsText = true)
        assertTrue(state.isBrowsing(4_099))
        assertFalse(state.isBrowsing(4_100))
        assertFalse(state.canFollow(4_100))
        state.endTouch(20f, 30f, hitsText = true, cancelled = false, nowMs = 4_200)
        assertTrue(state.canFollow(4_200))
    }
}
