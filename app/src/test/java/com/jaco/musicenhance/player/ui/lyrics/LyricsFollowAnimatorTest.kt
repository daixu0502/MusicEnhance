package com.jaco.musicenhance.player.ui.lyrics

import android.animation.ValueAnimator
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LyricsFollowAnimatorTest {
    private class Scene {
        val context = RuntimeEnvironment.getApplication()
        val viewport = ScrollView(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = (0..11).map { index ->
            View(context).also { column.addView(it, LinearLayout.LayoutParams(400, if (index == 3) 120 else 80)) }
        }
        val follow = LyricsFollowAnimator(viewport) {}
        init {
            viewport.addView(column)
            viewport.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
            viewport.layout(0, 0, 400, 360)
            viewport.scrollTo(0, 200)
        }
        fun animator(): ValueAnimator = ReflectionHelpers.getField(follow, "animator")
        fun y(index: Int) = rows[index].y - viewport.scrollY
    }

    @Test fun linesMoveUpInSequenceAndSettleAtTheirRealLayoutPositions() {
        val scene = Scene()
        val firstY = scene.y(3)
        val secondY = scene.y(4)
        scene.follow.moveTo(scene.rows, 320, animate = true)
        scene.animator().currentPlayTime = 120
        assertTrue(firstY - scene.y(3) > secondY - scene.y(4))
        assertTrue(secondY - scene.y(4) > 0f)
        scene.animator().end()
        assertEquals(320, scene.viewport.scrollY)
        scene.rows.forEach { assertEquals(0f, it.translationY, 0f) }
    }

    @Test fun retargetingMidAnimationKeepsTheVisibleRowsContinuous() {
        val scene = Scene()
        scene.follow.moveTo(scene.rows, 320, animate = true)
        scene.animator().currentPlayTime = 150
        val previousPositions = (3..5).map(scene::y)
        scene.follow.moveTo(scene.rows, 440, animate = true)
        (3..5).forEachIndexed { index, row -> assertEquals(previousPositions[index], scene.y(row), 1f) }
        scene.animator().end()
        assertEquals(440, scene.viewport.scrollY)
        scene.rows.forEach { assertEquals(0f, it.translationY, 0f) }
    }

    @Test fun touchingStopsAutomaticScrollAndDoesNotOverrideTheManualDrag() {
        val scene = Scene()
        scene.follow.moveTo(scene.rows, 320, animate = true)
        scene.animator().currentPlayTime = 150
        val scrollAtTouch = scene.viewport.scrollY
        scene.follow.stopForTouch()
        scene.animator().currentPlayTime = 80
        assertEquals(scrollAtTouch, scene.viewport.scrollY)
        scene.viewport.scrollTo(0, scrollAtTouch + 35)
        scene.animator().end()
        assertEquals(scrollAtTouch + 35, scene.viewport.scrollY)
        scene.rows.forEach { assertEquals(0f, it.translationY, 0f) }
    }

    @Test fun layoutChangesAndTrackReplacementClearPendingMotion() {
        val scene = Scene()
        scene.follow.moveTo(scene.rows, 320, animate = true)
        scene.animator().currentPlayTime = 150
        scene.follow.moveTo(scene.rows, 0, animate = false)
        assertEquals(0, scene.viewport.scrollY)
        scene.rows.forEach { assertEquals(0f, it.translationY, 0f) }
        scene.follow.moveTo(scene.rows, 400, animate = true)
        scene.animator().currentPlayTime = 150
        scene.follow.reset()
        scene.rows.forEach { assertEquals(0f, it.translationY, 0f) }
    }

    @Test fun followingAnAlreadyAlignedLineDoesNotStartAnAnimation() {
        val scene = Scene()
        scene.follow.moveTo(scene.rows, scene.viewport.scrollY, animate = true)
        assertNull(ReflectionHelpers.getField<ValueAnimator?>(scene.follow, "animator"))
        assertEquals(200, scene.viewport.scrollY)
        scene.rows.forEach { assertEquals(0f, it.translationY, 0f) }
    }
}
