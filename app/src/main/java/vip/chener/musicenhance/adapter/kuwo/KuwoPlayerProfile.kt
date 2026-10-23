package vip.chener.musicenhance.adapter.kuwo

import vip.chener.musicenhance.Prefs
import vip.chener.musicenhance.adapter.MusicAppProfile

internal val KuwoPlayerProfile = MusicAppProfile(
    packageName = "cn.kuwo.player",
    displayName = "酷我音乐",
    enabledPreference = Prefs.HOOK_KUWO_MUSIC,
    homeActivityNames = setOf("cn.kuwo.player.activities.MainActivity"),
    playerActivityMatcher = { it == "cn.kuwo.mod.nowplaynew.flip.MIUIFlipPlayPageActivity" },
    horizontalActivityMatcher = { false },
    activityNamespaces = setOf("cn.kuwo"),
)
