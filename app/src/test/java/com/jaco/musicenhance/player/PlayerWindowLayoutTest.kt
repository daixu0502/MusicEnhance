package com.jaco.musicenhance.player

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class PlayerWindowLayoutTest {
    @Test fun dedicatedPlayerAllowsCameraAreaBeforeFirstFrameWithoutChangingItsComponentTitle() {
        Robolectric.buildActivity(Activity::class.java).create().use { controller ->
            val window = controller.get().window
            window.attributes = window.attributes.apply {
                title = "com.kugou.android.lite/com.kugou.android.app.player.land.LandPlayerActivity"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            val originalTitle = window.attributes.title
            val originalFlags = window.attributes.flags
            val policy = PlayerWindowLayout(window)
            policy.enter()
            policy.enter() // Resume must not replace the saved host value with ALWAYS.
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, window.attributes.layoutInDisplayCutoutMode)
            assertEquals(originalTitle, window.attributes.title)
            assertEquals(originalFlags, window.attributes.flags)
            policy.restore()
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT, window.attributes.layoutInDisplayCutoutMode)
        }
    }

    @Test fun closingIndependentPlayerDoesNotChangeHomeWindowOrUndoUnrelatedHostAttributes() {
        Robolectric.buildActivity(Activity::class.java).setup().use { home ->
            Robolectric.buildActivity(Activity::class.java).setup().use { player ->
                val homeAttributes = WindowManager.LayoutParams().apply { copyFrom(home.get().window.attributes) }
                val window = player.get().window
                val policy = PlayerWindowLayout(window)
                policy.enter()
                window.attributes = window.attributes.apply {
                    title = "Host updated title"
                    screenBrightness = 0.7f
                }
                policy.restore()
                assertEquals("Host updated title", window.attributes.title.toString())
                assertEquals(0.7f, window.attributes.screenBrightness)
                assertEquals(0, homeAttributes.copyFrom(home.get().window.attributes))
            }
        }
    }

    @Test fun reenteringAfterPanelChangeSavesTheNewNativeCutoutPolicy() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            val policy = PlayerWindowLayout(window)
            policy.enter()
            policy.restore()
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            policy.enter()
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, window.attributes.layoutInDisplayCutoutMode)
            policy.restore()
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, window.attributes.layoutInDisplayCutoutMode)
        }
    }

    @Test fun embeddedPagePreservesNativeTitleChangesWhileRestoringCutoutPolicy() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            val originalMode = window.attributes.layoutInDisplayCutoutMode
            val policy = PlayerWindowLayout(window, "MusicEnhance:example/Home")
            policy.enter()
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, window.attributes.layoutInDisplayCutoutMode)
            window.attributes = window.attributes.apply { title = "Different host page" }
            policy.restore()
            assertEquals("Different host page", window.attributes.title.toString())
            assertEquals(originalMode, window.attributes.layoutInDisplayCutoutMode)
        }
    }

    @Test fun repeatedExitDoesNotOverwriteLaterHostChanges() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            val policy = PlayerWindowLayout(window, "MusicEnhance:example/Home")
            policy.enter()
            policy.restore()
            window.attributes = window.attributes.apply {
                title = "Updated native page"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
            policy.restore()
            assertEquals("Updated native page", window.attributes.title.toString())
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER, window.attributes.layoutInDisplayCutoutMode)
        }
    }

    @Test fun leavingPlayerRestoresHostWindowAttributes() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            window.attributes = window.attributes.apply {
                title = "Native home"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
            val policy = PlayerWindowLayout(window, "MusicEnhance:example/Home")
            policy.enter()
            assertEquals("MusicEnhance:example/Home", window.attributes.title.toString())
            policy.restore()
            assertEquals("Native home", window.attributes.title.toString())
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER, window.attributes.layoutInDisplayCutoutMode)
        }
    }
}
