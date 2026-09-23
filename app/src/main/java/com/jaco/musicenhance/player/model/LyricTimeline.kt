package com.jaco.musicenhance.player.model

internal object LyricTimeline {
    fun normalize(lines: List<LyricLine>, offsetMs: Long = 0): List<LyricLine> = lines
        .filter { it.text.isNotBlank() && it.startMs >= 0 }
        .map { LyricLine((it.startMs + offsetMs).coerceAtLeast(0), it.text.trim()) }
        .groupBy { it.startMs }
        .toSortedMap()
        .map { (time, group) -> LyricLine(time, group.map { it.text }.distinct().joinToString("\n")) }

    /** -1 during the intro; the final line remains active until the track changes. */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        return low - 1
    }
}
