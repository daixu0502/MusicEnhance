package vip.chener.musicenhance.adapter.qq

import vip.chener.musicenhance.Prefs
import vip.chener.musicenhance.adapter.MusicAppProfile

internal val QQPlayerProfile = MusicAppProfile(
    packageName = "com.tencent.qqmusic",
    displayName = "QQ 音乐",
    enabledPreference = Prefs.HOOK_QQ_MUSIC,
    homeActivityNames = setOf("com.tencent.qqmusic.activity.AppStarterActivity"),
    playerActivityMatcher = { name ->
        name.contains("player", ignoreCase = true) &&
            (name.endsWith("Activity") || name.contains("Activity\$"))
    },
    horizontalActivityMatcher = { it.contains("HorizontalScreenPlayerActivity", ignoreCase = true) },
    hideCoverHomeNavigationBar = true,
)
