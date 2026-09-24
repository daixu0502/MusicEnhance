package com.jaco.musicenhance.adapter.kugou

import android.os.SystemClock
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** A provider instance owns retries and results; no global current-song lyric object is overwritten. */
internal class KugouLyricsSource(loader: ClassLoader) {
    private val api by lazy { KugouLyricsApi(loader) }
    private var trackKey = ""
    private var nextLoadAtMs = 0L
    private var cached: LyricsSnapshot? = null
    @Volatile private var released = false

    fun readLyrics(player: PlayerSnapshot): LyricsSnapshot? {
        if (released) return null
        val song = api.currentSong()?.takeIf { it.identity.matches(player) } ?: return null
        if (song.identity.hash.isBlank()) return null
        val key = "${song.identity.key}\u0000${player.metadataKey}"
        if (trackKey != key) { trackKey = key; cached = null; nextLoadAtMs = 0 }
        cached?.let { return it }
        fun active() = !released && api.isCurrent(song) && !Thread.currentThread().isInterrupted
        var lines = api.currentLines(song.identity.hash)
        if (lines.isEmpty() && SystemClock.elapsedRealtime() >= nextLoadAtMs) {
            try { lines = api.load(song, ::active) }
            finally { nextLoadAtMs = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS }
        }
        if (!active() || lines.isEmpty()) return null
        return LyricsSnapshot(key, LyricsStatus.READY, lines).also { cached = it }
    }
    fun release() { released = true }
    private companion object { const val RETRY_INTERVAL_MS = 30_000L }
}
