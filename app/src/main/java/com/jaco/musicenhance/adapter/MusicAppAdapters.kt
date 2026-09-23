package com.jaco.musicenhance.adapter

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.qq.QQMusicPlayerController
import com.jaco.musicenhance.adapter.qq.QQMusicProfile
import com.jaco.musicenhance.player.PlayerController
import com.jaco.musicenhance.player.media.MediaSessionPlayerController

/** App-specific native control factories are kept separate from package/activity classification. */
internal object MusicAppAdapters {
    fun create(profile: MusicAppProfile, activity: Activity, root: ViewGroup): PlayerController =
        when (profile.packageName) {
            QQMusicProfile.packageName -> QQMusicPlayerController(activity.classLoader, root)
            else -> MediaSessionPlayerController(profile.displayName)
        }
}
