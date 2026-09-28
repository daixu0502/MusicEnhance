package com.jaco.musicenhance.adapter.lxx

import com.jaco.musicenhance.player.lyrics.LyricsProvider
import com.jaco.musicenhance.player.model.LyricLine
import com.jaco.musicenhance.player.model.LyricsSnapshot
import com.jaco.musicenhance.player.model.LyricsStatus
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Only the current host-provided lyric is retained; no file lookup, search or download. */
internal class LxxLyricsProvider(private val currentSongId: () -> String?) : LyricsProvider {
    override fun snapshot(player: PlayerSnapshot): LyricsSnapshot {
        val state = LxxStateBridge.state
        val matches = state.id.isNotBlank() && state.id == currentSongId() && state.metadataKey == player.metadataKey
        return if (matches) LyricsSnapshot(state.id, if (state.lines.isEmpty()) LyricsStatus.UNAVAILABLE else LyricsStatus.READY, state.lines)
        else LyricsSnapshot(player.metadataKey, LyricsStatus.UNAVAILABLE)
    }

    override fun release() = Unit // The process bridge belongs to RN, not this screen.

    companion object {
        private val timestamps = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
        private val wordTiming = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
        private const val MAX_LYRIC_CHARS = 512_000
        private const val MAX_LINES = 5_000

        internal fun parse(text: String): List<LyricLine> {
            if (text.length > MAX_LYRIC_CHARS) return emptyList()
            val offsetMs = offset.find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-86_400_000, 86_400_000) ?: 0L
            return text.lineSequence().take(MAX_LINES).flatMap { row ->
                val tags = timestamps.findAll(row).toList()
                val words = wordTiming.replace(timestamps.replace(row, ""), "").trim()
                if (words.isBlank()) emptySequence() else tags.asSequence().mapNotNull { match ->
                    val seconds = match.groupValues[2].toInt()
                    if (seconds >= 60) null else {
                        val fractionMs = match.groupValues[3].padEnd(3, '0').toLong()
                        val timeMs = match.groupValues[1].toLong() * 60_000 + seconds * 1_000 + fractionMs - offsetMs
                        LyricLine(timeMs.coerceAtLeast(0), words)
                    }
                }
            }.take(MAX_LINES).distinct().sortedBy(LyricLine::startMs).toList()
        }
    }
}
