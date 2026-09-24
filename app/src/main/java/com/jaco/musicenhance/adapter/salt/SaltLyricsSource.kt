package com.jaco.musicenhance.adapter.salt

import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Copies the host's parsed local lyrics. No download, search, or dependency on its lyrics screen. */
internal class SaltLyricsSource(loader: ClassLoader, private val playback: SaltPlaybackSource) {
    private val linesField = loader.loadClass("androidx.media3.uc1").getField("\u0528")
    private val lineType = loader.loadClass("androidx.media3.ed1")
    private val startField = lineType.getField("\u037f")
    private val textField = lineType.getField("\u052b")
    private var previousDocument: Any? = null
    private var previousKey = ""
    private var cached: LyricsSnapshot? = null

    fun readLyrics(player: PlayerSnapshot): LyricsSnapshot? {
        val song = playback.song()?.takeIf { it.matches(player) } ?: return null
        val document = playback.lyricsDocument(song) ?: return null
        val offsetMs = playback.cueOffsetMs()
        val key = "${song.id}\u0000$offsetMs\u0000${player.metadataKey}"
        if (document === previousDocument && key == previousKey) return cached
        val lines = (linesField.get(document) as? List<*>)?.mapNotNull { line ->
            line ?: return@mapNotNull null
            val text = textField.get(line) as? String ?: return@mapNotNull null
            LyricLine(startField.getLong(line) - offsetMs, text)
        }.orEmpty()
        if (playback.song()?.id != song.id || playback.lyricsDocument(song) !== document) return null
        val validLines = normalize(lines, player.durationMs)
        return LyricsSnapshot(key, if (validLines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, validLines)
            .also { previousDocument = document; previousKey = key; cached = it }
    }

    companion object {
        fun normalize(lines: List<LyricLine>, durationMs: Long): List<LyricLine> = lines
            .filter { it.text.isNotBlank() && it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs to it.text }
    }
}
