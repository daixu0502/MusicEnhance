package com.jaco.musicenhance.adapter.qq

import android.graphics.Bitmap
import com.jaco.musicenhance.player.artwork.ArtworkDimensions
import java.io.IOException
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
class QQArtworkFallbackTest {
    private fun image(size: Int) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private fun address(size: QQArtworkFallback.Size) = "https://example.test/${size.pixels}.jpg"

    @Test
    fun fallsThroughMissingAndFailingLargeSizesToA300PixelCover() {
        val tried = mutableListOf<Int>()
        val cover = image(300)
        val result = QQArtworkFallback.load(
            address = { size ->
                tried += size.pixels
                if (size.pixels == 1200) throw IllegalStateException("unsupported size")
                address(size)
            },
            download = { url ->
                when {
                    url.endsWith("1500.jpg") -> throw IOException("not found")
                    url.endsWith("300.jpg") -> cover
                    else -> null
                }
            },
            isCurrent = { true },
        )
        assertSame(cover, result)
        assertEquals(listOf(1500, 1200, 800, 500, 300), tried)
    }

    @Test
    fun accepts150PixelArtWithoutTreatingSmallIconsAsCovers() {
        assertTrue(ArtworkDimensions.isUsable(150, 150))
        assertFalse(ArtworkDimensions.isUsable(64, 64))
        assertFalse(ArtworkDimensions.isUsable(150, 600))
        val cover = image(150)
        val result = QQArtworkFallback.load(::address, { url ->
            if (url.endsWith("/150.jpg")) cover else null
        }, { true })
        assertSame(cover, result)
    }

    @Test
    fun knownLiveRecordingPlaceholderIsSkippedAndSingerArtIsUsed() {
        val fetched = mutableListOf<String>()
        val portrait = image(1500)
        val result = QQArtworkFallback.loadAlbumOrSinger(
            albumAddress = { "https://y.gtimg.cn/music/photo_new/T002R${it.pixels}x${it.pixels}M0000030lak94GN5Ad_0.jpg" },
            singerAddress = ::address,
            download = { url -> fetched += url; portrait },
            isCurrent = { true },
        )
        assertSame(portrait, result)
        assertEquals(listOf("https://example.test/1500.jpg"), fetched)
        assertFalse(QQArtworkFallback.isPlaceholderAddress("https://y.gtimg.cn/music/photo_new/T002R800x800M000002yMaKA38C1nv_2.jpg"))
    }

    @Test
    fun aRealSmallAlbumCoverTakesPriorityOverSingerArt() {
        val album = image(150)
        val result = QQArtworkFallback.loadAlbumOrSinger(
            albumAddress = ::address,
            singerAddress = { throw AssertionError("Singer fallback must not run") },
            download = { url -> if (url.endsWith("/150.jpg")) album else null },
            isCurrent = { true },
        )
        assertSame(album, result)
    }

    @Test
    fun changingSongDuringDownloadDiscardsTheResultAndStopsFallbacks() {
        var current = true
        var calls = 0
        val result = QQArtworkFallback.loadAlbumOrSinger(::address, ::address, {
            calls++
            current = false
            image(1500)
        }, { current })
        assertNull(result)
        assertEquals(1, calls)
    }

    @Test
    fun duplicateUrlsAreFetchedOnceAndExhaustionReturnsNoImage() {
        var calls = 0
        val result = QQArtworkFallback.load({ "https://example.test/missing.jpg" }, {
            calls++
            null
        }, { true })
        assertNull(result)
        assertEquals(1, calls)
    }

    @Test
    fun displaysTheServerOriginalImmediatelyWithoutRequestingSmallerCopies() {
        val larger = image(1000)
        val fetched = mutableListOf<String>()
        val result = QQArtworkFallback.load(::address, { url ->
            fetched += url
            larger
        }, { true })
        assertSame(larger, result)
        assertEquals(listOf("https://example.test/1500.jpg"), fetched)
    }
}
