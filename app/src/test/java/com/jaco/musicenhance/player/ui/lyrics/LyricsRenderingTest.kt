package com.jaco.musicenhance.player.ui.lyrics

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LyricsRenderingTest {
    @Test fun wholeSentenceFadesFromGrayAndRapidRetargetKeepsTheVisibleState() {
        val row = LyricRowView(RuntimeEnvironment.getApplication(), LyricLine(1000, "歌词"), 22f) {}
        val label = row.getChildAt(0) as TextView
        assertEquals(LyricsAppearance.INACTIVE_TEXT_COLOR, label.currentTextColor)
        assertEquals(LyricsAppearance.INACTIVE_TEXT_ALPHA, label.alpha, 0.001f)

        row.updateAppearance(1f, active = true, showTime = false)
        val fadeIn = ReflectionHelpers.getField<ValueAnimator>(row, "textAppearanceAnimator")
        fadeIn.currentPlayTime = 100
        val intermediateColor = label.currentTextColor
        val intermediateAlpha = label.alpha
        assertTrue(Color.red(intermediateColor) > Color.red(LyricsAppearance.INACTIVE_TEXT_COLOR))
        assertTrue(Color.red(intermediateColor) < 255)
        assertTrue(intermediateAlpha > LyricsAppearance.INACTIVE_TEXT_ALPHA && intermediateAlpha < 1f)
        row.updateAppearance(1f, active = true, showTime = false)
        assertSame(fadeIn, ReflectionHelpers.getField(row, "textAppearanceAnimator"))

        row.updateAppearance(LyricsAppearance.INACTIVE_TEXT_ALPHA, active = false, showTime = false)
        assertFalse(fadeIn.isStarted)
        assertEquals(intermediateColor, label.currentTextColor)
        assertEquals(intermediateAlpha, label.alpha, 0.001f)
        ReflectionHelpers.getField<ValueAnimator>(row, "textAppearanceAnimator").end()
        assertEquals(LyricsAppearance.INACTIVE_TEXT_COLOR, label.currentTextColor)
        assertEquals(LyricsAppearance.INACTIVE_TEXT_ALPHA, label.alpha, 0.001f)

        row.updateAppearance(1f, active = true, showTime = false)
        val finalFade = ReflectionHelpers.getField<ValueAnimator>(row, "textAppearanceAnimator")
        finalFade.end()
        assertEquals(Color.WHITE, label.currentTextColor)
        assertEquals(1f, label.alpha, 0.001f)
        row.updateAppearance(0.5f, active = false, showTime = false)
        val interrupted = ReflectionHelpers.getField<ValueAnimator>(row, "textAppearanceAnimator")
        ReflectionHelpers.callInstanceMethod<Unit>(row, "onDetachedFromWindow")
        assertFalse(interrupted.isStarted)
    }

    @Test fun introDoesNotHighlightFirstSentenceAndSeekingMovesTheWhiteHighlight() {
        val lyrics = LyricsView(RuntimeEnvironment.getApplication())
        val snapshot = LyricsSnapshot("track", LyricsStatus.READY,
            listOf(LyricLine(1000, "第一句"), LyricLine(2000, "第二句")))
        lyrics.render(snapshot, 0)
        layout(lyrics, 400, 600)
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val column = lyrics.getChildAt(0) as LinearLayout
        fun settle() {
            lyrics.draw(canvas)
            for (index in 0 until column.childCount) {
                ReflectionHelpers.getField<ValueAnimator?>(column.getChildAt(index), "textAppearanceAnimator")?.end()
            }
        }
        fun color(index: Int) = ((column.getChildAt(index) as LyricRowView).getChildAt(0) as TextView).currentTextColor
        try {
            settle()
            assertEquals(LyricsAppearance.INACTIVE_TEXT_COLOR, color(0))
            lyrics.render(snapshot, 1000)
            settle()
            assertEquals(Color.WHITE, color(0))
            assertEquals(LyricsAppearance.INACTIVE_TEXT_COLOR, color(1))
            lyrics.render(snapshot, 2000)
            settle()
            assertEquals(LyricsAppearance.INACTIVE_TEXT_COLOR, color(0))
            assertEquals(Color.WHITE, color(1))
            lyrics.render(snapshot, 1000)
            settle()
            assertEquals(Color.WHITE, color(0))
            assertEquals(LyricsAppearance.INACTIVE_TEXT_COLOR, color(1))
        } finally {
            ReflectionHelpers.callInstanceMethod<Unit>(lyrics, "onDetachedFromWindow")
            bitmap.recycle()
        }
    }

    @Test fun multipleAnimationUpdatesApplyOnlyTheLatestBlurAtDrawTime() {
        val lyrics = LyricsView(RuntimeEnvironment.getApplication())
        val snapshot = LyricsSnapshot("track", LyricsStatus.READY,
            (0..20).map { LyricLine(it * 1_000L, "Line $it") })
        lyrics.render(snapshot, 0)
        layout(lyrics, 400, 600)
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        try {
            lyrics.draw(canvas)
            val rows = ReflectionHelpers.getField<List<Any>>(lyrics, "rows")
            val row = rows[1]
            fun appliedStep() = ReflectionHelpers.getField<Int>(row, "appliedBlurStep")
            val before = appliedStep()
            val blur = ReflectionHelpers.getField<ValueAnimator>(lyrics, "blurAnimator")
            blur.currentPlayTime = 80
            blur.currentPlayTime = 160
            lyrics.scrollTo(0, lyrics.scrollY + 1)
            assertEquals(before, appliedStep())
            blur.end()
            lyrics.draw(canvas)
            assertEquals(LyricsAppearance.BLUR_STEPS_PER_LINE, appliedStep())
            // Repeated frames with no state change do not alter the already-applied effect.
            lyrics.draw(canvas)
            assertEquals(LyricsAppearance.BLUR_STEPS_PER_LINE, appliedStep())
            val column = lyrics.getChildAt(0) as LinearLayout
            assertFalse(column.clipChildren)
            assertTrue(lyrics.clipChildren) // Keep the real viewport boundary.
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun blurPaddingPreservesRowSpacingAndDoesNotExpandTheClickableText() {
        val context = RuntimeEnvironment.getApplication()
        val row = LyricRowView(context, LyricLine(0, "歌词 Agyp"), 22f) {}
        layout(row, 400, null)
        val label = row.getChildAt(0) as TextView
        val density = context.resources.displayMetrics.density
        val originalSpacing = (18f * density).toInt() * 2
        assertEquals(originalSpacing, row.paddingTop + row.paddingBottom + label.paddingTop + label.paddingBottom)
        // TextView excludes extra line spacing after its final line.
        assertEquals(label.layout.height + originalSpacing, row.height)
        assertTrue(label.paddingTop >= LyricsAppearance.BLUR_PADDING_DP * density - 1)
        assertFalse(row.clipChildren)
        val textX = label.left + label.totalPaddingLeft + 8f
        val textY = label.top + label.totalPaddingTop + label.layout.getLineBottom(0) / 2f
        assertTrue(row.hitsText(textX, textY))
        assertFalse(row.hitsText(textX, 0f))
        assertFalse(row.hitsText(textX, row.height.toFloat()))
    }

    private fun layout(view: View, width: Int, height: Int?) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height ?: 0,
                if (height == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
