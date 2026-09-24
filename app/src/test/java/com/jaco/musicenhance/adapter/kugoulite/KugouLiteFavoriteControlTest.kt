package com.jaco.musicenhance.adapter.kugoulite

import android.widget.FrameLayout
import android.widget.ImageView
import com.jaco.musicenhance.player.PLAYER_OVERLAY_TAG
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class KugouLiteFavoriteControlTest {
    private val tagId = 0x7f0a116a
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun compressedResourceNamesUseNativeFieldAndReadBothFavoriteStates() {
        val root = FrameLayout(context)
        val button = ImageView(context).apply {
            setTag(tagId, true)
            setOnClickListener { }
        }
        root.addView(button)
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = if (name == "ml.h") NativeIds::class.java else super.loadClass(name)
        }
        val control = KugouLiteFavoriteControl(root, loader)
        assertEquals(true, control.read(PlayerSnapshot.Empty).favorite)
        button.setTag(tagId, false)
        assertEquals(false, control.read(PlayerSnapshot.Empty).favorite)
    }

    @Test fun nativeClickDoesNotPretendTheFavoriteRequestAlreadySucceeded() {
        val root = FrameLayout(context)
        var clicks = 0
        val button = ImageView(context).apply {
            setTag(tagId, false)
            setOnClickListener { clicks++ }
        }
        root.addView(button)
        val control = KugouLiteFavoriteControl(root, tagId)
        assertTrue(control.toggle(PlayerSnapshot.Empty))
        assertEquals(1, clicks)
        assertEquals(false, control.read(PlayerSnapshot.Empty).favorite)
        button.setTag(tagId, true) // Host callback confirms the mutation.
        assertEquals(true, control.read(PlayerSnapshot.Empty).favorite)
    }

    @Test fun missingStateAndModuleControlsNeverMasqueradeAsNativeFavoriteButton() {
        val root = FrameLayout(context)
        val overlay = FrameLayout(context).apply { tag = PLAYER_OVERLAY_TAG }
        overlay.addView(ImageView(context).apply { setTag(tagId, true); setOnClickListener { fail("Overlay clicked") } })
        root.addView(overlay)
        root.addView(ImageView(context).apply { setOnClickListener { fail("Unknown state clicked") } })
        val control = KugouLiteFavoriteControl(root, tagId)
        assertNull(control.read(PlayerSnapshot.Empty).favorite)
        assertFalse(control.toggle(PlayerSnapshot.Empty))
        assertNull(KugouLiteFavoriteControl(root, 0).read(PlayerSnapshot.Empty).favorite)
    }

    class NativeIds {
        companion object {
            @JvmField var kg_player_song_like_button = 0x7f0a116a
        }
    }
}
