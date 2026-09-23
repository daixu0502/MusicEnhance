package com.jaco.musicenhance.adapter.qq

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Caches QQ lyrics and owns request lifetime. Reflection/decoding live in QQLyricsApi. */
internal class QQLyricsProvider(classLoader: ClassLoader) : LyricsProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val api by lazy { QQLyricsApi(classLoader) }
    private var metadataKey = ""
    private var currentSongId: Long? = null
    private var requestGeneration = 0
    private var songQueryPending = false
    private var nextSongQueryAtMs = 0L
    private var retryAtMs = 0L
    private var loadStartedAtMs = 0L
    private var metadataChangedAtMs = 0L
    private var activeLoader: QQLyricsApi.Loader? = null
    private var cachedLyrics = LyricsSnapshot("", LyricsStatus.LOADING)

    override fun snapshot(player: PlayerSnapshot): LyricsSnapshot {
        val nowMs = SystemClock.elapsedRealtime()
        if (player.metadataKey != metadataKey) {
            resetRequest()
            metadataKey = player.metadataKey
            metadataChangedAtMs = nowMs
            cachedLyrics = LyricsSnapshot(metadataKey, LyricsStatus.LOADING)
        }
        if (activeLoader != null && nowMs - loadStartedAtMs > LOAD_TIMEOUT_MS) {
            invalidateLoader()
            markUnavailable(nowMs)
        }
        if (player.durationMs <= 0) return LyricsSnapshot(metadataKey, LyricsStatus.UNAVAILABLE)
        if (!songQueryPending && nowMs >= nextSongQueryAtMs) queryCurrentSong(player, nowMs)
        return cachedLyrics
    }

    private fun queryCurrentSong(player: PlayerSnapshot, nowMs: Long) {
        nextSongQueryAtMs = nowMs + SONG_QUERY_INTERVAL_MS
        songQueryPending = true
        val queryGeneration = requestGeneration
        val queryMetadataKey = metadataKey
        lyricsWorker.post {
            val songResult = runCatching { api.currentSong(player.title) }
            mainHandler.post {
                songQueryPending = false
                if (queryGeneration == requestGeneration && queryMetadataKey == metadataKey) {
                    songResult.onSuccess(::acceptCurrentSong).onFailure { error ->
                        reportFailure("query", error)
                        // A temporary IPC failure must not discard already loaded lyrics.
                        if (cachedLyrics.status != LyricsStatus.READY) {
                            invalidateLoader()
                            markUnavailable(SystemClock.elapsedRealtime())
                        }
                        nextSongQueryAtMs = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
                    }
                }
            }
        }
    }

    private fun acceptCurrentSong(song: QQLyricsApi.Song?) {
        val nowMs = SystemClock.elapsedRealtime()
        if (song == null) {
            if (cachedLyrics.status == LyricsStatus.LOADING && activeLoader == null &&
                nowMs - metadataChangedAtMs > LOAD_TIMEOUT_MS
            ) markUnavailable(nowMs)
            return
        }
        if (song.id != currentSongId) {
            invalidateLoader()
            currentSongId = song.id
            retryAtMs = 0L
            cachedLyrics = LyricsSnapshot("$metadataKey\u0000${song.id}", LyricsStatus.LOADING)
        }
        if (activeLoader == null && nowMs >= retryAtMs && cachedLyrics.status != LyricsStatus.READY) {
            loadLyrics(song, nowMs)
        }
    }

    private fun loadLyrics(song: QQLyricsApi.Song, nowMs: Long) {
        val loadGeneration = ++requestGeneration
        loadStartedAtMs = nowMs
        cachedLyrics = cachedLyrics.copy(status = LyricsStatus.LOADING, lines = emptyList())
        runCatching {
            activeLoader = api.createLoader { result ->
                mainHandler.post {
                    if (loadGeneration == requestGeneration && song.id == currentSongId) {
                        acceptLyrics(result, song.id)
                    }
                }
            }
            activeLoader?.start(song)
            moduleInfo("QQ lyrics loading: songId=${song.id}")
        }.onFailure { failLoad("load", it) }
    }

    private fun acceptLyrics(result: Any, expectedSongId: Long) {
        runCatching {
            val lines = api.decode(result, expectedSongId) ?: return
            cachedLyrics = cachedLyrics.copy(
                status = if (lines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY,
                lines = lines,
            )
            retryAtMs = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
            moduleInfo("QQ lyrics loaded: songId=$expectedSongId, lines=${lines.size}")
            invalidateLoader()
        }.onFailure { failLoad("decode", it) }
    }

    private fun failLoad(operation: String, error: Throwable) {
        invalidateLoader()
        markUnavailable(SystemClock.elapsedRealtime())
        reportFailure(operation, error)
    }

    private fun markUnavailable(nowMs: Long) {
        cachedLyrics = cachedLyrics.copy(status = LyricsStatus.UNAVAILABLE, lines = emptyList())
        retryAtMs = nowMs + RETRY_INTERVAL_MS
    }

    private fun invalidateLoader() {
        requestGeneration++
        activeLoader?.let { runCatching { it.release() } }
        activeLoader = null
    }

    private fun resetRequest() {
        invalidateLoader()
        currentSongId = null
        nextSongQueryAtMs = 0L
        retryAtMs = 0L
        // A running IPC query keeps this provider busy until its generation-checked completion.
    }

    override fun release() {
        resetRequest()
        metadataKey = ""
        cachedLyrics = LyricsSnapshot("", LyricsStatus.LOADING)
    }

    private fun reportFailure(operation: String, error: Throwable) {
        moduleInfo("QQ lyrics $operation failed: ${(error.cause ?: error).javaClass.simpleName}")
    }

    private companion object {
        const val SONG_QUERY_INTERVAL_MS = 1_000L
        const val LOAD_TIMEOUT_MS = 15_000L
        const val RETRY_INTERVAL_MS = 30_000L
        val lyricsWorker = Handler(HandlerThread("MusicEnhance-lyrics").apply { start() }.looper)
    }
}
