package com.jaco.musicenhance.player.audio

/** Absolute-time segments avoid rebasing buffered audio whenever a new IPC packet arrives. */
internal class SpectrumTimeline(
    private val maxFrames: Int = 800,
    private val maxStreams: Int = 4,
) {
    private data class Segment(
        val startAtNanos: Long,
        val frameDurationNanos: Long,
        val levels: FloatArray,
    ) {
        val endAtNanos = startAtNanos + frameDurationNanos * levels.size
    }

    private data class Stream(val segments: ArrayDeque<Segment> = ArrayDeque(), var frameCount: Int = 0)

    private val streams = linkedMapOf<Long, Stream>()

    fun append(streamId: Long, startAtNanos: Long, levels: FloatArray, frameDurationMs: Int, nowNanos: Long) {
        if (levels.isEmpty()) return
        discardExpired(nowNanos)
        val durationNanos = frameDurationMs.coerceIn(5, 50) * 1_000_000L
        val safeStart = startAtNanos.takeIf {
            it >= nowNanos - MAX_PAST_NANOS && it <= nowNanos + MAX_FUTURE_NANOS
        } ?: nowNanos
        val sanitized = FloatArray(levels.size.coerceAtMost(maxFrames)) { levels[it].coerceIn(0f, 1f) }
        val stream = streams.getOrPut(streamId) { Stream() }
        stream.segments.addLast(Segment(safeStart, durationNanos, sanitized))
        stream.frameCount += sanitized.size
        while (stream.frameCount > maxFrames && stream.segments.isNotEmpty()) {
            stream.frameCount -= stream.segments.removeFirst().levels.size
        }
        while (streams.size > maxStreams) streams.remove(streams.keys.first())
    }

    fun levelAt(nowNanos: Long): Float {
        discardExpired(nowNanos)
        var level = 0f
        streams.values.forEach { stream ->
            stream.segments.forEach { segment ->
                if (nowNanos >= segment.startAtNanos && nowNanos < segment.endAtNanos) {
                    val index = ((nowNanos - segment.startAtNanos) / segment.frameDurationNanos).toInt()
                    level = maxOf(level, segment.levels[index])
                }
            }
        }
        return level
    }

    fun clear() = streams.clear()

    fun remove(streamId: Long) {
        streams.remove(streamId)
    }

    private fun discardExpired(nowNanos: Long) {
        val iterator = streams.iterator()
        while (iterator.hasNext()) {
            val stream = iterator.next().value
            while (stream.segments.firstOrNull()?.endAtNanos?.let { it <= nowNanos } == true) {
                stream.frameCount -= stream.segments.removeFirst().levels.size
            }
            if (stream.segments.isEmpty()) iterator.remove()
        }
    }

    private companion object {
        const val MAX_PAST_NANOS = 1_000_000_000L
        const val MAX_FUTURE_NANOS = 4_000_000_000L
    }
}
