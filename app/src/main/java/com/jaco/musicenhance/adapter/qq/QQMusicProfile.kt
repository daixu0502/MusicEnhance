package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppProfile

internal val QQMusicProfile = MusicAppProfile(
    packageName = "com.tencent.qqmusic",
    displayName = "QQ 音乐",
    enabledPreference = Prefs.HOOK_QQ_MUSIC,
    homeActivityNames = setOf("com.tencent.qqmusic.activity.AppStarterActivity"),
    playerActivityMatcher = { name ->
        name.contains("player", ignoreCase = true) &&
            (name.endsWith("Activity") || name.contains("Activity\$"))
    },
    horizontalActivityMatcher = { it.contains("HorizontalScreenPlayerActivity", ignoreCase = true) },
)
