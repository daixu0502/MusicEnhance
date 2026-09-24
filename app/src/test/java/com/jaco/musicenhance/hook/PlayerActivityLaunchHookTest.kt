package com.jaco.musicenhance.hook

import android.content.Intent
import android.content.pm.ActivityInfo
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.player.PlayerActivitySessions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlayerActivityLaunchHookTest {
    @Test fun activitySlotsCannotReuseHomeOrEscapeTheOriginalProcessAndTask() {
        for (profile in MusicAppRegistry.profiles) {
            val source = ActivityInfo().apply {
                packageName = profile.packageName
                name = profile.homeActivityNames.first()
                processName = packageName
                taskAffinity = packageName
                enabled = true
            }
            assertFalse(PlayerActivityLaunchHook.isUsableSlot(source, source, profile))
            val valid = ActivityInfo(source).apply { name = source.name.substringBeforeLast('.') + ".TestPlayerActivity" }
            assertTrue(PlayerActivityLaunchHook.isUsableSlot(valid, source, profile))
            for (invalid in listOf(
                ActivityInfo(valid).apply { launchMode = ActivityInfo.LAUNCH_SINGLE_TASK },
                ActivityInfo(valid).apply { processName += ":remote" },
                ActivityInfo(valid).apply { taskAffinity += ".other" },
                ActivityInfo(valid).apply { flags = ActivityInfo.FLAG_NO_HISTORY },
                ActivityInfo(valid).apply { targetActivity = valid.name },
                ActivityInfo(valid).apply { enabled = false },
                ActivityInfo(valid).apply { documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_ALWAYS },
            )) assertFalse(PlayerActivityLaunchHook.isUsableSlot(invalid, source, profile))
        }
    }
    @Test fun onlyExplicitModuleLaunchesAreReplacedAcrossAllFourApps() {
        for (profile in MusicAppRegistry.profiles) {
            val component = profile.homeActivityNames.first()
            val native = Intent().setClassName(profile.packageName, component)
            assertFalse(PlayerActivityLaunchHook.isPlayerIntent(native, component))
            val player = Intent(native).setAction(PlayerActivitySessions.ACTION)
                .putExtra(PlayerActivitySessions.EXTRA_SESSION, "session")
            assertTrue(PlayerActivityLaunchHook.isPlayerIntent(player, component))
            assertFalse(PlayerActivityLaunchHook.isPlayerIntent(player, "com.example.Activity"))
            assertTrue(profile.isEnhancedPlayerWindow(profile.enhancedPlayerWindowTitle(component)))
            assertFalse(profile.isEnhancedPlayerWindow("MusicEnhance:com.example/$component"))
            assertFalse(profile.isPlayerWindow("${profile.packageName}/$component"))
        }
    }

    @Test fun foreignPackagesAndMissingTokensNeverReplaceAnActivity() {
        val intent = Intent(PlayerActivitySessions.ACTION).setClassName("com.example", "com.example.Activity")
            .putExtra(PlayerActivitySessions.EXTRA_SESSION, "session")
        assertFalse(PlayerActivityLaunchHook.isPlayerIntent(intent, "com.example.Activity"))
        intent.setClassName("com.tencent.qqmusic", "com.tencent.qqmusic.activity.PlayerActivity")
        intent.removeExtra(PlayerActivitySessions.EXTRA_SESSION)
        assertFalse(PlayerActivityLaunchHook.isPlayerIntent(intent, intent.component?.className))
    }
}
