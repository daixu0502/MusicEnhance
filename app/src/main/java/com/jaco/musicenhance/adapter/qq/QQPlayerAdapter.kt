package com.jaco.musicenhance.adapter.qq

import android.app.Activity
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.player.PlayerDataSource
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.PlaylistArtworkProvider
import com.jaco.musicenhance.player.media.MediaSessionDataSource
import java.io.File

internal object QQPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = QQPlayerProfile
    override fun installHooks(loader: ClassLoader) {
        HostInterfaces.preload(loader, "qq.capabilities") { QQInterfaceRules.prepare(loader) }
        QQPlayerHooks.install(loader)
    }
    override fun createSession(activity: Activity): PlayerSession {
        val controls = QQControlSource(activity.classLoader)
        val playback = QQPlaybackSource(activity.classLoader)
        val media = MediaSessionDataSource
        val artworkFilter = QQArtworkFilter(activity.resources)
        return PlayerSession(
            profile.displayName,
            object : PlayerDataSource by media {
                override fun controlState() = controls.controlState()
            },
            media.actions.copy(
                seekAndPlay = { playback.seekAndPlay(it, media.snapshot()) },
                cycleRepeat = controls::cycleRepeat,
                toggleFavorite = { controls.toggleFavorite(media.snapshot()) },
            ),
            QQLyricsProvider(activity.classLoader),
            PlaylistArtworkProvider("QQ Music", { QQArtworkApi(activity.classLoader) },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
                // QQ's page also contains dynamically generated disc/blur bitmaps without
                // resource names. Its MediaSession publishes the unadorned native cover;
                // scanning arbitrary ImageViews can mistake a theme background for that cover.
                acceptNativeArtwork = artworkFilter::accepts),
            onRelease = { playback.release(); controls.release() },
        )
    }
}
