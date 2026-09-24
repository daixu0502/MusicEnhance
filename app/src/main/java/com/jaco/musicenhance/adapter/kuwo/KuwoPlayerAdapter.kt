package com.jaco.musicenhance.adapter.kuwo

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.CachedNativeLyricsProvider
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.adapter.nativePlayerSession
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.PlaylistArtworkProvider
import com.jaco.musicenhance.player.media.MediaSessionStore
import java.io.File

internal object KuwoPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = KuwoPlayerProfile
    override fun installHooks(loader: ClassLoader) {
        MediaSessionStore.controlReader = KuwoMediaControlSource
        KuwoPlaybackSource.install(loader)
    }
    override fun createSession(activity: Activity, nativeRoot: ViewGroup): PlayerSession {
        val data = KuwoPlaybackSource.Reader(activity.classLoader)
        val playback = KuwoPlaybackSource(activity.classLoader)
        val session = nativePlayerSession(
            profile.displayName,
            lyrics = CachedNativeLyricsProvider(data::readLyrics),
            artwork = PlaylistArtworkProvider(
                "Kuwo", { KuwoArtworkSource(activity.classLoader) },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
            ),
            readArtwork = { data.readArtwork(nativeRoot) },
            repeatApi = { KuwoRepeatSource(activity.classLoader) },
        )
        return session.copy(
            actions = session.actions.copy(seekAndPlay = { playback.seekAndPlay(it, session.data.snapshot()) }),
            onRelease = { try { playback.release() } finally { session.onRelease() } },
        )
    }
}
