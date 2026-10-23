package vip.chener.musicenhance.adapter.kuwo

import android.app.Activity
import vip.chener.musicenhance.adapter.CachedNativeLyricsProvider
import vip.chener.musicenhance.adapter.MusicPlayerAdapter
import vip.chener.musicenhance.adapter.nativePlayerSession
import vip.chener.musicenhance.player.PlayerSession
import vip.chener.musicenhance.player.artwork.ArtworkDiskCache
import vip.chener.musicenhance.player.artwork.PlaylistArtworkProvider
import vip.chener.musicenhance.player.media.MediaSessionStore
import java.io.File

internal object KuwoPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = KuwoPlayerProfile
    override fun installHooks(loader: ClassLoader) {
        MediaSessionStore.controlReader = KuwoMediaControlSource
        KuwoPlaybackSource.install(loader)
    }
    override fun createSession(activity: Activity): PlayerSession {
        val data = KuwoPlaybackSource.Reader(activity.classLoader)
        val playback = KuwoPlaybackSource(activity.classLoader)
        val session = nativePlayerSession(
            profile.displayName,
            lyrics = CachedNativeLyricsProvider(data::readLyrics),
            artwork = PlaylistArtworkProvider(
                "Kuwo", { KuwoArtworkSource(activity.classLoader) },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
                readNativeArtwork = data::readArtwork,
            ),
            repeatApi = { KuwoRepeatSource(activity.classLoader) },
        )
        return session.copy(
            actions = session.actions.copy(seekAndPlay = { playback.seekAndPlay(it, session.data.snapshot()) }),
            onRelease = { try { playback.release() } finally { session.onRelease() } },
        )
    }
}
