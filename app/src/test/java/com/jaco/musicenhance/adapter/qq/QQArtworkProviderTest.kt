package com.jaco.musicenhance.adapter.qq

import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class QQArtworkProviderTest {
    private class UnavailableQQ : ClassLoader() {
        var queries = 0
        override fun loadClass(name: String): Class<*> {
            queries++
            throw ClassNotFoundException(name)
        }
    }

    @Test fun reopeningTheSameSongDoesNotReviveAReleasedRequest() {
        val host = UnavailableQQ()
        val queue = Handler(Looper.getMainLooper())
        val provider = QQArtworkProvider(host, queue, queue)
        val song = PlayerSnapshot.Empty.copy(title = "Song", durationMs = 60_000)
        assertNull(provider.snapshot(song))
        provider.release()
        assertNull(provider.snapshot(song))
        shadowOf(queue.looper).idle()
        assertEquals("Released request must not query even when the metadata key is reused", 0, host.queries)

        assertNull(provider.snapshot(song))
        shadowOf(queue.looper).idle()
        assertEquals("Reattachment must be able to start fresh work", 1, host.queries)
        assertNull(provider.snapshot(song))
        shadowOf(queue.looper).idle()
        assertEquals("An unavailable QQ API must respect the retry interval", 1, host.queries)
        provider.release()
    }

    @Test fun switchingSongsCancelsQueuedWorkWithoutBlockingTheNewSong() {
        val host = UnavailableQQ()
        val queue = Handler(Looper.getMainLooper())
        val provider = QQArtworkProvider(host, queue, queue)
        val first = PlayerSnapshot.Empty.copy(title = "First", durationMs = 60_000)
        val second = first.copy(title = "Second")
        provider.snapshot(first)
        provider.snapshot(second)
        shadowOf(queue.looper).idle()
        assertEquals(0, host.queries)
        provider.snapshot(second)
        shadowOf(queue.looper).idle()
        assertEquals(1, host.queries)
        provider.release()
    }
}
