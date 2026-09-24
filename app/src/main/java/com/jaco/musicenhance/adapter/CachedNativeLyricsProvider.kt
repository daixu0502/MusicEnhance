package com.jaco.musicenhance.adapter

import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Polls native caches off the UI thread. A result can only update its own request generation. */
internal class CachedNativeLyricsProvider(
    private val read: (PlayerSnapshot) -> LyricsSnapshot?,
    private val createWorker: () -> ExecutorService = { Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-native-lyrics").apply { isDaemon = true }
    } },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : LyricsProvider {
    private var worker = createWorker()
    private var generation = 0L
    private var metadataKey = ""
    private var nextReadAtMs = 0L
    private var pending = false
    private var failureReported = false
    private var cached = LyricsSnapshot("", LyricsStatus.LOADING)

    @Synchronized override fun snapshot(player: PlayerSnapshot): LyricsSnapshot {
        if (metadataKey != player.metadataKey) {
            generation++
            metadataKey = player.metadataKey
            cached = LyricsSnapshot(metadataKey, LyricsStatus.LOADING)
            nextReadAtMs = 0L
            failureReported = false
        }
        if (!pending && nowMs() >= nextReadAtMs) {
            if (worker.isShutdown) worker = createWorker()
            pending = true
            val request = generation
            worker.execute {
                synchronized(this) {
                    if (request != generation) {
                        pending = false
                        return@execute
                    }
                }
                val result = runCatching { read(player) }
                synchronized(this) {
                    pending = false
                    if (generation == request) {
                        val loaded = result.getOrNull() ?: LyricsSnapshot(metadataKey, LyricsStatus.UNAVAILABLE)
                        if (loaded.status != cached.status || loaded.trackKey != cached.trackKey) {
                            moduleInfo("Native lyrics: status=${loaded.status}, lines=${loaded.lines.size}")
                        }
                        if (result.isFailure && !failureReported) {
                            failureReported = true
                            val error = result.exceptionOrNull()
                            moduleInfo("Native lyrics read failed: ${error?.cause ?: error}")
                        }
                        cached = loaded
                        nextReadAtMs = nowMs() + POLL_INTERVAL_MS
                    }
                }
            }
        }
        return cached
    }

    @Synchronized override fun release() {
        generation++
        metadataKey = ""
        cached = LyricsSnapshot("", LyricsStatus.LOADING)
        nextReadAtMs = 0
        worker.shutdown()
        // A still-running native read retains the busy flag until it returns.
    }

    private companion object { const val POLL_INTERVAL_MS = 750L }
}
