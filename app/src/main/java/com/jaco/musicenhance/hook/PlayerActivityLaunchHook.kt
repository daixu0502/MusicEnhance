package com.jaco.musicenhance.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.adapter.MusicAppProfile
import com.jaco.musicenhance.player.EnhancedPlayerActivity
import com.jaco.musicenhance.player.PlayerActivitySessions

/** Android must resolve a declared host component before Instrumentation can supply our Activity. */
internal object PlayerActivityLaunchHook {
    var ready = false
        private set
    private val hookedFactories = mutableSetOf<java.lang.reflect.Method>()

    fun install(instrumentation: Instrumentation? = null) = safeHook("enhanced player Activity factory") {
        val method = (instrumentation?.javaClass ?: Instrumentation::class.java)
            .getMethod("newActivity", ClassLoader::class.java, String::class.java, Intent::class.java)
        if (method in hookedFactories) return@safeHook
        module.installHook(
            method,
            "musicenhance.player.activity.factory.${method.declaringClass.name}",
        ) { chain ->
            val name = chain.args[1] as? String
            val intent = chain.args[2] as? Intent
            if (isPlayerIntent(intent, name)) EnhancedPlayerActivity() else chain.proceed()
        }
        hookedFactories += method
        ready = true
    }

    internal fun isPlayerIntent(intent: Intent?, className: String?): Boolean =
        intent?.action == PlayerActivitySessions.ACTION &&
            intent.component?.className == className &&
            !intent.getStringExtra(PlayerActivitySessions.EXTRA_SESSION).isNullOrBlank() &&
            MusicAppRegistry.find(intent.component?.packageName)?.ownsActivity(className) == true

    fun component(source: Activity): ComponentName? {
        val profile = MusicAppRegistry.find(source.packageName) ?: return null
        val manager = source.packageManager
        val own = manager.getActivityInfo(source.componentName, PackageManager.ComponentInfoFlags.of(0))
        fun usable(info: ActivityInfo) = isUsableSlot(info, own, profile)
        if (usable(own)) return source.componentName
        // Do not borrow a home component: vendor widget queries are scoped by component name.
        // Single-task Activities also cannot create a second independent instance.
        // Select a standard slot from this installed APK; its native class is never instantiated.
        val candidates = manager.getPackageInfo(source.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())).activities.orEmpty()
        return candidates.firstOrNull { usable(it) && it.permission == null }
            ?.let { ComponentName(source.packageName, it.name) }
    }

    internal fun isUsableSlot(info: ActivityInfo, source: ActivityInfo, profile: MusicAppProfile): Boolean =
        info.enabled && info.packageName == profile.packageName && info.processName == source.processName &&
            info.launchMode == ActivityInfo.LAUNCH_MULTIPLE && info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_NONE &&
            info.targetActivity == null && info.flags and ActivityInfo.FLAG_NO_HISTORY == 0 &&
            profile.ownsActivity(info.name) && !profile.isHomeActivity(info.name) && info.taskAffinity == source.taskAffinity
}
