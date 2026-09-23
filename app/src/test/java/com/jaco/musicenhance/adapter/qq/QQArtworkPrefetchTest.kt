package com.jaco.musicenhance.adapter.qq

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.adapter.qq.QQArtworkSource.Song
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.PlaylistArtworkWindow
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class QQArtworkPrefetchTest {
    @get:Rule val temporary = TemporaryFolder()

    private class QueueSource : QQArtworkSource {
        private val namespace = UUID.randomUUID().toString()
        private val firstId = (UUID.randomUUID().mostSignificantBits ushr 32) * 16
        val songs = (1..10).map { Song(it, firstId + it) }
        var playingIndex = 4
        var queueAvailable = true
        var queueReads = 0
        val coverRequests = mutableListOf<Long>()
        fun address(song: Song) = "file:///$namespace-${song.id}.png"
        fun snapshot() = PlayerSnapshot.Empty.copy(title = "Song ${songs[playingIndex].id}", durationMs = 60_000)
        override fun currentSong(expectedTitle: String) = songs[playingIndex].takeIf { expectedTitle == snapshot().title }
        override fun isCurrentSong(expected: Song) = expected.id == songs[playingIndex].id
        override fun neighbours(expected: Song): List<Song> {
            queueReads++
            if (!queueAvailable) throw UnsupportedOperationException("QQ version without queue API")
            return PlaylistArtworkWindow.indices(songs.size, playingIndex, wrap = false).map(songs::get)
        }
        override fun coverAddress(currentSong: Song, size: QQArtworkFallback.Size): String {
            coverRequests += currentSong.id
            return address(currentSong)
        }
        override fun singerAddress(currentSong: Song, size: QQArtworkFallback.Size): String? = null
    }

    private fun provider(source: QueueSource): QQArtworkProvider {
        val cache = ArtworkDiskCache(temporary.newFolder())
        val image = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.songs.forEach { cache.write(source.address(it), bytes) }
        val handler = Handler(Looper.getMainLooper())
        return QQArtworkProvider(javaClass.classLoader!!, handler, handler, cache, handler) { source }
    }

    @Test fun preloadsSixNeighboursAndSlidesTheWindowAfterSkipping() {
        val source = QueueSource()
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.snapshot())
        looper.idle()
        val firstCover = provider.snapshot(source.snapshot())
        assertNotNull(firstCover)
        assertEquals(listOf(4, 5, 3, 6, 2, 7, 1).map { source.songs[it].id }, source.coverRequests)
        assertEquals(1, source.queueReads)
        looper.idle()
        assertSame(firstCover, provider.snapshot(source.snapshot()))
        assertEquals(1, source.queueReads)

        source.playingIndex++
        source.coverRequests.clear()
        provider.snapshot(source.snapshot())
        looper.idle()
        assertNotNull(provider.snapshot(source.snapshot()))
        // The next song and overlapping neighbours reuse their resolved disk images.
        assertEquals(listOf(source.songs[8].id), source.coverRequests)
        assertEquals(2, source.queueReads)
        provider.release()
    }

    @Test fun releaseCancelsQueuedPrefetchAndACachedReopenSchedulesAFreshWindow() {
        val source = QueueSource()
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.snapshot())
        looper.runOneTask() // Current cover loaded.
        looper.runOneTask() // Current cover published; neighbour work queued.
        provider.release()
        looper.idle()
        assertEquals(0, source.queueReads)
        assertNotNull(provider.snapshot(source.snapshot())) // Reopen via metadata memory alias.
        looper.idle()
        assertEquals(1, source.queueReads)
        provider.release()
    }

    @Test fun unavailableQueueApiDoesNotDiscardTheCurrentCoverOrRetryEveryFrame() {
        val source = QueueSource().apply { queueAvailable = false }
        val provider = provider(source)
        val looper = shadowOf(Looper.getMainLooper())
        provider.snapshot(source.snapshot())
        looper.idle()
        repeat(5) {
            assertNotNull(provider.snapshot(source.snapshot()))
            looper.idle()
        }
        assertEquals(1, source.queueReads)
        provider.release()
    }
}
