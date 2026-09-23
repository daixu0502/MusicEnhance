package com.jaco.musicenhance.player.lyrics

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Damped spring displacement: starts at rest, passes the target gently, then settles. */
internal object LyricSpring {
    const val SETTLE_MS = 760L
    private const val DAMPING_RATIO = 0.82
    private const val ANGULAR_FREQUENCY = 14.0
    private const val DECAY_RATE = DAMPING_RATIO * ANGULAR_FREQUENCY
    private val dampedFrequency = ANGULAR_FREQUENCY * sqrt(1.0 - DAMPING_RATIO * DAMPING_RATIO)

    fun remainingDisplacement(elapsedMs: Long): Float {
        if (elapsedMs <= 0) return 1f
        if (elapsedMs >= SETTLE_MS) return 0f
        val seconds = elapsedMs / 1000.0
        return (exp(-DECAY_RATE * seconds) * (cos(dampedFrequency * seconds) +
            DECAY_RATE / dampedFrequency * sin(dampedFrequency * seconds))).toFloat()
    }
}
