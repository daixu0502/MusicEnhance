package com.jaco.musicenhance.adapter.salt

import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.lyrics.LrcLyricsParser

/** Copies the host's parsed local lyrics. No download, search, or dependency on its lyrics screen. */
internal class SaltLyricsSource(private val playback: SaltPlaybackSource) {
    private var previousDocument: Any? = null
    private var previousKey = ""
    private var cached: LyricsSnapshot? = null

    fun readLyrics(player: PlayerSnapshot): LyricsSnapshot? {
        val song = playback.song()?.takeIf { it.matches(player) } ?: return null
        val nativeDocument = runCatching { playback.lyricsDocument(song) }.getOrNull()
        val published = SaltNativeDataHooks.lyrics?.takeIf { it.ownerId == song.id }
        val document = nativeDocument ?: published ?: return null
        val offsetMs = playback.cueOffsetMs()
        val key = "${song.id}\u0000$offsetMs\u0000${player.metadataKey}"
        if (document === previousDocument && key == previousKey) return cached
        val lines = if (nativeDocument == null) parseSourceText(requireNotNull(published).sourceText).map {
            it.copy(startMs = it.startMs - offsetMs)
        } else (playback.data.lyricLines.get(nativeDocument) as? List<*>)?.mapNotNull { line ->
            line ?: return@mapNotNull null
            val text = playback.data.lineText.get(line) as? String ?: return@mapNotNull null
            LyricLine(playback.data.lineStart.getLong(line) - offsetMs, text)
        }.orEmpty()
        if (playback.song()?.id != song.id || (nativeDocument != null && playback.lyricsDocument(song) !== document) ||
            (nativeDocument == null && SaltNativeDataHooks.lyrics !== published)) return null
        val validLines = normalize(lines, player.durationMs)
        return LyricsSnapshot(key, if (validLines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, validLines)
            .also { previousDocument = document; previousKey = key; cached = it }
    }

    companion object {
        /** Salt's original local LRC text, copied at the same callback as its actual song ID. */
        internal fun parseSourceText(text: String): List<LyricLine> =
            LrcLyricsParser.parse(text, clampNegativeTimes = false)

        fun normalize(lines: List<LyricLine>, durationMs: Long): List<LyricLine> = lines
            .filter { it.text.isNotBlank() && it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs to it.text }
    }
}
