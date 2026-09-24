package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import android.os.Looper
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.EnhancedPlayerController
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.PlayerActions
import com.jaco.musicenhance.player.TestPlayerDataSource
import com.jaco.musicenhance.player.model.PlayerDisplayState
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ArtworkProviderTest {
    @Test fun metadataOnlyPlayerDoesNotPromiseAnAsyncArtworkSource() {
        val controller = EnhancedPlayerController(PlayerSession("Other player", TestPlayerDataSource(), PlayerActions({}, {}, {}, {}, {})))
        var state = PlayerDisplayState()
        controller.addListener { state = it }
        controller.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(state.artwork)
        controller.release()
    }

    @Test fun otherPlayersCanProvideFallbackArtAndReleaseBothProviders() {
        val image = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
        val song = PlayerSnapshot.Empty.copy(title = "Other app song")
        var receivedSong: PlayerSnapshot? = null
        var artworkReleases = 0
        var lyricsReleases = 0
        val artwork = object : ArtworkProvider {
            override fun snapshot(player: PlayerSnapshot): Bitmap {
                receivedSong = player
                return image
            }
            override fun release() { artworkReleases++ }
        }
        val lyrics = object : LyricsProvider by LyricsProvider.Unsupported {
            override fun release() { lyricsReleases++ }
        }
        val data = TestPlayerDataSource(song)
        val controller = EnhancedPlayerController(PlayerSession("Other player", data, PlayerActions({}, {}, {}, {}, {}), lyrics, artwork))
        var state = PlayerDisplayState()
        controller.addListener { state = it }
        controller.setActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(image, state.artwork)
        assertSame(song, receivedSong)
        controller.release()
        assertEquals(1, artworkReleases)
        assertEquals(1, lyricsReleases)
        controller.release()
        assertTrue(data.listeners.isEmpty())
        assertEquals(1, artworkReleases)
    }
}
