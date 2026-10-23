package vip.chener.musicenhance.adapter.apple

import vip.chener.musicenhance.Prefs
import vip.chener.musicenhance.adapter.MusicAppProfile

internal val ApplePlayerProfile = MusicAppProfile(
    packageName = "com.apple.android.music",
    displayName = "Apple Music",
    enabledPreference = Prefs.HOOK_APPLE_MUSIC,
    homeActivityNames = setOf("com.apple.android.music.common.MainContentActivity"),
    // Audio is a bottom sheet in MainContentActivity. Video activities must remain native.
    playerActivityMatcher = { false },
    horizontalActivityMatcher = { false },
)
