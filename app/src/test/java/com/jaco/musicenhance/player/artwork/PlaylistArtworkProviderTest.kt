package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlaylistArtworkProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Source : PlaylistArtworkSource<Int> {
        private val namespace = UUID.randomUUID().toString()
        var current = 4
        var nativeReady = true
        var queueAvailable = true
        var fixedTitle = false
        var failHighResolution = false
        var onAddress: (() -> Unit)? = null
        var reads = 0
        var queueReads = 0
        val requested = mutableListOf<Int>()
        fun player() = PlayerSnapshot.Empty.copy(title = if (fixedTitle) "Same title" else "Song $current", durationMs = 60_000)
        override fun currentSong(player: PlayerSnapshot): Int? {
            reads++
            return current.takeIf { nativeReady && player.title == player().title }
        }
        override fun isCurrentSong(song: Int) = song == current
        override fun cacheKey(song: Int) = "$namespace:$song"
        fun address(song: Int) = "https://example.test/$namespace/$song.png"
        override fun addresses(song: Int, isCurrent: () -> Boolean) = sequence {
            requested += song
            onAddress?.invoke()
            if (failHighResolution) yield("file:///unavailable-high-resolution.png")
            yield(address(song))
        }
        override fun neighbours(song: Int): List<Int> {
            queueReads++
            check(queueAvailable)
            return PlaylistArtworkWindow.indices(10, song, wrap = false)
        }
    }

    private fun provider(source: Source): PlaylistArtworkProvider<Int> {
        val cache = ArtworkDiskCache(temporary.newFolder())
        for (song in 0..9) {
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(song, 0, 0)) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            cache.write(source.address(song), bytes)
        }
        val handler = Handler(Looper.getMainLooper())
        return PlaylistArtworkProvider("Test", { source }, cache, handler, handler, handler)
    }

    @Test fun prefetchesSixNeighboursAndReusesOverlappingDiskCacheAfterSkip() {
        val source = Source()
        val provider = provider(source)
        assertFalse(provider.holdPreviousArtworkWhileLoading)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.player())
        looper.idle()
        assertNotNull(provider.snapshot(source.player()))
        assertEquals(listOf(4, 5, 3, 6, 2, 7, 1), source.requested)
        assertEquals(1, source.queueReads)

        source.current++
        source.requested.clear()
        provider.snapshot(source.player())
        looper.idle()
        assertNotNull(provider.snapshot(source.player()))
        assertEquals(listOf(8), source.requested)
        assertEquals(2, source.queueReads)
        provider.release()
    }

    @Test fun releaseAndRapidSwitchRejectOldRequestsEvenWithSameMetadata() {
        val source = Source()
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.player())
        provider.release()
        provider.snapshot(source.player())
        looper.idle()
        assertEquals(0, source.reads)
        looper.idleFor(Duration.ofMillis(500))
        provider.snapshot(source.player())
        source.current++
        provider.snapshot(source.player())
        looper.idle()
        assertEquals(0, source.reads)
        looper.idleFor(Duration.ofMillis(500))
        provider.snapshot(source.player())
        looper.idle()
        assertNotNull(provider.snapshot(source.player()))
        assertEquals(5, source.requested.first())
        provider.release()
    }

    @Test fun delayedNativeMetadataRetriesWithoutPublishingAnotherSongsCover() {
        val source = Source().apply { nativeReady = false }
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.player())
        looper.idle()
        assertNull(provider.snapshot(source.player()))
        assertEquals(emptyList<Int>(), source.requested)
        source.nativeReady = true
        looper.idleFor(Duration.ofMillis(500))
        provider.snapshot(source.player())
        looper.idle()
        assertNotNull(provider.snapshot(source.player()))
        provider.release()
    }

    @Test fun identicalMetadataStillRechecksNativeSongIdentity() {
        val source = Source().apply { fixedTitle = true }
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.player())
        looper.idle()
        val first = provider.snapshot(source.player())!!
        source.current++
        looper.idleFor(Duration.ofMillis(500))
        provider.snapshot(source.player())
        looper.idle()
        val second = provider.snapshot(source.player())!!
        assertEquals(4, Color.red(first.getPixel(0, 0)))
        assertEquals(5, Color.red(second.getPixel(0, 0)))
        provider.release()
    }

    @Test fun songChangedDuringImageLoadingCannotPublishOldResultOrStartPrefetch() {
        val source = Source()
        source.onAddress = { source.current++; source.onAddress = null }
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        val oldPlayer = source.player()
        provider.snapshot(oldPlayer)
        looper.idle()
        assertNull(provider.snapshot(oldPlayer))
        assertEquals(0, source.queueReads)
        provider.snapshot(source.player())
        looper.idle()
        assertEquals(5, Color.red(provider.snapshot(source.player())!!.getPixel(0, 0)))
        provider.release()
    }

    @Test fun missingQueueAndFailedLargeImagePreserveValidSmallImage() {
        val source = Source().apply { queueAvailable = false; failHighResolution = true }
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.player())
        looper.idle()
        val cover = provider.snapshot(source.player())
        assertNotNull(cover)
        repeat(4) {
            looper.idleFor(Duration.ofMillis(500))
            provider.snapshot(source.player())
            looper.idle()
        }
        assertSame(cover, provider.snapshot(source.player()))
        assertEquals(listOf(4), source.requested)
        assertEquals(1, source.queueReads)
        provider.release()
    }

    @Test fun releaseCancelsPrefetchButReopeningReschedulesIt() {
        val source = Source()
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.player())
        looper.runOneTask()
        looper.runOneTask()
        provider.release()
        looper.idle()
        assertEquals(0, source.queueReads)
        provider.snapshot(source.player())
        looper.idle()
        assertNotNull(provider.snapshot(source.player()))
        assertEquals(1, source.queueReads)
        provider.release()
    }
}
