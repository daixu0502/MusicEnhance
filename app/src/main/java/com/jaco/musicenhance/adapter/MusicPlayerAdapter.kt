package com.jaco.musicenhance.adapter

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import com.jaco.musicenhance.player.PlayerSession

/** Uniform entry points; native hooks and data never escape an app's adapter directory. */
internal interface MusicPlayerAdapter {
    val profile: MusicAppProfile
    fun installHooks(loader: ClassLoader) {}
    fun onApplicationCreated(application: Application) {}
    fun onActivityReady(activity: Activity) {}
    fun onPlayerUnavailable(activity: Activity) {}
    fun createSession(activity: Activity, nativeRoot: ViewGroup): PlayerSession
}
