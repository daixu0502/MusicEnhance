package com.jaco.musicenhance.adapter.apple

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.player.PlayerDataSource
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.media.MediaSessionDataSource
import com.jaco.musicenhance.player.media.MediaSessionStore
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.PlaylistArtworkProvider
import java.io.File

internal object ApplePlayerAdapter : MusicPlayerAdapter {
    override val profile get() = ApplePlayerProfile
    override fun installHooks(loader: ClassLoader) {
        ApplePlayerHooks.install(loader)
        AppleLyricsProvider.install(loader)
        AppleFavoriteOperationHooks.install(loader)
    }
    override fun onApplicationCreated(application: Application) {
        MediaSessionStore.controlReader = AppleMediaControlSource(application.resources)
    }
    override fun onActivityReady(activity: Activity) = ApplePlayerHooks.observe(activity)
    override fun createSession(activity: Activity, nativeRoot: ViewGroup): PlayerSession {
        val playback = ApplePlaybackSource(activity.classLoader)
        val controls = AppleControlSource(playback)
        val media = MediaSessionDataSource
        return PlayerSession(
            profile.displayName,
            object : PlayerDataSource by media {
                override fun controlState() = controls.controlState()
            },
            media.actions.copy(cycleRepeat = controls::cycleRepeat, toggleFavorite = controls::toggleFavorite),
            AppleLyricsProvider(activity.application, playback::item),
            PlaylistArtworkProvider(
                "Apple Music", { AppleArtworkSource { playback.artwork } },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
            ),
            onRelease = playback::release,
        )
    }
}
