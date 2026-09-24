package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppProfile

internal val KugouLiteMusicProfile = MusicAppProfile(
    packageName = "com.kugou.android.lite",
    displayName = "酷狗概念版",
    enabledPreference = Prefs.HOOK_KUGOU_LITE_MUSIC,
    homeActivityNames = setOf("com.kugou.android.app.MediaActivity"),
    playerActivityMatcher = { it == KugouLitePlayerLauncher.ACTIVITY_NAME },
    horizontalActivityMatcher = { false },
    activityNamespaces = setOf("com.kugou.android"),
    experimental = true,
)
