package com.jaco.musicenhance.adapter.kugou

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.CachedNativeLyricsProvider
import com.jaco.musicenhance.adapter.MusicPlayerAdapter
import com.jaco.musicenhance.adapter.PolledFavoriteControl
import com.jaco.musicenhance.adapter.nativePlayerSession
import com.jaco.musicenhance.player.PlayerSession
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.PlaylistArtworkProvider
import java.io.File

internal object KugouPlayerAdapter : MusicPlayerAdapter {
    override val profile get() = KugouPlayerProfile
    override fun installHooks(loader: ClassLoader) = KugouPlayerHooks.install(loader)
    override fun onPlayerUnavailable(activity: Activity) = KugouPlayerHooks.onPlayerUnavailable(activity)
    override fun createSession(activity: Activity, nativeRoot: ViewGroup): PlayerSession {
        val playback = KugouPlaybackSource(activity.classLoader)
        val lyrics = KugouLyricsSource(activity.classLoader)
        val session = nativePlayerSession(profile.displayName,
            lyrics = CachedNativeLyricsProvider(lyrics::readLyrics),
            artwork = PlaylistArtworkProvider("Kugou", { KugouArtworkSource(activity.classLoader) },
                ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork"))),
            // FlipPlayerDelegate applies blur to native backgrounds. Use media art and original URLs.
            readArtwork = { null },
            repeatApi = { KugouRepeatSource(activity.classLoader) },
            favorite = PolledFavoriteControl({ KugouFavoriteSource(activity, nativeRoot) }),
        )
        return session.copy(actions = session.actions.copy(seekAndPlay = { playback.seekAndPlay(it, session.data.snapshot()) }),
            onRelease = { try { playback.release(); lyrics.release() } finally { session.onRelease() } })
    }
}
