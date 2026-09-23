package com.jaco.musicenhance.player.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class GradientBlurArtworkViewTest {
    @Test
    fun lyricsBlurCoversTheWholeViewAndFadesWithoutHidingItsArtwork() {
        val background = GradientBlurArtworkView(RuntimeEnvironment.getApplication())
        val artwork = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        background.artwork = artwork
        val layer = (0 until background.childCount).map(background::getChildAt).filterIsInstance<ImageView>().single()

        for ((width, height) in listOf(1208 to 1392, 1392 to 1208)) {
            background.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            background.layout(0, 0, width, height)
            assertEquals(width, layer.width)
            assertEquals(height, layer.height)
            assertEquals(ImageView.ScaleType.CENTER_CROP, layer.scaleType)
            for (progress in listOf(0f, 0.4f, 1f, 0f, 1f)) {
                background.fullBlurProgress = progress
                assertEquals(progress, layer.alpha, 0f)
                assertSame(artwork, (layer.drawable as BitmapDrawable).bitmap)
            }
        }

        val nextArtwork = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        background.artwork = nextArtwork
        assertSame(nextArtwork, (layer.drawable as BitmapDrawable).bitmap)
        background.artwork = null
        assertNull((layer.drawable as? BitmapDrawable)?.bitmap)
    }
}
