package vip.chener.musicenhance.adapter.apple

import android.app.Activity
import android.app.Application
import vip.chener.musicenhance.adapter.MusicPlayerAdapter
import vip.chener.musicenhance.player.PlayerDataSource
import vip.chener.musicenhance.player.PlayerSession
import vip.chener.musicenhance.player.media.MediaSessionDataSource
import vip.chener.musicenhance.player.media.MediaSessionStore
import vip.chener.musicenhance.player.artwork.ArtworkDiskCache
import vip.chener.musicenhance.player.artwork.PlaylistArtworkProvider
import java.io.File

internal object ApplePlayerAdapter : MusicPlayerAdapter {
    override val profile get() = ApplePlayerProfile
    override fun installHooks(loader: ClassLoader) {
        ApplePlayerHooks.install(loader)
        AppleLyricsProvider.install(loader)
    }
    override fun onApplicationCreated(application: Application) {
        MediaSessionStore.controlReader = AppleMediaControlSource(application.resources)
    }
    override fun onActivityReady(activity: Activity) = ApplePlayerHooks.observe(activity)
    override fun createSession(activity: Activity): PlayerSession {
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
