package com.jaco.musicenhance.adapter.kugou

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.hook.hookEnabled
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference

/** Use the host's declared flip Activity as data context; the common router owns the enhanced window. */
internal object KugouPlayerHooks {
    private const val MODULE_LAUNCH = "com.jaco.musicenhance.KUGOU_COVER_PLAYER"
    private val playerFragments = setOf("com.kugou.android.app.player.PlayerFragment", "com.kugou.android.app.nplayer.NPlayerFragment")
    private var pendingAtMs: Long? = null
    private var player = WeakReference<Activity>(null)

    fun install(loader: ClassLoader) {
        safeHook("Kugou flip activity lifecycle") {
            val type = loader.loadClass(KUGOU_FLIP_ACTIVITY)
            module.installHook(type.getDeclaredMethod("onCreate", Bundle::class.java), "musicenhance.kugou.create") { chain ->
                val result = chain.proceed()
                player = WeakReference(chain.thisObject as Activity)
                pendingAtMs = null
                result
            }
            module.installHook(type.getDeclaredMethod("onDestroy"), "musicenhance.kugou.destroy") { chain ->
                try { chain.proceed() } finally { if (player.get() === chain.thisObject) player.clear() }
            }
        }
        safeHook("Kugou early player navigation") {
            val type = loader.loadClass("com.kugou.common.base.ViewPagerFrameworkDelegate")
            val activity = type.getMethod("H")
            val navigation = type.declaredMethods.single {
                it.name == "i1" && it.parameterCount == 7 && it.parameterTypes[1] == Class::class.java
            }
            module.installHook(navigation, "musicenhance.kugou.navigate") { chain ->
                var redirected = false
                if ((chain.args[1] as? Class<*>)?.name in playerFragments && chain.args[6] != true) {
                    safeHook("Kugou player navigation") {
                        val source = activity.invoke(chain.thisObject) as? Activity
                        if (source != null && canRedirect(source)) redirected = launch(source)
                    }
                }
                if (redirected) null else chain.proceed()
            }
            moduleInfo("Kugou 20.8.2 independent player route installed")
        }
    }

    private fun canRedirect(activity: Activity) = activity.packageName == KugouPlayerProfile.packageName &&
        KugouPlayerProfile.isHomeActivity(activity.javaClass.name) && !activity.isFinishing && !activity.isDestroyed &&
        hookEnabled(KugouPlayerProfile) && CoverScreenDetector.isCoverScreen(activity)

    private fun launch(source: Activity): Boolean {
        if (player.get()?.let { !it.isFinishing && !it.isDestroyed } == true) return true
        val nowMs = SystemClock.elapsedRealtime()
        if (pendingAtMs?.let { nowMs - it < 2_000L } == true) return true
        pendingAtMs = nowMs
        try {
            source.startActivity(Intent().setClassName(KugouPlayerProfile.packageName, KUGOU_FLIP_ACTIVITY)
                .putExtra(MODULE_LAUNCH, true))
        } catch (error: Exception) { pendingAtMs = null; throw error }
        return true
    }

    fun onPlayerUnavailable(activity: Activity) {
        if (activity.intent?.getBooleanExtra(MODULE_LAUNCH, false) == true && !activity.isFinishing) activity.finish()
    }
}
