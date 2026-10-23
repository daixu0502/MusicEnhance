package vip.chener.musicenhance.adapter.kugou

import vip.chener.musicenhance.Prefs
import vip.chener.musicenhance.adapter.MusicAppProfile

internal const val KUGOU_FLIP_ACTIVITY = "com.kugou.android.app.player.flip.MiFlipPlayerActivity"

internal val KugouPlayerProfile = MusicAppProfile(
    packageName = "com.kugou.android",
    displayName = "酷狗音乐",
    enabledPreference = Prefs.HOOK_KUGOU_STANDARD_MUSIC,
    homeActivityNames = setOf("com.kugou.android.app.MediaActivity"),
    playerActivityMatcher = { it == KUGOU_FLIP_ACTIVITY },
    horizontalActivityMatcher = { false },
)
