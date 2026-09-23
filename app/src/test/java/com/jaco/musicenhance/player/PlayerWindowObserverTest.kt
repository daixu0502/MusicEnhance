package com.jaco.musicenhance.player

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlayerWindowObserverTest {
    @Test fun coldStartChecksTheLaidOutWindowBeforeDrawingWithoutPolling() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val root = FrameLayout(controller.get())
            val sizes = mutableListOf<Int>()
            val observer = PlayerWindowObserver(root) { sizes += root.width }
            observer.start()
            assertEquals(listOf(0), sizes)
            root.layout(0, 0, 1208, 1392)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(listOf(0, 1208), sizes)
            repeat(5) { root.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(2, sizes.size)
            observer.stop()
        } finally { controller.destroy() }
    }

    @Test fun returningBeforeFirstDrawCancelsTheOldWindowAndResumeRearmsIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val root = FrameLayout(controller.get())
            var updates = 0
            val observer = PlayerWindowObserver(root) { updates++ }
            observer.start()
            observer.stop()
            root.layout(0, 0, 1208, 1392)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(1, updates)
            observer.start()
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(3, updates)
            observer.stop()
            root.layout(0, 0, 1224, 2912)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(3, updates)
        } finally { controller.destroy() }
    }

    @Test fun hostLayoutRequestsNeverBlockDrawing() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val root = FrameLayout(controller.get())
            var calls = 0
            val observer = PlayerWindowObserver(root) {
                if (++calls == 2) root.addView(View(root.context))
            }
            observer.start()
            root.layout(0, 0, 1208, 1392)
            // ViewTreeObserver returns true when at least one listener cancels this draw.
            assertFalse(root.viewTreeObserver.dispatchOnPreDraw())
            root.measure(View.MeasureSpec.makeMeasureSpec(1208, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1392, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1208, 1392)
            assertFalse(root.viewTreeObserver.dispatchOnPreDraw())
            assertEquals(2, calls)
            observer.stop()
        } finally { controller.destroy() }
    }

    @Test fun attachingMigratesThePendingListenerWithoutCreatingAPerFrameLoop() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(controller.get())
            var updates = 0
            val observer = PlayerWindowObserver(root) { updates++ }
            observer.start() // Registers against the still-unattached view tree.
            val floating = root.viewTreeObserver
            controller.get().setContentView(root)
            assertFalse(floating.isAlive)
            root.layout(0, 0, 1208, 1392)
            root.viewTreeObserver.dispatchOnPreDraw()
            val countAfterAttach = updates
            assertTrue(countAfterAttach >= 2)
            repeat(8) {
                root.requestLayout()
                assertFalse(root.viewTreeObserver.dispatchOnPreDraw())
            }
            assertEquals(countAfterAttach, updates)
            observer.stop()
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(countAfterAttach, updates)
        } finally { controller.pause().stop().destroy() }
    }
}
