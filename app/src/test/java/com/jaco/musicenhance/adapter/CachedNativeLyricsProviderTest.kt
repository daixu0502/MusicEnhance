package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class CachedNativeLyricsProviderTest {
    private class Worker : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks += command }
        fun runNext() = tasks.removeFirst().run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow() = tasks.toMutableList().also { stopped = true; tasks.clear() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private val first = PlayerSnapshot.Empty.copy(title = "First", durationMs = 100_000)
    private val second = first.copy(title = "Second")
    private fun ready(player: PlayerSnapshot) = LyricsSnapshot(
        player.metadataKey, LyricsStatus.READY, listOf(LyricLine(1_000, player.title)),
    )

    @Test fun oldCompletionCannotReplaceNewSong() {
        val worker = Worker()
        lateinit var provider: CachedNativeLyricsProvider
        provider = CachedNativeLyricsProvider({ player ->
            if (player == first) provider.snapshot(second)
            ready(player)
        }, { worker }, { 0 })
        provider.snapshot(first)
        worker.runNext()
        assertEquals(LyricsStatus.LOADING, provider.snapshot(second).status)
        worker.runNext()
        assertEquals("Second", provider.snapshot(second).lines.single().text)
        provider.release()
    }

    @Test fun releaseDiscardsQueuedReadAndReattachmentCreatesNewWorker() {
        val workers = mutableListOf<Worker>()
        var calls = 0
        val provider = CachedNativeLyricsProvider({ calls++; ready(it) }, { Worker().also(workers::add) }, { 0 })
        provider.snapshot(first)
        provider.release()
        workers.single().runNext()
        assertEquals(0, calls)
        provider.snapshot(second)
        workers.last().runNext()
        assertEquals(2, workers.size)
        assertEquals("Second", provider.snapshot(second).lines.single().text)
        provider.release()
    }

    @Test fun missingLyricsClearPreviousTrackAndReadsAreThrottled() {
        val worker = Worker()
        var timeMs = 0L
        val provider = CachedNativeLyricsProvider({ if (it == first) ready(it) else null }, { worker }, { timeMs })
        provider.snapshot(first)
        worker.runNext()
        repeat(60) { assertEquals(LyricsStatus.READY, provider.snapshot(first).status) }
        assertTrue(worker.tasks.isEmpty())
        assertTrue(provider.snapshot(second).lines.isEmpty())
        worker.runNext()
        assertEquals(LyricsStatus.UNAVAILABLE, provider.snapshot(second).status)
        timeMs = 1_000
        provider.snapshot(second)
        assertEquals(1, worker.tasks.size)
        provider.release()
    }

    @Test fun runningReadCannotPublishAfterRelease() {
        val workers = mutableListOf<Worker>()
        var firstRead = true
        lateinit var provider: CachedNativeLyricsProvider
        provider = CachedNativeLyricsProvider({
            if (firstRead) { firstRead = false; provider.release() }
            ready(it)
        }, { Worker().also(workers::add) }, { 0 })
        provider.snapshot(first)
        workers.single().runNext()
        // Release also clears the metadata key; the late result must not survive reattachment.
        assertEquals(LyricsStatus.LOADING, provider.snapshot(second).status)
        workers.last().runNext()
        assertEquals("Second", provider.snapshot(second).lines.single().text)
        provider.release()
    }
}
