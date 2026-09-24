package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.media.MediaSessionPlayerController
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ArtworkProviderTest {
    @Test fun metadataOnlyPlayerDoesNotPromiseAnAsyncArtworkSource() {
        val controller = MediaSessionPlayerController("Other player")
        assertFalse(controller.holdPreviousArtworkWhileLoading)
        assertNull(controller.verifiedArtwork(PlayerSnapshot.Empty))
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
        val controller = MediaSessionPlayerController("Other player", lyrics, artwork)
        assertTrue(controller.holdPreviousArtworkWhileLoading)
        assertSame(image, controller.verifiedArtwork(song))
        assertSame(song, receivedSong)
        controller.release()
        assertEquals(1, artworkReleases)
        assertEquals(1, lyricsReleases)
        assertSame(image, controller.verifiedArtwork(song))
    }
}
