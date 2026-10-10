package com.jaco.musicenhance.adapter.spotify

import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Uses the host's authenticated lyric service; account/region failures remain unavailable. */
internal class SpotifyLyricsProvider(private val loader: ClassLoader) : LyricsProvider {
    private var worker = newWorker()
    private var task: Future<*>? = null
    private var generation = 0L
    private var uri = ""
    private var metadataKey = ""
    private var retryAtMs = 0L
    private var cached = LyricsSnapshot("", LyricsStatus.LOADING)
    private val cache = object : LinkedHashMap<String, LyricsSnapshot>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsSnapshot>?) = size > 4
    }

    @Synchronized override fun snapshot(player: PlayerSnapshot): LyricsSnapshot {
        val song = runCatching { SpotifyApi.current(player)?.song }.getOrNull()
        val nextUri = song?.uri.orEmpty()
        if (metadataKey != player.metadataKey || uri != nextUri) {
            generation++
            task?.cancel(true)
            task = null
            uri = nextUri
            metadataKey = player.metadataKey
            retryAtMs = 0L
            cached = cache[uri] ?: LyricsSnapshot(uri.ifEmpty { metadataKey }, LyricsStatus.LOADING)
        }
        if (cached.status == LyricsStatus.READY || task != null || SystemClock.elapsedRealtime() < retryAtMs) return cached
        val service = SpotifyPlayerHooks.lyricsService.get()
        if (service == null || song == null) {
            retryAtMs = SystemClock.elapsedRealtime() + 1_000L
            return LyricsSnapshot(uri.ifEmpty { metadataKey }, LyricsStatus.UNAVAILABLE)
        }
        if (worker.isShutdown) worker = newWorker()
        val request = generation
        val requestUri = song.uri
        // Submission holds this lock, so even an immediate result cannot race assignment of task.
        task = worker.submit {
            val result = runCatching { load(service, requestUri) }
            synchronized(this) {
                if (request == generation) {
                    task = null
                    cached = result.getOrNull() ?: LyricsSnapshot(requestUri, LyricsStatus.UNAVAILABLE)
                    if (cached.status == LyricsStatus.READY) cache[requestUri] = cached
                    retryAtMs = SystemClock.elapsedRealtime() + 30_000L
                    moduleInfo("Spotify lyrics: status=${cached.status}, lines=${cached.lines.size}" +
                        (result.exceptionOrNull()?.let { "; error=${it.cause ?: it}" } ?: ""))
                }
            }
        }
        return cached
    }

    private fun load(service: Any, trackUri: String): LyricsSnapshot {
        val singleType = loader.loadClass("io.reactivex.rxjava3.core.Single")
        val single = service.javaClass.getMethod("b", String::class.java, String::class.java)
            .invoke(service, trackUri, null)
        val bounded = singleType.getMethod("timeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
            .invoke(single, 12L, TimeUnit.SECONDS)
        val response = singleType.getMethod("blockingGet").invoke(bounded)
        val lyrics = loader.loadClass("p.j6g").getField("a").get(response)
        val lyricsType = loader.loadClass("p.x9d0")
        val syncType = lyricsType.getField("b").getInt(lyrics)
        val lineType = loader.loadClass("p.r9d0")
        val start = lineType.getField("a")
        val words = lineType.getField("b")
        val lines = (lyricsType.getField("a").get(lyrics) as? List<*>).orEmpty().mapNotNull {
            if (it == null) null else LyricLine(start.getLong(it), words.get(it) as String)
        }
        return parse(trackUri, syncType, lines)
    }

    @Synchronized override fun release() {
        generation++
        task?.cancel(true)
        task = null
        worker.shutdownNow()
        cache.clear()
        uri = ""
        metadataKey = ""
        cached = LyricsSnapshot("", LyricsStatus.LOADING)
    }

    companion object {
        private fun newWorker() = ScheduledThreadPoolExecutor(1) {
            Thread(it, "MusicEnhance-Spotify-lyrics").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        fun parse(uri: String, syncType: Int, lines: List<LyricLine>): LyricsSnapshot {
            // Native converter maps UNSYNCED -> 1, LINE_SYNCED -> 2, SYLLABLE_SYNCED -> 3.
            val timed = if (syncType in 2..3) lines.filter { it.startMs >= 0 && it.text.isNotBlank() }
                .sortedBy { it.startMs }.distinct() else emptyList()
            return LyricsSnapshot(uri, if (timed.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, timed)
        }
    }
}
