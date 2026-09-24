package com.jaco.musicenhance.adapter.salt

import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppProfile

internal val SaltPlayerProfile = MusicAppProfile(
    packageName = "com.salt.music",
    displayName = "椒盐音乐",
    enabledPreference = Prefs.HOOK_SALT_MUSIC,
    homeActivityNames = setOf("com.salt.music.ui.MainActivity"),
    // The native player is a Compose sheet inside the library Activity.
    playerActivityMatcher = { false },
    horizontalActivityMatcher = { false },
    experimental = true,
)
