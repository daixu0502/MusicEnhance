package com.jaco.musicenhance.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverRotationPolicyTest {
    private val player = "com.tencent.qqmusic.business.playernew.view.NewPlayerActivity"

    @Test fun acceptsBothCoverPortraitDirections() {
        assertTrue(CoverRotationPolicy.matches(0, 2, 1208, 1392, 1208, 1392, "com.tencent.qqmusic", player))
        assertTrue(CoverRotationPolicy.matches(2, 0, 1208, 1392, 1208, 1392, "com.tencent.qqmusic", player))
    }

    @Test fun excludesInnerScreenAndPanelChanges() {
        assertFalse(CoverRotationPolicy.matches(0, 2, 1224, 2912, 1224, 2912, "com.tencent.qqmusic", player))
        assertFalse(CoverRotationPolicy.matches(0, 2, 1224, 2912, 1208, 1392, "com.tencent.qqmusic", player))
    }

    @Test fun excludesOtherAppsHomeAndHorizontalRotations() {
        assertFalse(CoverRotationPolicy.matches(0, 2, 1208, 1392, 1208, 1392, "com.netease.cloudmusic", player))
        assertFalse(CoverRotationPolicy.matches(0, 2, 1208, 1392, 1208, 1392, "com.tencent.qqmusic", "com.tencent.qqmusic.activity.AppStarterActivity"))
        assertFalse(CoverRotationPolicy.matches(0, 1, 1208, 1392, 1392, 1208, "com.tencent.qqmusic", player))
        assertFalse(CoverRotationPolicy.matches(0, 0, 1208, 1392, 1208, 1392, "com.tencent.qqmusic", player))
    }
}
