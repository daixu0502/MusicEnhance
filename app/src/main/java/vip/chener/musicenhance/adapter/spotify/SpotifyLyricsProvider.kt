package vip.chener.musicenhance.adapter.spotify

import android.os.SystemClock
import vip.chener.musicenhance.hook.moduleInfo
import vip.chener.musicenhance.player.lyrics.LyricsProvider
import vip.chener.musicenhance.player.model.LyricLine
import vip.chener.musicenhance.player.model.LyricsSnapshot
import vip.chener.musicenhance.player.model.LyricsStatus
import vip.chener.musicenhance.player.model.PlayerSnapshot
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
        if (!SpotifyPlayerHooks.hasLyricsService() || song == null) {
            retryAtMs = SystemClock.elapsedRealtime() + 1_000L
            return LyricsSnapshot(uri.ifEmpty { metadataKey }, LyricsStatus.UNAVAILABLE)
        }
        if (worker.isShutdown) worker = newWorker()
        val request = generation
        val requestUri = song.uri
        // Submission holds this lock, so even an immediate result cannot race assignment of task.
        task = worker.submit {
            val result = runCatching {
                load(requireNotNull(SpotifyPlayerHooks.resolveLyricsService()) { "Spotify lyrics provider unavailable" }, requestUri)
            }
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
        val single = SpotifyInterfaceRules.loadLyrics(loader, service.javaClass)
            .invoke(service, trackUri, null)
        val bounded = singleType.getMethod("timeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
            .invoke(single, 12L, TimeUnit.SECONDS)
        val response = singleType.getMethod("blockingGet").invoke(bounded)
        requireNotNull(response) { "Spotify lyric response is empty" }
        val lyrics = SpotifyInterfaceRules.responseLyrics(loader, response.javaClass).get(response)
            ?: return LyricsSnapshot(trackUri, LyricsStatus.UNAVAILABLE)
        val syncType = SpotifyInterfaceRules.lyricSync(loader, lyrics.javaClass).getInt(lyrics)
        val rows = (SpotifyInterfaceRules.lyricRows(loader, lyrics.javaClass).get(lyrics) as? List<*>).orEmpty()
        val lines = rows.take(2_000).mapNotNull { row ->
            if (row == null) null else LyricLine(
                SpotifyInterfaceRules.rowStart(loader, row.javaClass).getLong(row),
                SpotifyInterfaceRules.rowWords(loader, row.javaClass).get(row) as String,
            )
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
