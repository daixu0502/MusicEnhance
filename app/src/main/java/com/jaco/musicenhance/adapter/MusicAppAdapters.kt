package com.jaco.musicenhance.adapter

import android.app.Activity
import android.app.Application
import com.jaco.musicenhance.adapter.apple.ApplePlayerAdapter
import com.jaco.musicenhance.adapter.kugoulite.KugouLitePlayerAdapter
import com.jaco.musicenhance.adapter.kugou.KugouPlayerAdapter
import com.jaco.musicenhance.adapter.kuwo.KuwoPlayerAdapter
import com.jaco.musicenhance.adapter.qq.QQPlayerAdapter
import com.jaco.musicenhance.adapter.salt.SaltPlayerAdapter
import com.jaco.musicenhance.adapter.lxx.LxxPlayerAdapter
import com.jaco.musicenhance.adapter.spotify.SpotifyPlayerAdapter
import com.jaco.musicenhance.player.EnhancedPlayerController

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
