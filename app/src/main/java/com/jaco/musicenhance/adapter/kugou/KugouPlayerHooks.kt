package com.jaco.musicenhance.adapter.kugou

import android.app.Activity
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference

/** Redirect the navigation request; neither native player Fragment nor flip Activity is created. */
internal object KugouPlayerHooks {
    private val playerFragments = setOf("com.kugou.android.app.player.PlayerFragment", "com.kugou.android.app.nplayer.NPlayerFragment")
    private var home = WeakReference<Activity>(null)
    fun homeActivity() = home.get()?.takeUnless { it.isDestroyed || it.isFinishing }
    fun observe(activity: Activity) {
        if (KugouPlayerProfile.isHomeActivity(activity.javaClass.name)) home = WeakReference(activity)
    }

    fun install(loader: ClassLoader) = safeHook("Kugou direct player navigation") {
        val type = loader.loadClass("com.kugou.common.base.ViewPagerFrameworkDelegate")
        val activity = type.getMethod("H")
        val navigations = type.declaredMethods.filter {
            it.name == "i1" && !it.isBridge && it.parameterCount == 7 && it.parameterTypes[1] == Class::class.java
        }
        check(navigations.isNotEmpty()) { "Kugou navigation signature unavailable" }
        navigations.forEach { navigation ->
            module.installHook(navigation, "musicenhance.kugou.navigate.${navigation.parameterTypes[0].name}") { chain ->
                var redirected = false
                if ((chain.args[1] as? Class<*>)?.name in playerFragments && chain.args[6] != true) {
                    safeHook("Kugou player navigation") {
                        val source = activity.invoke(chain.thisObject) as? Activity
                        if (source != null) {
                            observe(source)
                            val receiver = WeakReference(chain.thisObject)
                            val arguments = chain.args.toTypedArray()
                            redirected = PlayerActivityRouter.launch(source) {
                                receiver.get()?.let { navigation.invoke(it, *arguments) }
                            }
                        }
                    }
                }
                if (redirected) null else chain.proceed()
            }
        }
        moduleInfo("Kugou direct enhanced navigation installed")
    }
}
