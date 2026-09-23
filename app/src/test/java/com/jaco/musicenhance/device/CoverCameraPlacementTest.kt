package com.jaco.musicenhance.device

import com.jaco.musicenhance.device.CoverCameraPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverCameraPlacementTest {
    private val reverse = CoverCameraPlacement.Cutout(0, 0, 398, 728, 1208, 1392)
    private val upright = CoverCameraPlacement.Cutout(810, 664, 1208, 1392, 1208, 1392)

    @Test fun reverseHomeLaunchUsesWindowWhenDisplayCutoutIsMissing() {
        assertEquals(CoverCameraPlacement.Placement(false, false, "window-cutout"),
            CoverCameraPlacement.resolve(null, reverse, 0))
    }

    @Test fun displayCutoutWinsOverStaleActivityRotationAndInsets() {
        assertEquals(CoverCameraPlacement.Placement(false, false, "display-cutout"),
            CoverCameraPlacement.resolve(reverse, upright, 0))
        assertEquals(CoverCameraPlacement.Placement(true, true, "display-cutout"),
            CoverCameraPlacement.resolve(upright, reverse, 2))
    }

    @Test fun innerScreenNotchDoesNotOverrideCoverWindow() {
        val innerNotch = CoverCameraPlacement.Cutout(569, 0, 655, 146, 1224, 2912)
        assertEquals(CoverCameraPlacement.Placement(false, false, "window-cutout"),
            CoverCameraPlacement.resolve(innerNotch, reverse, 0))
    }

    @Test fun uprightWindowWorksWithStaleReverseRotation() {
        assertEquals(CoverCameraPlacement.Placement(true, true, "window-cutout"),
            CoverCameraPlacement.resolve(null, upright, 2))
    }

    @Test fun absentOrInvalidCutoutsFallBackToRotation() {
        val empty = reverse.copy(right = 0, bottom = 0)
        val outside = reverse.copy(left = -398)
        assertEquals(CoverCameraPlacement.Placement(false, false, "rotation"),
            CoverCameraPlacement.resolve(empty, outside, 2))
        assertEquals(CoverCameraPlacement.Placement(true, true, "rotation"),
            CoverCameraPlacement.resolve(null, null, 0))
    }
}
