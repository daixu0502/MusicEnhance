package vip.chener.musicenhance.adapter.lxx

import vip.chener.musicenhance.Prefs
import vip.chener.musicenhance.adapter.MusicAppProfile

internal val LxxPlayerProfile = MusicAppProfile(
    packageName = "com.lxwalnut.music.mobile",
    displayName = "LX-X Music",
    enabledPreference = Prefs.HOOK_LXX_MUSIC,
    homeActivityNames = setOf("com.lxwalnut.music.mobile.MainActivity"),
    playerActivityMatcher = { false },
    horizontalActivityMatcher = { false },
    // This APK has no standard Activity in its own namespace. Borrow only its declared RN slot;
    // the common factory supplies EnhancedPlayerActivity, never the native settings screen.
    activityNamespaces = setOf("com.lxwalnut.music.mobile", "com.facebook.react.devsupport"),
)
