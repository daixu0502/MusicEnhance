package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.player.model.RepeatMode
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class NativeRepeatControlTest {
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

    @Test fun burstClicksCoalesceAndPublishOnlyReadBackState() {
        val worker = QueuedWorker()
        var actual = RepeatMode.LIST_LOOP
        var calls = 0
        val controls = NativeRepeatControl({ object : NativeRepeatControl.Api {
            override fun read() = actual
            override fun advance() { calls++; actual = RepeatMode.SINGLE_LOOP }
        } }, worker, { 0L })
        assertEquals(RepeatMode.UNKNOWN, controls.snapshot())
        assertFalse(controls.advance())
        worker.runNext()
        repeat(8) { assertTrue(controls.advance()) }
        assertEquals(1, worker.tasks.size)
        assertEquals(RepeatMode.LIST_LOOP, controls.snapshot())
        worker.runNext()
        assertEquals(1, calls)
        assertEquals(RepeatMode.SINGLE_LOOP, controls.snapshot())
        controls.release()
    }

    @Test fun releaseCancelsQueuedOperationsAndClearsState() {
        val worker = QueuedWorker()
        var queried = false
        val controls = NativeRepeatControl({ object : NativeRepeatControl.Api {
            override fun read(): RepeatMode { queried = true; return RepeatMode.LIST_LOOP }
            override fun advance() = fail("Cannot change mode after release")
        } }, worker)
        controls.snapshot()
        controls.release()
        assertTrue(worker.tasks.isEmpty())
        assertFalse(queried)
        assertFalse(controls.advance())
        assertEquals(RepeatMode.UNKNOWN, controls.snapshot())
    }

    @Test fun unavailableNativeInterfaceDoesNotInventMode() {
        val worker = QueuedWorker()
        val controls = NativeRepeatControl({ error("Host API changed") }, worker)
        controls.snapshot()
        worker.runNext()
        assertEquals(RepeatMode.UNKNOWN, controls.snapshot())
        assertFalse(controls.advance())
        controls.release()
    }
}
