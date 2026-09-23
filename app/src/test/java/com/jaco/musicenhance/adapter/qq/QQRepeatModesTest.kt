package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.adapter.qq.QQRepeatModes
import com.jaco.musicenhance.player.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class QQRepeatModesTest {
    @Test fun cyclesThroughAllFourServiceModes() {
        var mode = 106
        for (expected in listOf(103, 101, 105, 106)) {
            mode = QQRepeatModes.next(mode)
            assertEquals(expected, mode)
        }
    }

    @Test fun usesCurrentSequentialConstantRatherThanObsolete102() {
        assertEquals(RepeatMode.SEQUENTIAL, QQRepeatModes.decode(106))
        assertEquals(RepeatMode.UNKNOWN, QQRepeatModes.decode(102))
        assertEquals(103, QQRepeatModes.next(102))
    }

    @Test fun bothNativeShuffleModesCanReturnToSequentialPlayback() {
        for (mode in listOf(104, 105)) {
            assertEquals(RepeatMode.SHUFFLE, QQRepeatModes.decode(mode))
            assertEquals(106, QQRepeatModes.next(mode))
        }
    }
}
