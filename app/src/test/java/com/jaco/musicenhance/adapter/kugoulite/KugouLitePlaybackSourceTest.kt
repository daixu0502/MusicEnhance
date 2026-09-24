package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class KugouLitePlaybackSourceTest {
    private val worker = QueuedWorker()
    private val player = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist", durationMs = 180_000)
    private val source = KugouLitePlaybackSource(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.kugou.framework.service.util.PlaybackServiceUtil" -> Utility::class.java
            "com.kugou.framework.service.IKugouPlaybackService" -> Service::class.java
            "com.kugou.framework.service.entity.KGMusicWrapper" -> Song::class.java
            else -> super.loadClass(name)
        }
    }, worker)

    @Before fun reset() {
        Utility.current = Song()
        Utility.service = Service()
    }

    @Test fun playingSeekDoesNotSendPlayEvenWhenMediaSnapshotSaysPaused() {
        val service = requireNotNull(Utility.service).apply { playing = true }
        source.seekAndPlay(42_000, player)
        assertTrue(service.seeks.isEmpty()) // Host IPC stays off the calling/UI thread.
        worker.runAll()
        assertEquals(listOf(42_000), service.seeks)
        assertTrue(service.playing)
        assertEquals(0, service.playCalls)
    }

    @Test fun pausedSeekExplicitlyStartsEvenWhenMediaSnapshotSaysPlaying() {
        val service = requireNotNull(Utility.service)
        source.seekAndPlay(24_000, player.copy(isPlaying = true))
        worker.runAll()
        assertEquals(listOf(24_000), service.seeks)
        assertTrue(service.playing)
        assertEquals(1, service.playCalls)
    }

    @Test fun seekThatResumesPlaybackDoesNotStartAgain() {
        val service = requireNotNull(Utility.service).apply { afterSeek = { playing = true } }
        source.seekAndPlay(30_000, player)
        worker.runAll()
        assertTrue(service.playing)
        assertEquals(0, service.playCalls)
    }

    @Test fun failedSeekDoesNotStartPlayback() {
        val service = requireNotNull(Utility.service).apply { seekAccepted = false }
        source.seekAndPlay(30_000, player)
        worker.runAll()
        assertFalse(service.playing)
        assertEquals(0, service.playCalls)
    }

    @Test fun queuedClickIsDiscardedAfterSongChanges() {
        val service = requireNotNull(Utility.service)
        source.seekAndPlay(30_000, player)
        Utility.current = Song(title = "Next song")
        worker.runAll()
        assertTrue(service.seeks.isEmpty())
        assertEquals(0, service.playCalls)
    }

    @Test fun songChangeDuringSeekCannotStartTheNewSong() {
        val service = requireNotNull(Utility.service).apply {
            // Same title/artist, different native identity.
            afterSeek = { Utility.current = Song(hash = "different-recording") }
        }
        source.seekAndPlay(30_000, player)
        worker.runAll()
        assertEquals(listOf(30_000), service.seeks)
        assertEquals(0, service.playCalls)
    }

    @Test fun rapidClicksApplyOnlyLatestTargetAndClampPosition() {
        val service = requireNotNull(Utility.service)
        source.seekAndPlay(12_000, player)
        source.seekAndPlay(24_000, player)
        source.seekAndPlay(Long.MAX_VALUE, player)
        worker.runAll()
        assertEquals(listOf(180_000), service.seeks)
        assertEquals(1, service.playCalls)
        source.seekAndPlay(-1, player)
        worker.runAll()
        assertEquals(listOf(180_000, 0), service.seeks)
        assertEquals(1, service.playCalls)
    }

    @Test fun disconnectedServiceDoesNotFallBackToToggle() {
        Utility.service = null
        source.seekAndPlay(24_000, player)
        worker.runAll()
    }

    @Test fun releaseCancelsQueuedClickAndRejectsNewClicks() {
        val service = requireNotNull(Utility.service)
        source.seekAndPlay(24_000, player)
        source.release()
        source.seekAndPlay(30_000, player)
        worker.runAll()
        assertTrue(worker.isShutdown)
        assertTrue(service.seeks.isEmpty())
    }

    object Utility {
        var current: Song? = null
        var service: Service? = null
        @JvmStatic fun P0() = service
        @JvmStatic fun s0() = current
    }

    class Song(private val title: String = "Song", private val hash: String = "hash") {
        fun getHashValue() = hash
        fun getMixId() = 1L
        fun getTrackName() = title
        fun getArtistName() = "Artist"
    }

    class Service {
        var playing = false
        var playCalls = 0
        var seekAccepted = true
        var afterSeek: () -> Unit = {}
        val seeks = mutableListOf<Int>()
        fun Pj(positionMs: Int, source: Int): Boolean {
            assertEquals(27, source)
            seeks += positionMs
            afterSeek()
            return seekAccepted
        }
        fun isPlaying() = playing
        fun N9(source: Int) { assertEquals(7, source); playing = true; playCalls++ }
        fun play(): Unit = throw AbstractMethodError("5.2.9's Binder proxy has no play() implementation")
    }

    private class QueuedWorker : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
