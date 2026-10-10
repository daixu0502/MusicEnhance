package com.jaco.musicenhance.adapter.spotify

import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppProfile

internal val SpotifyPlayerProfile = MusicAppProfile(
    packageName = "com.spotify.music",
    displayName = "Spotify",
    enabledPreference = Prefs.HOOK_SPOTIFY,
    homeActivityNames = setOf("com.spotify.music.SpotifyMainActivity", "com.spotify.music.MainActivity"),
    playerActivityMatcher = { it == "com.spotify.nowplaying.musicinstallation.NowPlayingActivity" },
    horizontalActivityMatcher = { false },
    activityNamespaces = setOf("com.spotify"),
)
