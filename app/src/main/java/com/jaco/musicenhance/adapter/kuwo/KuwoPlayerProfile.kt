package com.jaco.musicenhance.adapter.kuwo

import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppProfile

internal val KuwoPlayerProfile = MusicAppProfile(
    packageName = "cn.kuwo.player",
    displayName = "酷我音乐",
    enabledPreference = Prefs.HOOK_KUWO_MUSIC,
    homeActivityNames = setOf("cn.kuwo.player.activities.MainActivity"),
    playerActivityMatcher = { it == "cn.kuwo.mod.nowplaynew.flip.MIUIFlipPlayPageActivity" },
    horizontalActivityMatcher = { false },
    activityNamespaces = setOf("cn.kuwo"),
)
