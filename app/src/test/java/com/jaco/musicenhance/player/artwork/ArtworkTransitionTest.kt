package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ArtworkTransitionTest {
    private fun image() = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)

    @Test
    fun retainsABackgroundDuringLoadingAndImmediatelyUsesTheVerifiedNewCover() {
        val transition = ArtworkTransition()
        val previous = image()
        val placeholder = image()
        val current = image()
        transition.begin(previous, 0)
        assertSame(previous, transition.background(null, false, 10))
        assertSame(previous, transition.background(placeholder, false, 20))
        assertSame(current, transition.background(current, true, 100))
        assertSame(current, transition.background(current, false, 200))
    }

    @Test
    fun aFailedRequestCannotLeaveThePreviousSongOnScreenIndefinitely() {
        val transition = ArtworkTransition()
        transition.begin(image(), 0)
        assertNull(transition.background(null, false, ArtworkTransition.MAX_WAIT_MS))
        val fallback = image()
        assertSame(fallback, transition.background(fallback, false, ArtworkTransition.MAX_WAIT_MS + 1))
    }

    @Test
    fun firstOpenShowsAvailableArtworkWithoutWaitingForNetworkVerification() {
        val transition = ArtworkTransition()
        val current = image()
        transition.begin(null, 0)
        assertSame(current, transition.background(current, false, 0))
    }

    @Test
    fun aRecycledPreviousFrameIsNotDrawn() {
        val transition = ArtworkTransition()
        val previous = image()
        transition.begin(previous, 0)
        previous.recycle()
        assertNull(transition.background(null, false, 10))
    }
}
