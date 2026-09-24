package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.player.model.RepeatMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Coalesces rapid clicks and serializes host IPC; no service calls occur on the rendering thread. */
internal class NativeRepeatControl(
    private val createApi: () -> Api,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-native-repeat").apply { isDaemon = true }
    },
    private val nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    interface Api {
        fun read(): RepeatMode
        fun advance()
    }

    private val api by lazy(createApi)
    private var released = false
    private var mode = RepeatMode.UNKNOWN
    private var pending = false
    private var changeRequested = false
    private var nextReadAtMs = Long.MIN_VALUE

    @Synchronized
    fun snapshot(): RepeatMode {
        if (!released && !pending && nowMs() >= nextReadAtMs) submit()
        return mode
    }

    @Synchronized
    fun advance(): Boolean {
        if (released || mode == RepeatMode.UNKNOWN) return false
        changeRequested = true
        if (!pending) submit()
        return true
    }

    private fun submit() {
        pending = true
        worker.execute {
            do {
                val change = synchronized(this) {
                    if (released) return@execute
                    changeRequested.also { changeRequested = false }
                }
                val result = runCatching {
                    if (change) api.advance()
                    api.read()
                }.getOrDefault(RepeatMode.UNKNOWN)
                val again = synchronized(this) {
                    if (released) return@execute
                    mode = result
                    nextReadAtMs = nowMs() + READ_INTERVAL_MS
                    changeRequested.also { pending = it }
                }
            } while (again)
        }
    }

    @Synchronized
    fun release() {
        released = true
        mode = RepeatMode.UNKNOWN
        changeRequested = false
        worker.shutdownNow()
    }

    private companion object {
        const val READ_INTERVAL_MS = 500L
    }
}
