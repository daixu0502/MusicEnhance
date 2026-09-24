package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.adapter.qq.QQPlayerProfile
import com.jaco.musicenhance.adapter.apple.ApplePlayerProfile
import com.jaco.musicenhance.adapter.kugoulite.KugouLitePlayerProfile
import com.jaco.musicenhance.adapter.kuwo.KuwoPlayerProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAppProfileTest {
    @Test fun nativeNamespacesNeedNotMatchInstalledPackage() {
        assertSame(KugouLitePlayerProfile, MusicAppRegistry.forActivity("com.kugou.android.app.MediaActivity"))
        assertSame(KuwoPlayerProfile, MusicAppRegistry.forActivity("cn.kuwo.mod.nowplaynew.flip.MIUIFlipPlayPageActivity"))
        assertTrue(KuwoPlayerProfile.isPlayerWindow("cn.kuwo.player/cn.kuwo.mod.nowplaynew.flip.MIUIFlipPlayPageActivity"))
        assertFalse(KuwoPlayerProfile.isPlayerWindow("cn.kuwo.fake/cn.kuwo.mod.nowplaynew.flip.MIUIFlipPlayPageActivity"))
        assertNull(MusicAppRegistry.find("com.kugou.android")) // Full Kugou is not the tested Lite package.
    }

    @Test fun ownedPlayerMarkerDoesNotChangeUnmarkedHomeOrVideoClassification() {
        val profile = ApplePlayerProfile
        val home = profile.homeActivityNames.single()
        assertFalse(profile.isPlayerActivity(home))
        assertFalse(profile.isPlayerWindow("${profile.packageName}/$home"))
        assertTrue(profile.isPlayerWindow(profile.enhancedPlayerWindowTitle(home)))
        assertFalse(profile.isPlayerWindow(profile.enhancedPlayerWindowTitle("com.example.PlayerActivity")))
        assertFalse(ApplePlayerProfile.isPlayerActivity("com.apple.android.music.player.VideoFullScreenActivity"))
        assertFalse(ApplePlayerProfile.isPlayerActivity("com.apple.android.music.player2.FullScreenVideoActivity"))
    }

    @Test fun kugouLiteUsesSeparateActivityWithoutExpandingHomeOrUnrelatedPages() {
        val player = "com.kugou.android.app.player.land.LandPlayerActivity"
        val home = "com.kugou.android.app.MediaActivity"
        assertTrue(KugouLitePlayerProfile.isPlayerActivity(player))
        assertFalse(KugouLitePlayerProfile.isHorizontalPlayerActivity(player))
        assertTrue(KugouLitePlayerProfile.isPlayerWindow("com.kugou.android.lite/$player"))
        assertFalse(KugouLitePlayerProfile.isPlayerWindow("com.kugou.android/$player"))
        assertTrue(KugouLitePlayerProfile.isHomeActivity(home))
        assertFalse(KugouLitePlayerProfile.isPlayerActivity(home))
        assertTrue(KugouLitePlayerProfile.isPlayerWindow(KugouLitePlayerProfile.enhancedPlayerWindowTitle(home)))
        assertFalse(KugouLitePlayerProfile.isPlayerActivity("com.kugou.android.app.SvFragmentContainerActivity"))
        assertFalse(KugouLitePlayerProfile.isPlayerActivity("com.kugou.android.app.player.land.LightPlayerEditActivity"))
    }

    @Test fun unknownPackagesNeverReceiveAProfile() {
        assertNull(MusicAppRegistry.find(null))
        assertNull(MusicAppRegistry.find("com.example.music"))
        assertNull(MusicAppRegistry.find("com.tencent.qqmusic.clone"))
        assertNull(MusicAppRegistry.forActivity("com.example.music.PlayerActivity"))
        assertSame(QQPlayerProfile, MusicAppRegistry.find("com.tencent.qqmusic"))
    }

    @Test fun qqHomeAndPlayerHaveDifferentPolicies() {
        val home = "com.tencent.qqmusic.activity.AppStarterActivity"
        val player = "com.tencent.qqmusic.activity.PlayerActivity"
        val horizontal = "com.tencent.qqmusic.activity.HorizontalScreenPlayerActivity"
        assertTrue(QQPlayerProfile.isHomeActivity(home))
        assertFalse(QQPlayerProfile.isPlayerActivity(home))
        assertTrue(QQPlayerProfile.isPlayerActivity(player))
        assertFalse(QQPlayerProfile.isHorizontalPlayerActivity(player))
        assertTrue(QQPlayerProfile.isHorizontalPlayerActivity(horizontal))
    }

    @Test fun foreignOrIncompleteActivityNamesAreRejected() {
        for (name in listOf(null, "", ".PlayerActivity", "PlayerActivity",
            "com.tencent.qqmusicfake.PlayerActivity", "com.example.PlayerActivity")) {
            assertFalse(QQPlayerProfile.isPlayerActivity(name))
            assertFalse(QQPlayerProfile.isHorizontalPlayerActivity(name))
        }
    }

    @Test fun windowTitlesAcceptAndroidShortComponentsWithoutMatchingOtherApps() {
        assertTrue(QQPlayerProfile.isPlayerWindow("com.tencent.qqmusic/.activity.PlayerActivity"))
        assertTrue(QQPlayerProfile.isPlayerWindow("com.tencent.qqmusic/com.tencent.qqmusic.activity.PlayerActivity"))
        assertTrue(QQPlayerProfile.isPlayerWindow("com.tencent.qqmusic.activity.PlayerActivity"))
        assertFalse(QQPlayerProfile.isPlayerWindow("com.example/com.tencent.qqmusic.activity.PlayerActivity"))
        assertFalse(QQPlayerProfile.isPlayerWindow("com.tencent.qqmusic/.activity.AppStarterActivity"))
        assertFalse(QQPlayerProfile.isPlayerWindow(null))
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
