package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import com.jaco.musicenhance.player.model.PlayerSnapshot
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
class PlayerArtworkStateTest {
    private val song = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist", album = "Album")
    private fun bitmap(size: Int) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    @Test
    fun absentAndLowerResolutionUpdatesDoNotEraseOrDowngradeTheCover() {
        val state = PlayerArtworkState()
        state.updateTrack(song)
        val cover = bitmap(80)
        assertSame(cover, state.select(null, cover, null))
        assertSame(cover, state.select(null, null, null))
        assertSame(cover, state.select(null, bitmap(20), null))
        val larger = bitmap(120)
        assertSame(larger, state.select(null, larger, null))
    }

    @Test
    fun verifiedCoverSurvivesNativePlaceholdersAndMissingProviderResults() {
        val state = PlayerArtworkState()
        state.updateTrack(song)
        val cover = bitmap(80)
        val unlabelledNative = bitmap(120)
        state.select(null, null, unlabelledNative)
        assertSame(cover, state.select(cover, null, unlabelledNative))
        assertSame(cover, state.select(null, null, unlabelledNative))
        assertSame(cover, state.select(null, null, null))
    }

    @Test
    fun partialAlbumMetadataKeepsArtworkButARealTrackChangeClearsIt() {
        val state = PlayerArtworkState()
        val cover = bitmap(80)
        state.updateTrack(song.copy(album = ""))
        state.select(cover, null, null)
        assertFalse(state.updateTrack(song))
        assertFalse(state.updateTrack(song.copy(album = "")))
        assertSame(cover, state.select(null, null, null))
        assertTrue(state.updateTrack(song.copy(album = "Another recording")))
        assertNull(state.select(null, null, null))
        state.select(cover, null, null)
        assertTrue(state.updateTrack(song.copy(title = "Next song")))
        assertNull(state.select(null, null, null))
    }

    @Test
    fun recycledImagesAreNeverReturnedOrReadForTheirSize() {
        val state = PlayerArtworkState()
        state.updateTrack(song)
        val cover = bitmap(80)
        state.select(cover, null, null)
        cover.recycle()
        assertNull(state.select(cover, cover, cover))
        val replacement = bitmap(40)
        assertSame(replacement, state.select(null, replacement, cover))
    }
}
