package com.jaco.musicenhance.adapter

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageView
import com.jaco.musicenhance.player.PLAYER_OVERLAY_TAG
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class NativePlayerViewsTest {
    @Test fun blurredHostBackgroundAndOurOverlayCannotReplaceSharpCover() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        fun addImage(size: Int, id: Int = android.view.View.NO_ID, tag: String? = null): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val image = ImageView(context).apply {
                this.id = id
                this.tag = tag
                setImageBitmap(bitmap)
            }
            root.addView(image)
            image.layout(0, 0, size, size)
            return bitmap
        }
        addImage(700, android.R.id.background)
        val cover = addImage(360)
        addImage(1_000, tag = PLAYER_OVERLAY_TAG)
        assertSame(cover, NativePlayerViews.findArtwork(root))
    }
}
