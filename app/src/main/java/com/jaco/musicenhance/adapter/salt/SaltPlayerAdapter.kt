package com.jaco.musicenhance.adapter.salt

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.CachedNativeLyricsProvider
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.player.PlayerDataSource
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.media.MediaSessionDataSource
import com.jaco.musicenhance.player.model.PlayerControlState

internal object SaltPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = SaltPlayerProfile
    override fun installHooks(loader: ClassLoader) = SaltPlayerHooks.install(loader)
    override fun onActivityReady(activity: Activity) = SaltPlayerHooks.observe(activity)

    override fun createSession(activity: Activity, nativeRoot: ViewGroup): PlayerSession {
        val playback = SaltPlaybackSource(activity.classLoader)
        val media = MediaSessionDataSource
        val lyrics = SaltLyricsSource(activity.classLoader, playback)
        val data = object : PlayerDataSource by media {
            // Notification metadata may still carry the outgoing thumbnail during a track change.
            override fun snapshot() = media.snapshot().copy(artwork = null)
            override fun controlState() = PlayerControlState(
                repeatMode = playback.repeatMode(), favorite = true, songTitle = snapshot().title,
            )
        }
        return PlayerSession(
            profile.displayName, data,
            media.actions.copy(
                playPause = playback::playPause, previous = playback::previous, next = playback::next,
                seekTo = { playback.seekTo(it, data.snapshot(), resume = false) },
                seekAndPlay = { playback.seekTo(it, data.snapshot(), resume = true) },
                cycleRepeat = playback::cycleRepeat,
                // Requested display policy for local music; do not alter Salt's actual favorites.
                toggleFavorite = { false },
            ),
            CachedNativeLyricsProvider(lyrics::readLyrics), SaltArtworkSource(playback),
            onRelease = playback::release,
        )
    }
}
