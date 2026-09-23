package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.adapter.qq.QQMusicProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAppProfileTest {
    @Test fun unknownPackagesNeverReceiveAProfile() {
        assertNull(MusicAppRegistry.find(null))
        assertNull(MusicAppRegistry.find("com.example.music"))
        assertNull(MusicAppRegistry.find("com.tencent.qqmusic.clone"))
        assertNull(MusicAppRegistry.forActivity("com.example.music.PlayerActivity"))
        assertSame(QQMusicProfile, MusicAppRegistry.find("com.tencent.qqmusic"))
    }

    @Test fun qqHomeAndPlayerHaveDifferentPolicies() {
        val home = "com.tencent.qqmusic.activity.AppStarterActivity"
        val player = "com.tencent.qqmusic.activity.PlayerActivity"
        val horizontal = "com.tencent.qqmusic.activity.HorizontalScreenPlayerActivity"
        assertTrue(QQMusicProfile.isHomeActivity(home))
        assertFalse(QQMusicProfile.isPlayerActivity(home))
        assertTrue(QQMusicProfile.isPlayerActivity(player))
        assertFalse(QQMusicProfile.isHorizontalPlayerActivity(player))
        assertTrue(QQMusicProfile.isHorizontalPlayerActivity(horizontal))
    }

    @Test fun foreignOrIncompleteActivityNamesAreRejected() {
        for (name in listOf(null, "", ".PlayerActivity", "PlayerActivity",
            "com.tencent.qqmusicfake.PlayerActivity", "com.example.PlayerActivity")) {
            assertFalse(QQMusicProfile.isPlayerActivity(name))
            assertFalse(QQMusicProfile.isHorizontalPlayerActivity(name))
        }
    }

    @Test fun windowTitlesAcceptAndroidShortComponentsWithoutMatchingOtherApps() {
        assertTrue(QQMusicProfile.isPlayerWindow("com.tencent.qqmusic/.activity.PlayerActivity"))
        assertTrue(QQMusicProfile.isPlayerWindow("com.tencent.qqmusic/com.tencent.qqmusic.activity.PlayerActivity"))
        assertTrue(QQMusicProfile.isPlayerWindow("com.tencent.qqmusic.activity.PlayerActivity"))
        assertFalse(QQMusicProfile.isPlayerWindow("com.example/com.tencent.qqmusic.activity.PlayerActivity"))
        assertFalse(QQMusicProfile.isPlayerWindow("com.tencent.qqmusic/.activity.AppStarterActivity"))
        assertFalse(QQMusicProfile.isPlayerWindow(null))
    }

    @Test fun newAppCanUseItsOwnRulesWithoutChangingSharedCode() {
        val profile = MusicAppProfile(
            packageName = "org.example.music",
            displayName = "Example",
            enabledPreference = "hook_example",
            homeActivityNames = setOf("org.example.music.PlayerHomeActivity"),
            playerActivityMatcher = { it.contains("Player") || it.endsWith("NowPlaying") },
            horizontalActivityMatcher = { it.endsWith("WidePlayer") },
        )
        assertTrue(profile.isPlayerActivity("org.example.music.NowPlaying"))
        assertTrue(profile.isHorizontalPlayerActivity("org.example.music.WidePlayer"))
        assertTrue(profile.isHomeActivity("org.example.music.PlayerHomeActivity"))
        assertFalse(profile.isPlayerActivity("org.example.music.PlayerHomeActivity"))
        assertFalse(profile.isPlayerActivity("com.tencent.qqmusic.activity.PlayerActivity"))
        // Constructing a profile alone must not opt a package into hooks.
        assertNull(MusicAppRegistry.find(profile.packageName))
    }
}
