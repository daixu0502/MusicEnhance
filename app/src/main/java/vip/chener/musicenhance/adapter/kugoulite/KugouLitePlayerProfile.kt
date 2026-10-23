package vip.chener.musicenhance.adapter.kugoulite

import vip.chener.musicenhance.Prefs
import vip.chener.musicenhance.adapter.MusicAppProfile

internal val KugouLitePlayerProfile = MusicAppProfile(
    packageName = "com.kugou.android.lite",
    displayName = "酷狗概念版",
    enabledPreference = Prefs.HOOK_KUGOU_LITE_MUSIC,
    homeActivityNames = setOf("com.kugou.android.app.MediaActivity"),
    playerActivityMatcher = { it == "com.kugou.android.app.player.land.LandPlayerActivity" },
    horizontalActivityMatcher = { false },
    activityNamespaces = setOf("com.kugou.android"),
)
