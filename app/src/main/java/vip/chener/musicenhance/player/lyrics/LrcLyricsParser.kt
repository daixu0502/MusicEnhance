package vip.chener.musicenhance.player.lyrics

import vip.chener.musicenhance.player.model.LyricLine

/** Parses only the timed text already supplied by a host. No file lookup or network access. */
internal object LrcLyricsParser {
    private val timestamps = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val wordTiming = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private const val MAX_LYRIC_CHARS = 512_000
    private const val MAX_LINES = 5_000

    // Salt applies a separate cue offset before discarding negative lines; LX-X clamps at zero.
    fun parse(text: String, clampNegativeTimes: Boolean = true): List<LyricLine> {
        if (text.length > MAX_LYRIC_CHARS) return emptyList()
        val offsetMs = offset.find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?.coerceIn(-86_400_000, 86_400_000) ?: 0L
        return text.lineSequence().take(MAX_LINES).flatMap { row ->
            val tags = timestamps.findAll(row).toList()
            val words = wordTiming.replace(timestamps.replace(row, ""), "").trim()
            if (words.isBlank()) emptySequence() else tags.asSequence().mapNotNull { tag ->
                val seconds = tag.groupValues[2].toInt()
                if (seconds >= 60) null else {
                    val timeMs = tag.groupValues[1].toLong() * 60_000 + seconds * 1_000 +
                        tag.groupValues[3].padEnd(3, '0').toLong() - offsetMs
                    LyricLine(if (clampNegativeTimes) timeMs.coerceAtLeast(0) else timeMs, words)
                }
            }
        }.take(MAX_LINES).distinct().sortedBy(LyricLine::startMs).toList()
    }
}
