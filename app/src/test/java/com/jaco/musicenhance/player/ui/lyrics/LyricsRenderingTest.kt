package com.jaco.musicenhance.player.ui.lyrics

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
