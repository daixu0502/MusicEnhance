package com.jaco.musicenhance.adapter

import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class PolledFavoriteControlTest {
    private val song = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist")
    private var nowMs = 0L
    private val worker = QueuedWorker()
    private val source = FakeSource()
    private val control = PolledFavoriteControl({ source }, worker, Handler(Looper.getMainLooper()), { nowMs })

    @Test fun missingNativeButtonDoesNotPreventDatabaseStateOrConfirmedFavoriteChanges() {
        assertNull(control.read(song).favorite)
        assertFalse(control.toggle(song))
        flush()
        assertEquals(false, control.read(song).favorite)
        assertTrue(control.toggle(song))
        repeat(5) { assertFalse(control.toggle(song)) }
        flush()
        assertEquals(listOf(false), source.dispatched)
        assertEquals(false, control.read(song).favorite) // Dispatch is not confirmation.
        flush()
        assertFalse(control.toggle(song))
        source.favorite = true
        nowMs += 250
        control.read(song)
        flush()
        assertEquals(true, control.read(song).favorite)
        assertTrue(control.toggle(song))
        flush()
        assertEquals(listOf(false, true), source.dispatched)
        control.release()
    }

    @Test fun actionRereadsNativeStateAndDoesNotTrustAnOutdatedButton() {
        control.read(song); flush()
        source.favorite = true // Changed in the host since the last display update.
        assertTrue(control.toggle(song)); flush()
        assertEquals(listOf(true), source.dispatched)
        control.release()
    }

    @Test fun rapidTrackChangeDropsOldQueriesAndPendingClicks() {
        control.read(song)
        val next = song.copy(title = "Next")
        assertNull(control.read(next).favorite)
        flush()
        assertNull(control.read(next).favorite)
        flush()
        assertEquals(false, control.read(next).favorite)
        assertTrue(control.toggle(next))
        control.read(song)
        flush()
        assertTrue(source.dispatched.isEmpty())
        control.release()
    }

    @Test fun sameMetadataWithDifferentNativeIdentityCannotReceiveOldSongClick() {
        control.read(song); flush()
        source.trackKey = "different-recording"
        assertTrue(control.toggle(song)); flush()
        assertTrue(source.dispatched.isEmpty())
        control.release()
    }

    @Test fun releaseCancelsBothQueuedWorkerAndMainThreadActions() {
        control.read(song); flush()
        assertTrue(control.toggle(song))
        worker.runNext() // Main-thread native invocation is queued but has not run.
        control.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(source.dispatched.isEmpty())
        assertTrue(worker.isShutdown)
        assertNull(control.read(song).favorite)
        assertFalse(control.toggle(song))
        control.release()
    }

    @Test fun unsupportedOrFailedQueriesStayUnknownAndCanRetry() {
        source.available = false
        control.read(song); flush()
        assertNull(control.read(song).favorite)
        assertFalse(control.toggle(song))
        source.available = true
        source.failure = true
        nowMs += 500
        control.read(song); flush()
        assertNull(control.read(song).favorite)
        source.failure = false
        nowMs += 500
        control.read(song); flush()
        assertEquals(false, control.read(song).favorite)
        control.release()
    }

    @Test fun unconfirmedMutationTimesOutWithoutFabricatingSuccess() {
        control.read(song); flush()
        assertTrue(control.toggle(song)); flush()
        nowMs += 4_001
        assertEquals(false, control.read(song).favorite)
        assertTrue(control.toggle(song))
        control.release()
    }

    private fun flush() {
        while (worker.tasks.isNotEmpty()) worker.runNext()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private class FakeSource : PolledFavoriteControl.Source {
        var trackKey = "original"
        var favorite = false
        var available = true
        var failure = false
        val dispatched = mutableListOf<Boolean>()
        override fun read(player: PlayerSnapshot): PolledFavoriteControl.State? {
            check(!failure) { "Host unavailable" }
            return if (available) PolledFavoriteControl.State(trackKey, favorite) else null
        }
        override fun toggle(state: PolledFavoriteControl.State): Boolean {
            dispatched += state.favorite
            return true
        }
    }
    private class QueuedWorker : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks += command }
        fun runNext() = tasks.removeFirst().run()
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
