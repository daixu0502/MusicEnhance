package com.jaco.musicenhance.adapter.lxx

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.media.MediaSessionDataSource

internal object LxxPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = LxxPlayerProfile
    override fun installHooks(loader: ClassLoader) {
        LxxControlSource.install(loader)
        LxxStateBridge.install(loader)
        LxxPlaybackSource.install(loader)
        LxxPlayerHooks.install(loader)
    }
    override fun onApplicationCreated(application: Application) = LxxStateBridge.prepare(application)
    override fun onActivityReady(activity: Activity) = LxxPlayerHooks.observe(activity)

    override fun createSession(activity: Activity, nativeRoot: ViewGroup): PlayerSession {
        val playback = LxxPlaybackSource()
        return PlayerSession(
            profile.displayName, playback,
            MediaSessionDataSource.actions.copy(
                playPause = playback::playPause,
                seekTo = { playback.seek(it, resume = false) },
                seekAndPlay = { playback.seek(it, resume = true) },
                cycleRepeat = LxxControlSource::cycleRepeat,
                toggleFavorite = { false },
            ),
            LxxLyricsProvider(playback::currentSongId), LxxArtworkSource(playback),
            onRelease = playback::release,
        )
    }
}
