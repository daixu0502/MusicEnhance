package com.jaco.musicenhance.player.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.jaco.musicenhance.player.PlayerController
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.ui.lyrics.LyricsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CoverPlayerViewTest {
    @Test
    @Config(sdk = [34, 35])
    fun firstTraversalLaysOutControlsWithoutWaitingForPostedMessages() {
        val activityController = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = activityController.get()
            val player = CoverPlayerView(activity, activity.window, EmptyPlayerController) {}
            fun layout(width: Int, height: Int) {
                player.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                player.layout(0, 0, width, height)
            }
            layout(1208, 1392)
            val spectrum = (0 until player.childCount).map(player::getChildAt)
                .filterIsInstance<CameraSpectrumView>().single()
            assertEquals((1208 * (341f / 1208f)).toInt(), spectrum.width)
            assertEquals((1392 * (702f / 1392f)).toInt(), spectrum.height)
            val back = (0 until player.childCount).map(player::getChildAt)
                .single { it.contentDescription?.startsWith("返回 ") == true }
            assertTrue(back.width > 0 && back.height > 0)
            assertEquals((1208 * .025f).toInt(), back.top)
            layout(1000, 1200)
            assertEquals((1000 * (341f / 1208f)).toInt(), spectrum.width)
            assertEquals((1200 * (702f / 1392f)).toInt(), spectrum.height)
        } finally {
            activityController.destroy()
        }
    }

    @Test
    fun keepScreenOnFollowsTheSettingFocusAndOverlayLifetime() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = activityController.get()
            var requested = false
            val player = CoverPlayerView(activity, activity.window, EmptyPlayerController,
                keepScreenOnRequested = { requested }) {}
            activity.setContentView(player)
            shadowOf(Looper.getMainLooper()).idle()
            setWindowVisibility(player, View.VISIBLE)
            activityController.windowFocusChanged(true)
            assertFalse(player.keepScreenOn)
            requested = true
            ReflectionHelpers.callInstanceMethod<Unit>(player, "refreshKeepScreenOn")
            assertTrue("attached=${player.isAttachedToWindow}, shown=${player.isShown}, window=${player.windowVisibility}, focus=${player.hasWindowFocus()}", player.keepScreenOn)
            setWindowVisibility(player, View.GONE)
            assertFalse(player.keepScreenOn)
            setWindowVisibility(player, View.VISIBLE)
            assertTrue(player.keepScreenOn)
            player.performClick() // Lyrics mode uses the same scoped screen-on policy.
            assertTrue("attached=${player.isAttachedToWindow}, shown=${player.isShown}, window=${player.windowVisibility}, focus=${player.hasWindowFocus()}", player.keepScreenOn)
            activityController.windowFocusChanged(false)
            assertFalse(player.keepScreenOn)
            activityController.windowFocusChanged(true)
            assertTrue("attached=${player.isAttachedToWindow}, shown=${player.isShown}, window=${player.windowVisibility}, focus=${player.hasWindowFocus()}", player.keepScreenOn)
            requested = false // Disabled preference or no longer on the cover display.
            ReflectionHelpers.callInstanceMethod<Unit>(player, "refreshKeepScreenOn")
            assertFalse(player.keepScreenOn)
            requested = true
            ReflectionHelpers.callInstanceMethod<Unit>(player, "refreshKeepScreenOn")
            activity.setContentView(View(activity))
            assertFalse(player.keepScreenOn)
        } finally {
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun dismissStopsHoldingTheDisplayWithoutClearingHostWindowFlags() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = activityController.get()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val player = CoverPlayerView(activity, activity.window, EmptyPlayerController,
                keepScreenOnRequested = { true }) {}
            activity.setContentView(player)
            shadowOf(Looper.getMainLooper()).idle()
            setWindowVisibility(player, View.VISIBLE)
            activityController.windowFocusChanged(true)
            assertTrue("attached=${player.isAttachedToWindow}, shown=${player.isShown}, window=${player.windowVisibility}, focus=${player.hasWindowFocus()}", player.keepScreenOn)
            player.prepareForDismissal()
            ReflectionHelpers.callInstanceMethod<Unit>(player, "refreshKeepScreenOn")
            assertFalse(player.keepScreenOn)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        } finally {
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun metadataOnlyPlayerShowsTheNextCoverWithoutWaitingForAnUnsupportedProvider() {
        val activityController = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = activityController.get()
            val player = CoverPlayerView(activity, activity.window, EmptyPlayerController) {}
            val background = (0 until player.childCount).map(player::getChildAt)
                .filterIsInstance<GradientBlurArtworkView>().single()
            val first = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            val second = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            val song = PlayerSnapshot.Empty.copy(title = "First", artwork = first)
            fun render(snapshot: PlayerSnapshot) = ReflectionHelpers.callInstanceMethod<Unit>(player, "render",
                ReflectionHelpers.ClassParameter.from(PlayerSnapshot::class.java, snapshot))
            render(song)
            assertSame(first, background.artwork)
            render(song.copy(title = "Second", artwork = second))
            assertSame(second, background.artwork)
        } finally {
            activityController.destroy()
        }
    }

    @Test
    fun nextTrackArtworkSurvivesSnapshotsWithoutArtworkInBothPlayerModes() {
        val activityController = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = activityController.get()
            var verifiedArtwork: Bitmap? = null
            val controller = object : PlayerController by EmptyPlayerController {
                override val hasArtworkProvider = true
                override fun verifiedArtwork(snapshot: PlayerSnapshot) = verifiedArtwork
            }
            val player = CoverPlayerView(activity, activity.window, controller) {}
            val children = (0 until player.childCount).map(player::getChildAt)
            val background = children.filterIsInstance<GradientBlurArtworkView>().single()
            val images = children.filterIsInstance<ImageView>()
            fun render(snapshot: PlayerSnapshot) {
                ReflectionHelpers.callInstanceMethod<Unit>(player, "render",
                    ReflectionHelpers.ClassParameter.from(PlayerSnapshot::class.java, snapshot))
            }
            fun assertArtwork(expected: Bitmap?) {
                assertSame(expected, background.artwork)
                images.forEach { assertSame(expected, (it.drawable as? BitmapDrawable)?.bitmap) }
            }
            val first = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            val second = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            val song = PlayerSnapshot.Empty.copy(title = "First song", artist = "Artist", album = "Album", artwork = first)
            render(song)
            assertArtwork(first)
            player.performClick()
            ReflectionHelpers.getField<ValueAnimator>(player, "modeTransitionAnimator").end()

            val next = song.copy(title = "Next song", artwork = null)
            render(next)
            assertSame(first, background.artwork) // Only the background retains a transition frame.
            val thumbnail = images.single { it.contentDescription == "返回封面播放器" }
            assertNull((thumbnail.drawable as? BitmapDrawable)?.bitmap)
            verifiedArtwork = second
            render(next.copy(artwork = second))
            assertArtwork(second)
            verifiedArtwork = null
            render(next.copy(positionMs = 1_000)) // A later playback update contains no image.
            assertArtwork(second)
            render(next.copy(album = "")) // Album metadata can be temporarily omitted as well.
            assertArtwork(second)
            player.performClick()
            ReflectionHelpers.getField<ValueAnimator>(player, "modeTransitionAnimator").end()
            assertArtwork(second)
        } finally {
            activityController.destroy()
        }
    }

    @Test
    fun lyricsModeFadesInFullArtworkBlurAndRestoresPlayerMode() {
        val activityController = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = activityController.get()
            val player = CoverPlayerView(activity, activity.window, EmptyPlayerController) {}
            val children = (0 until player.childCount).map(player::getChildAt)
            val background = children.filterIsInstance<GradientBlurArtworkView>().single()
            val lyrics = children.filterIsInstance<LyricsView>().single()
            assertEquals(0f, background.fullBlurProgress, 0f)

            player.performClick()
            // This view is not attached to a host window: drive its animator explicitly.
            val entering = ReflectionHelpers.getField<ValueAnimator>(player, "modeTransitionAnimator")
            entering.currentPlayTime = 100
            assertTrue("Expected an intermediate blur frame, got ${background.fullBlurProgress}",
                background.fullBlurProgress > 0f && background.fullBlurProgress < 1f)
            entering.end()
            assertEquals(1f, background.fullBlurProgress, 0f)
            assertEquals(View.VISIBLE, lyrics.visibility)

            player.performClick()
            ReflectionHelpers.getField<ValueAnimator>(player, "modeTransitionAnimator").end()
            assertEquals(0f, background.fullBlurProgress, 0f)
            assertEquals(View.INVISIBLE, lyrics.visibility)
        } finally {
            activityController.destroy()
        }
    }

    @Test
    fun constructionAppliesInitialPlayerModeBeforeWindowAttachment() {
        val activityController = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = activityController.get()
            // The injector constructs the view before adding it to the host window. Initial
            // layout must already have all its dependencies, including the cached view groups.
            val player = CoverPlayerView(activity, activity.window, EmptyPlayerController) {}
            val children = (0 until player.childCount).map(player::getChildAt)
            assertEquals(View.VISIBLE, children.filterIsInstance<PlayerProgressView>().single().visibility)
            assertEquals(View.INVISIBLE, children.filterIsInstance<LyricsView>().single().visibility)
        } finally {
            activityController.destroy()
        }
    }

    private fun setWindowVisibility(view: View, visibility: Int) {
        // Robolectric attaches this content view with mWindowVisibility=GONE. Deliver the
        // same attach-info update and callback that ViewRootImpl sends on a real display.
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", visibility)
        view.dispatchWindowVisibilityChanged(visibility)
    }

    private object EmptyPlayerController : PlayerController {
        override val appName = "Test player"
        override fun snapshot() = PlayerSnapshot.Empty
        override fun addListener(listener: (PlayerSnapshot) -> Unit) = Unit
        override fun removeListener(listener: (PlayerSnapshot) -> Unit) = Unit
        override fun playPause() = Unit
        override fun previous() = Unit
        override fun next() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekAndPlay(positionMs: Long) = Unit
        override fun controlState() = PlayerControlState()
        override fun cycleRepeat() = false
        override fun toggleFavorite() = false
        override fun nativeArtwork(): Bitmap? = null
        override fun verifiedArtwork(snapshot: PlayerSnapshot): Bitmap? = null
        override fun bassLevel() = 0f
        override fun setSpectrumPlaybackActive(active: Boolean) = Unit
    }
}
