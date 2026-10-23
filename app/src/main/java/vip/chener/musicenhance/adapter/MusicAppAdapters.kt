package vip.chener.musicenhance.adapter

import android.app.Activity
import android.app.Application
import vip.chener.musicenhance.adapter.apple.ApplePlayerAdapter
import vip.chener.musicenhance.adapter.kugoulite.KugouLitePlayerAdapter
import vip.chener.musicenhance.adapter.kugou.KugouPlayerAdapter
import vip.chener.musicenhance.adapter.kuwo.KuwoPlayerAdapter
import vip.chener.musicenhance.adapter.qq.QQPlayerAdapter
import vip.chener.musicenhance.adapter.salt.SaltPlayerAdapter
import vip.chener.musicenhance.adapter.lxx.LxxPlayerAdapter
import vip.chener.musicenhance.adapter.spotify.SpotifyPlayerAdapter
import vip.chener.musicenhance.player.EnhancedPlayerController

/** Registration only. Every adapter exposes the same hooks and session factory. */
internal object MusicAppAdapters {
    private val adapters = listOf(QQPlayerAdapter, ApplePlayerAdapter, KugouLitePlayerAdapter, KuwoPlayerAdapter, KugouPlayerAdapter, SaltPlayerAdapter, LxxPlayerAdapter, SpotifyPlayerAdapter)
    fun find(packageName: String?) = adapters.firstOrNull { it.profile.packageName == packageName }
    fun installNativeHooks(profile: MusicAppProfile, classLoader: ClassLoader) = find(profile.packageName)?.installHooks(classLoader)
    fun onApplicationCreated(application: Application) = find(application.packageName)?.onApplicationCreated(application)
    fun onActivityReady(activity: Activity) = find(activity.packageName)?.onActivityReady(activity)
    fun create(profile: MusicAppProfile, activity: Activity) =
        EnhancedPlayerController(requireNotNull(find(profile.packageName)).createSession(activity))
}
