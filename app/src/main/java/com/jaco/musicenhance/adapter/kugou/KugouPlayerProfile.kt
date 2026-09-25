package com.jaco.musicenhance.adapter.kugou

import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppProfile

internal const val KUGOU_FLIP_ACTIVITY = "com.kugou.android.app.player.flip.MiFlipPlayerActivity"

internal val KugouPlayerProfile = MusicAppProfile(
    packageName = "com.kugou.android",
    displayName = "酷狗音乐",
    enabledPreference = Prefs.HOOK_KUGOU_STANDARD_MUSIC,
    homeActivityNames = setOf("com.kugou.android.app.MediaActivity"),
    playerActivityMatcher = { it == KUGOU_FLIP_ACTIVITY },
    horizontalActivityMatcher = { false },
)
