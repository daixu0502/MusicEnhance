package com.jaco.musicenhance.adapter.qq

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.adapter.NativePlayerViews
import com.jaco.musicenhance.player.PlayerDataSource
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.media.MediaSessionDataSource
import java.io.File

internal object QQPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = QQPlayerProfile
    override fun createSession(activity: Activity, nativeRoot: ViewGroup): PlayerSession {
        val controls = QQControlSource(activity.classLoader)
        val playback = QQPlaybackSource(activity.classLoader)
        val media = MediaSessionDataSource
        return PlayerSession(
            profile.displayName,
            object : PlayerDataSource by media {
                override fun controlState() = controls.controlState()
                override fun nativeArtwork() = NativePlayerViews.findArtwork(nativeRoot)
            },
            media.actions.copy(
                seekAndPlay = { playback.seekAndPlay(it, media.snapshot()) },
                cycleRepeat = controls::cycleRepeat,
                toggleFavorite = { NativePlayerViews.clickControl(nativeRoot, FAVORITE_KEYWORDS) || media.actions.toggleFavorite() },
            ),
            QQLyricsProvider(activity.classLoader),
            QQArtworkProvider(activity.classLoader, diskCache = ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork"))),
            onRelease = playback::release,
        )
    }
    private val FAVORITE_KEYWORDS = listOf("favorite", "favourite", "collect", "like", "love", "fav", "收藏", "喜欢", "爱心")
}
