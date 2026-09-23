package com.jaco.musicenhance.player

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlayerOverlayLayerTest {
    @Test fun playerAddedAfterHostLayoutCoversTheWindowInTheSameFrame() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val root = FrameLayout(controller.get())
            root.layout(0, 0, 1208, 1392)
            val player = View(root.context)
            root.addView(player)
            assertEquals(0, player.width)
            val layer = PlayerOverlayLayer(root, player)
            layer.start()
            assertEquals(1208, player.width)
            assertEquals(1392, player.height)
            // A new outer-screen size must not leave an uncovered strip on its first frame.
            root.layout(0, 0, 1000, 1200)
            layer.onPreDraw()
            assertEquals(1000, player.width)
            assertEquals(1200, player.height)
            layer.stop()
        } finally { controller.destroy() }
    }

    @Test fun lateNativeContentCannotCoverPlayerAndReorderingDoesNotDetachIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(controller.get())
            controller.get().setContentView(root)
            val player = View(root.context)
            root.addView(player)
            var detachCount = 0
            player.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { detachCount++ }
            })
            var reorderCount = 0
            val layer = PlayerOverlayLayer(root, player) { reorderCount++ }
            layer.start()
            val nativeContent = View(root.context).apply { elevation = 8f }
            root.addView(nativeContent)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertSame(player, root.getChildAt(root.childCount - 1))
            assertTrue(player.z >= nativeContent.z)
            assertSame(root, nativeContent.parent)
            assertEquals(0, detachCount)
            repeat(5) { root.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(1, reorderCount)
            layer.stop()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun removingPlayerReleasesTheDrawListenerAndLeavesNativeViewsAlone() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(controller.get())
            controller.get().setContentView(root)
            val nativeContent = View(root.context)
            val player = View(root.context)
            root.addView(nativeContent)
            root.addView(player)
            var reorders = 0
            PlayerOverlayLayer(root, player) { reorders++ }.start()
            root.removeView(player)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(1, root.childCount)
            assertSame(nativeContent, root.getChildAt(0))
            assertEquals(0f, nativeContent.z)
            assertEquals(0, reorders)
        } finally { controller.pause().stop().destroy() }
    }
}
