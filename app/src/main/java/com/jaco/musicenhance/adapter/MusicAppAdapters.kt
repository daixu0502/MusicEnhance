package com.jaco.musicenhance.adapter

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.apple.AppleMusicControls
import com.jaco.musicenhance.adapter.apple.AppleMusicPageHooks
import com.jaco.musicenhance.adapter.apple.AppleMusicProfile
import com.jaco.musicenhance.adapter.apple.AppleMusicPlayerController
import com.jaco.musicenhance.adapter.apple.AppleNativePlayer
import com.jaco.musicenhance.adapter.apple.AppleLyricsProvider
import com.jaco.musicenhance.adapter.kugoulite.KugouLitePlayerActivityHooks
import com.jaco.musicenhance.adapter.kugoulite.KugouLiteMusicProfile
import com.jaco.musicenhance.adapter.kugoulite.KugouLiteRepeatApi
import com.jaco.musicenhance.adapter.kugoulite.KugouLiteFavoriteControl
import com.jaco.musicenhance.adapter.kugoulite.KugouLiteLyrics
import com.jaco.musicenhance.adapter.kugoulite.KugouLiteArtworkSource
import com.jaco.musicenhance.adapter.kuwo.KuwoMediaControls
import com.jaco.musicenhance.adapter.kuwo.KuwoMusicProfile
import com.jaco.musicenhance.adapter.kuwo.KuwoRepeatApi
import com.jaco.musicenhance.adapter.kuwo.KuwoNativeData
import com.jaco.musicenhance.adapter.kuwo.KuwoArtworkSource
import com.jaco.musicenhance.adapter.qq.QQMusicPlayerController
import com.jaco.musicenhance.adapter.qq.QQMusicProfile
import com.jaco.musicenhance.adapter.qq.QQMusicWindowHooks
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.PlayerController
import com.jaco.musicenhance.player.artwork.ArtworkDiskCache
import com.jaco.musicenhance.player.artwork.PlaylistArtworkProvider
import com.jaco.musicenhance.player.media.MediaSessionPlayerController
import com.jaco.musicenhance.player.media.MediaSessionStore
import java.io.File

/** App-specific native control factories are kept separate from package/activity classification. */
internal object MusicAppAdapters {
    fun installNativeHooks(profile: MusicAppProfile, classLoader: ClassLoader) {
        when (profile.packageName) {
            QQMusicProfile.packageName -> QQMusicWindowHooks.install(classLoader)
            AppleMusicProfile.packageName -> {
                AppleMusicPageHooks.install(classLoader)
                AppleNativePlayer.install(classLoader)
                AppleLyricsProvider.install(classLoader)
            }
            KugouLiteMusicProfile.packageName -> {
                KugouLitePlayerActivityHooks.install(classLoader)
                KugouLiteLyrics.install(classLoader)
            }
            KuwoMusicProfile.packageName -> {
                MediaSessionStore.controlReader = KuwoMediaControls
                KuwoNativeData.install(classLoader)
            }
        }
    }

    fun onApplicationCreated(application: Application) {
        if (application.packageName == AppleMusicProfile.packageName) {
            safeHook("Apple Music media controls") {
                MediaSessionStore.controlReader = AppleMusicControls(application.resources)
            }
        }
    }

    fun onActivityReady(activity: Activity) {
        if (activity.packageName == AppleMusicProfile.packageName) AppleMusicPageHooks.observe(activity)
    }

    fun onCoverPlayerUnavailable(activity: Activity) {
        if (activity.packageName == KugouLiteMusicProfile.packageName) {
            KugouLitePlayerActivityHooks.onCoverPlayerUnavailable(activity)
        }
    }

    fun create(profile: MusicAppProfile, activity: Activity, root: ViewGroup): PlayerController =
        when (profile.packageName) {
            QQMusicProfile.packageName -> QQMusicPlayerController(activity.classLoader, root)
            KugouLiteMusicProfile.packageName -> NativeMediaPlayerController(
                profile.displayName, root,
                lyricsProvider = CachedNativeLyricsProvider(KugouLiteLyrics.Reader(activity.classLoader)::read),
                repeatApi = { KugouLiteRepeatApi(activity.classLoader) },
                favoriteControl = KugouLiteFavoriteControl(root, activity.classLoader),
                artworkProvider = PlaylistArtworkProvider(
                    sourceName = "Kugou Lite",
                    source = { KugouLiteArtworkSource(activity.classLoader) },
                    diskCache = ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
                ),
            )
            KuwoMusicProfile.packageName -> KuwoNativeData.Reader(activity.classLoader).let { data ->
                NativeMediaPlayerController(
                    profile.displayName, root,
                    lyricsProvider = CachedNativeLyricsProvider(data::lyrics),
                    repeatApi = { KuwoRepeatApi(activity.classLoader) },
                    artworkReader = { data.artwork(root) },
                    artworkProvider = PlaylistArtworkProvider(
                        sourceName = "Kuwo",
                        source = { KuwoArtworkSource(activity.classLoader) },
                        diskCache = ArtworkDiskCache(File(activity.cacheDir, "musicenhance-artwork")),
                    ),
                )
            }
            AppleMusicProfile.packageName -> AppleMusicPlayerController(activity, root)
            else -> MediaSessionPlayerController(profile.displayName)
        }
}
