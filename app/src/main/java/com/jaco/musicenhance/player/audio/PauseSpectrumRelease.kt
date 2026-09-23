package com.jaco.musicenhance.player.audio


/** Monotonic visual release; buffered audio must not influence it after pause. */
internal class PauseSpectrumRelease(private val durationMs: Long = 800L) {
    private var startAt = 0L
    private var startLevel = 0f

    fun start(level: Float, now: Long) {
        startLevel = level.coerceIn(0f, 1f)
        startAt = now
    }

    fun level(now: Long): Float {
        val remaining = 1f - ((now - startAt).toFloat() / durationMs).coerceIn(0f, 1f)
        return startLevel * remaining * remaining
    }
}
