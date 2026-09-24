package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class QQPlaybackSourceTest {
    private val worker = QueuedWorker()
    private val player = PlayerSnapshot.Empty.copy(title = "Song", durationMs = 180_000)
    private val source = QQPlaybackSource(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.tencent.qqmusic.common.ipc.MusicProcess" -> Process::class.java
            "com.tencent.qqmusic.common.ipc.IPlayProcessMethods" -> Environment::class.java
            "com.tencent.qqmusicplayerprocess.songinfo.SongInfo" -> Song::class.java
            "com.tencent.qqmusic.common.player.g" -> Controls::class.java
            "com.tencent.qqmusicplayerprocess.servicenew.FromInfo" -> FromInfo::class.java
            else -> super.loadClass(name)
        }
    }, worker)

    @Before fun reset() {
        Process.environment = Environment()
        Controls.instance = Controls()
    }

    @Test fun pausedSongSeeksThenResumesDespiteStalePlayingSnapshot() {
        source.seekAndPlay(42_000, player.copy(isPlaying = true))
        assertTrue(Controls.instance.calls.isEmpty())
        worker.runAll()
        assertEquals(listOf("seek:42000", "resume"), Controls.instance.calls)
        assertTrue(Controls.instance.playing)
    }

    @Test fun alreadyPlayingSongUsesIdempotentResumeInsteadOfToggle() {
        Controls.instance.playing = true
        source.seekAndPlay(50_000, player)
        worker.runAll()
        assertEquals(listOf("seek:50000", "resume"), Controls.instance.calls)
        assertTrue(Controls.instance.playing)
    }

    @Test fun pendingPauseAfterSeekIsExplicitlyResumed() {
        Controls.instance.playing = true
        Controls.instance.afterSeek = { Controls.instance.playing = false }
        source.seekAndPlay(60_000, player.copy(isPlaying = true))
        worker.runAll()
        assertTrue(Controls.instance.playing)
    }

    @Test fun rapidClicksDiscardOlderTargetsAndClampToDuration() {
        source.seekAndPlay(20_000, player)
        source.seekAndPlay(Long.MAX_VALUE, player)
        worker.runAll()
        assertEquals(listOf("seek:180000", "resume"), Controls.instance.calls)
    }

    @Test fun queuedOldLyricsDoNotSeekTheNextSong() {
        source.seekAndPlay(20_000, player)
        Process.environment.song = Song(2, "Next song")
        worker.runAll()
        assertTrue(Controls.instance.calls.isEmpty())
    }

    @Test fun songChangeDuringSeekDoesNotResumeAnotherRecordingWithSameTitle() {
        Controls.instance.afterSeek = { Process.environment.song = Song(2, "Song") }
        source.seekAndPlay(20_000, player)
        worker.runAll()
        assertEquals(listOf("seek:20000"), Controls.instance.calls)
        assertFalse(Controls.instance.playing)
    }

    @Test fun failedSeekDoesNotResume() {
        Controls.instance.seekResult = -1
        source.seekAndPlay(20_000, player)
        worker.runAll()
        assertEquals(listOf("seek:20000"), Controls.instance.calls)
    }

    @Test fun unloadedPlayerStartsBeforeDelayedSeek() {
        Process.environment.state = 0
        source.seekAndPlay(42_000, player)
        worker.runAll()
        assertEquals(listOf("start"), Controls.instance.calls)
        worker.runDelayed()
        assertEquals(listOf("start", "seek:42000", "resume"), Controls.instance.calls)
        assertTrue(Controls.instance.playing)
    }

    @Test fun stoppedPlayerDoesNotApplyDelayedSeekAfterSongChanges() {
        Process.environment.state = 601
        source.seekAndPlay(42_000, player)
        worker.runAll()
        Process.environment.song = Song(2, "Song")
        worker.runDelayed()
        assertEquals(listOf("start"), Controls.instance.calls)
    }

    @Test fun releaseCancelsDelayedPreparationRequest() {
        Process.environment.state = 0
        source.seekAndPlay(42_000, player)
        worker.runAll()
        source.release()
        worker.runDelayed()
        assertEquals(listOf("start"), Controls.instance.calls)
    }

    @Test fun unavailableSongDoesNotIssuePlaybackCommands() {
        Process.environment.song = null
        source.seekAndPlay(20_000, player)
        worker.runAll()
        assertTrue(Controls.instance.calls.isEmpty())
    }

    @Test fun releaseCancelsPendingAndRejectsFutureRequests() {
        source.seekAndPlay(20_000, player)
        source.release()
        source.seekAndPlay(40_000, player)
        worker.runAll()
        assertTrue(Controls.instance.calls.isEmpty())
        assertTrue(worker.isShutdown)
    }

    @Test fun releaseDuringSeekCannotResumePlayback() {
        Controls.instance.afterSeek = source::release
        source.seekAndPlay(20_000, player)
        worker.runAll()
        assertEquals(listOf("seek:20000"), Controls.instance.calls)
    }

    // Host API names are invoked reflectively by the adapter under test.
    @Suppress("unused")
    object Process {
        var environment = Environment()
        @JvmStatic fun playEnv() = environment
    }
    @Suppress("unused")
    class Environment {
        var song: Song? = Song(1, "Song")
        var state = 5
        fun getPlaySong() = song
        fun getPlayState() = state
    }
    @Suppress("unused")
    class Song(private val id: Long, private val title: String) {
        fun C3() = id
        fun j3() = title
    }
    @Suppress("unused")
    class FromInfo(@JvmField val value: Int) {
        companion object { @JvmField val FROM_LYRIC = FromInfo(16) }
    }
    @Suppress("unused")
    class Controls {
        var playing = false
        var seekResult: Long? = null
        var afterSeek: () -> Unit = {}
        val calls = mutableListOf<String>()
        fun o(from: Int) { assertEquals(16, from); calls += "start"; playing = true }
        fun s(positionMs: Long, from: Int): Long {
            assertEquals(16, from)
            calls += "seek:$positionMs"
            afterSeek()
            return seekResult ?: positionMs
        }
        fun r(from: Int): Int {
            assertEquals(16, from)
            calls += "resume"
            val result = if (playing) 21 else 0
            playing = true
            return result
        }
        companion object { @JvmField var instance = Controls() }
    }

    private class QueuedWorker : AbstractExecutorService(), ScheduledExecutorService {
        private val tasks = ArrayDeque<Runnable>()
        private val delayed = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        fun runDelayed() { while (delayed.isNotEmpty()) delayed.removeFirst().run() }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            schedule(Callable { command.run() }, delay, unit)
        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            check(!stopped)
            assertEquals(500L, unit.toMillis(delay))
            return object : FutureTask<V>(callable), ScheduledFuture<V> {
                override fun getDelay(unit: TimeUnit) = unit.convert(delay, TimeUnit.MILLISECONDS)
                override fun compareTo(other: Delayed) = 0
            }.also { delayed += it }
        }
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            error("No periodic playback commands expected")
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            error("No periodic playback commands expected")
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return (tasks + delayed).toMutableList().also { tasks.clear(); delayed.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
