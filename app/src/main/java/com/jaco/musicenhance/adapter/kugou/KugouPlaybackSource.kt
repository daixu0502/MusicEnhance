package com.jaco.musicenhance.adapter.kugou

import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Explicit service operations avoid MediaSession implementations that toggle on play(). */
internal class KugouPlaybackSource(
    loader: ClassLoader,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-kugou-seek").apply { isDaemon = true }
    },
) {
    private val songs by lazy { KugouSongSource(loader) }
    private val api by lazy { Api(loader) }
    private val generation = AtomicLong()
    @Volatile private var released = false

    @Synchronized fun seekAndPlay(positionMs: Long, player: PlayerSnapshot) {
        if (released || player.durationMs <= 0) return
        val request = generation.incrementAndGet()
        val targetMs = positionMs.coerceIn(0, minOf(player.durationMs, Int.MAX_VALUE.toLong())).toInt()
        worker.execute {
            fun active() = !released && generation.get() == request
            if (!active()) return@execute
            runCatching {
                val song = songs.current()?.takeIf { it.matches(player) } ?: return@runCatching
                val service = api.service.invoke(null) ?: return@runCatching
                fun canApply() = active() && songs.current()?.key == song.key
                if (!canApply() || api.seek.invoke(service, targetMs) != true) return@runCatching
                // A9 -> J(source) explicitly starts playback; it cannot pause an already playing song.
                if (canApply() && api.isPlaying.invoke(service) != true && canApply()) api.play.invoke(service, 2)
            }.onFailure { moduleInfo("Kugou lyric seek failed: ${it.cause ?: it}") }
        }
    }

    @Synchronized fun release() {
        released = true
        generation.incrementAndGet()
        worker.shutdownNow()
    }

    private class Api(loader: ClassLoader) {
        val service = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil").getMethod("E2")
        private val type = loader.loadClass("com.kugou.framework.service.IKugouPlaybackService")
        val seek = type.getMethod("seek", Int::class.javaPrimitiveType)
        val isPlaying = type.getMethod("isPlaying")
        val play = type.getMethod("J", Int::class.javaPrimitiveType)
    }
}
