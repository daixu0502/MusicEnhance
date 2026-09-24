package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlayerArtworkResolverTest {
    @Test fun boundedHoldKeepsOldBackgroundButNeverOldThumbnail() {
        val provider = TestArtworkProvider(hold = true)
        val resolver = PlayerArtworkResolver(provider) { null }
        val first = bitmap()
        val second = bitmap()
        val song = PlayerSnapshot.Empty.copy(title = "First", artwork = first)
        assertSame(first, resolver.resolve(song, 0).background)
        val next = song.copy(title = "Next", artwork = null)
        val pending = resolver.resolve(next, 50)
        assertSame(first, pending.background)
        assertNull(pending.thumbnail)
        assertSame(first, resolver.resolve(next.copy(artwork = second), 100).background)
        assertSame(second, resolver.resolve(next, 50 + ArtworkTransition.MAX_WAIT_MS).background)
    }

    @Test fun verifiedCoverEndsHoldAndSurvivesMissingMetadataAndAlbum() {
        val provider = TestArtworkProvider(hold = true)
        val resolver = PlayerArtworkResolver(provider) { null }
        val first = bitmap()
        val verified = bitmap(700)
        val song = PlayerSnapshot.Empty.copy(title = "First", album = "Album", artwork = first)
        resolver.resolve(song, 0)
        val next = song.copy(title = "Next", artwork = null)
        resolver.resolve(next, 50)
        provider.image = verified
        assertSame(verified, resolver.resolve(next, 100).background)
        provider.image = null
        val result = resolver.resolve(next.copy(album = "", artwork = first), 200)
        assertSame(verified, result.background)
        assertSame(verified, result.thumbnail)
    }

    @Test fun optionalHighResolutionDoesNotDelayNativeCoverAndUpgradesWhenReady() {
        val provider = TestArtworkProvider(hold = false)
        val resolver = PlayerArtworkResolver(provider) { null }
        val first = bitmap()
        val second = bitmap()
        resolver.resolve(PlayerSnapshot.Empty.copy(title = "First", artwork = first), 0)
        val next = PlayerSnapshot.Empty.copy(title = "Next", artwork = second)
        assertSame(second, resolver.resolve(next, 50).background)
        provider.image = bitmap(700)
        assertSame(provider.image, resolver.resolve(next, 100).background)
    }

    @Test fun metadataOnlyPlayerDoesNotWaitForAnUnsupportedProvider() {
        val resolver = PlayerArtworkResolver(ArtworkProvider.Unsupported) { null }
        val first = PlayerSnapshot.Empty.copy(title = "First", artwork = bitmap())
        resolver.resolve(first, 0)
        val next = first.copy(title = "Next", artwork = bitmap())
        assertSame(next.artwork, resolver.resolve(next, 50).background)
    }

    @Test fun nativePollIsBoundedAndResetsOnTrackChangeWithoutKeepingRecycledArtwork() {
        var reads = 0
        var native: Bitmap? = bitmap()
        val resolver = PlayerArtworkResolver(ArtworkProvider.Unsupported) { reads++; native }
        val song = PlayerSnapshot.Empty.copy(title = "First")
        assertSame(native, resolver.resolve(song, 0).background)
        resolver.resolve(song, 50)
        assertEquals(1, reads)
        native!!.recycle()
        native = null
        val next = song.copy(title = "Next")
        assertNull(resolver.resolve(next, 100).background)
        assertEquals(2, reads)
    }

    private class TestArtworkProvider(private val hold: Boolean) : ArtworkProvider {
        var image: Bitmap? = null
        override val holdPreviousArtworkWhileLoading get() = hold
        override fun snapshot(player: PlayerSnapshot) = image
        override fun release() = Unit
    }
    private fun bitmap(size: Int = 360) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
}
