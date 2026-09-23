package com.jaco.musicenhance.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSpringTest {
    @Test fun delayedRowsStartAtRestAndFinishExactlyAtTheTarget() {
        assertEquals(1f, LyricSpring.remainingDisplacement(-40), 0f)
        assertEquals(1f, LyricSpring.remainingDisplacement(0), 0f)
        assertTrue(LyricSpring.remainingDisplacement(40) > LyricSpring.remainingDisplacement(120))
        assertEquals(0f, LyricSpring.remainingDisplacement(LyricSpring.SETTLE_MS), 0f)
        assertEquals(0f, LyricSpring.remainingDisplacement(10_000), 0f)
    }

    @Test fun overshootIsGentleAndDoesNotOscillateIndefinitely() {
        val values = (0L..LyricSpring.SETTLE_MS step 10).map(LyricSpring::remainingDisplacement)
        assertTrue(values.all { it.isFinite() && it in -0.02f..1f })
        assertTrue(values.any { it < 0f })
        assertTrue(kotlin.math.abs(LyricSpring.remainingDisplacement(LyricSpring.SETTLE_MS - 1)) < 0.001f)
    }
}
