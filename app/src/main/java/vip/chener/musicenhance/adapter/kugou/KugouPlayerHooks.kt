package vip.chener.musicenhance.adapter.kugou

import android.app.Activity
import vip.chener.musicenhance.hook.PlayerActivityRouter
import vip.chener.musicenhance.hook.module
import vip.chener.musicenhance.hook.moduleInfo
import vip.chener.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** Redirect the navigation request; neither native player Fragment nor flip Activity is created. */
internal object KugouPlayerHooks {
    private val playerFragments = setOf("com.kugou.android.app.player.PlayerFragment", "com.kugou.android.app.nplayer.NPlayerFragment")
    private var home = WeakReference<Activity>(null)
    private val installed = mutableSetOf<Method>()
    fun homeActivity() = home.get()?.takeUnless { it.isDestroyed || it.isFinishing }
    fun observe(activity: Activity) {
        if (KugouPlayerProfile.isHomeActivity(activity.javaClass.name)) home = WeakReference(activity)
    }

    fun install(loader: ClassLoader) = safeHook("Kugou direct player navigation") {
        val activity = KugouInterfaceRules.activity(loader)
        val navigation = KugouInterfaceRules.navigation(loader)
        if (navigation !in installed) {
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
            installed += navigation
        }
        moduleInfo("Kugou direct enhanced navigation installed")
    }
}
