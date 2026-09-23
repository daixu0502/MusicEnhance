package com.jaco.musicenhance.player.audio

import com.jaco.musicenhance.player.audio.PauseSpectrumRelease
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PauseSpectrumReleaseTest {
    @Test fun pausePreservesCurrentHeightAndReachesRestIn800ms() {
        val release = PauseSpectrumRelease()
        release.start(0.8f, 1000L)
        assertEquals(0.8f, release.level(1000L), 0.0001f)
        assertEquals(0.2f, release.level(1400L), 0.0001f)
        assertEquals(0f, release.level(1800L), 0f)
        assertEquals(0f, release.level(9000L), 0f)
    }

    @Test fun releaseIsMonotonicAcrossDifferentFrameRatesAndDroppedFrames() {
        for (step in listOf(8L, 16L, 33L, 127L)) {
            val release = PauseSpectrumRelease()
            release.start(1f, 0L)
            var previous = 1f
            for (time in 0L..1000L step step) {
                val level = release.level(time)
                assertTrue(level in 0f..previous)
                previous = level
            }
            assertEquals(0f, release.level(1000L), 0f)
        }
    }

    @Test fun secondPauseStartsFromNewDisplayedHeight() {
        val release = PauseSpectrumRelease()
        release.start(0.9f, 0L)
        release.start(0.3f, 200L)
        assertEquals(0.3f, release.level(200L), 0.0001f)
        assertEquals(0f, release.level(1000L), 0f)
    }
}
