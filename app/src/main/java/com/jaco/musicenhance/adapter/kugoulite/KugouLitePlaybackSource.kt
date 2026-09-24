package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 5.2.9's playback IPC. MediaSession.onPlay can send PLAY_PAUSE, so it is not safe after a seek. */
internal class KugouLitePlaybackSource(
    loader: ClassLoader,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-kugoulite-seek").apply { isDaemon = true }
    },
) {
    private val api by lazy { Api(loader) }
    private val generation = AtomicLong()
    @Volatile private var released = false

    @Synchronized
    fun seekAndPlay(positionMs: Long, player: PlayerSnapshot) {
        if (released || player.durationMs <= 0) return
        val request = generation.incrementAndGet()
        val targetMs = positionMs.coerceIn(0, minOf(player.durationMs, Int.MAX_VALUE.toLong())).toInt()
        worker.execute {
            fun isCurrent() = !released && generation.get() == request
            if (!isCurrent()) return@execute
            runCatching {
                val native = api
                val song = native.song()?.takeIf { it.matches(player) } ?: return@runCatching
                val service = native.service() ?: return@runCatching
                fun canApply() = isCurrent() && native.song()?.key == song.key
                if (!canApply() || !native.seek(service, targetMs)) return@runCatching
                // Read the service after seeking, not a delayed media-session snapshot. N9 here
                // explicitly starts playback; unlike onPlay(), it never dispatches a toggle key.
                if (canApply() && !native.isPlaying(service) && canApply()) native.play(service)
            }.onFailure { moduleInfo("Kugou Lite lyric seek failed: ${it.cause ?: it}") }
        }
    }

    @Synchronized
    fun release() {
        released = true
        generation.incrementAndGet()
        worker.shutdownNow()
    }

    private data class Song(val key: Pair<String, Long>, val title: String, val artist: String) {
        fun matches(player: PlayerSnapshot) = title.isNotBlank() && title == player.title.trim() &&
            (artist.isBlank() || player.artist.isBlank() || artist == player.artist.trim())
    }

    private class Api(loader: ClassLoader) {
        private val utility = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
        private val serviceType = loader.loadClass("com.kugou.framework.service.IKugouPlaybackService")
        private val songType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
        private val getService = utility.getMethod("P0")
        private val getSong = utility.getMethod("s0")
        private val getHash = songType.getMethod("getHashValue")
        private val getMixId = songType.getMethod("getMixId")
        private val getTitle = songType.getMethod("getTrackName")
        private val getArtist = songType.getMethod("getArtistName")
        private val seek = serviceType.getMethod("Pj", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val isPlaying = serviceType.getMethod("isPlaying")
        // The AIDL interface declares play(), but 5.2.9's Binder proxy does not implement it.
        // PlaybackServiceUtil.e4 uses N9(source), which reaches the same explicit start operation.
        private val play = serviceType.getMethod("N9", Int::class.javaPrimitiveType)

        fun song(): Song? {
            val song = getSong.invoke(null) ?: return null
            val hash = (getHash.invoke(song) as? String).orEmpty().trim()
            val mixId = (getMixId.invoke(song) as Number).toLong()
            if (hash.isBlank() && mixId <= 0) return null
            return Song(hash to mixId, (getTitle.invoke(song) as? String).orEmpty().trim(),
                (getArtist.invoke(song) as? String).orEmpty().trim())
        }

        fun service(): Any? = getService.invoke(null)
        fun seek(service: Any, positionMs: Int) = seek.invoke(service, positionMs, SEEK_SOURCE) == true
        fun isPlaying(service: Any) = isPlaying.invoke(service) == true
        fun play(service: Any) { play.invoke(service, PLAY_SOURCE) }

        private companion object {
            // Same seek source used by the host's MediaSession.onSeekTo.
            const val SEEK_SOURCE = 27
            const val PLAY_SOURCE = 7
        }
    }
}
