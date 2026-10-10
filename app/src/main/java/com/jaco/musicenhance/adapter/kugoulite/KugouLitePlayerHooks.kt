package com.jaco.musicenhance.adapter.kugoulite

import android.app.Activity
import android.view.View
import com.jaco.musicenhance.adapter.NativePlayerPage
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.hook.hookEnabled
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Intercept 5.2.9 player navigation before its Fragment or landscape Activity is created. */
internal object KugouLitePlayerHooks {
    private const val FRAGMENT_NAME = "com.kugou.android.app.player.YoungPlayerFragment"
    private var ready = false

    private var home = WeakReference<Activity>(null)
    fun homeActivity() = home.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    fun observe(activity: Activity) {
        if (KugouLitePlayerProfile.isHomeActivity(activity.javaClass.name)) home = WeakReference(activity)
    }

    fun install(loader: ClassLoader) = safeHook("Kugou Lite direct player") {
        val delegate = loader.loadClass("com.kugou.common.base.ViewPagerFrameworkDelegate")
        val fragment = loader.loadClass(FRAGMENT_NAME)
        val getActivity = delegate.getMethod("getActivity")
        val navigation = delegate.declaredMethods.single {
            it.name == "Q1" && it.parameterCount == 8 && it.parameterTypes[1] == Class::class.java &&
                it.parameterTypes.last() == Runnable::class.java && it.returnType == Void.TYPE
        }
        hook(navigation, "navigate") { chain ->
            val destination = chain.args[1] as? Class<*>
            var redirected = false
            if (destination?.name == FRAGMENT_NAME && chain.args[6] != true) {
                safeHook("Kugou Lite player navigation") {
                    val activity = getActivity.invoke(chain.thisObject) as? Activity
                    if (activity != null && canRedirect(activity)) {
                        observe(activity)
                        val receiver = WeakReference(chain.thisObject)
                        val arguments = chain.args.toTypedArray().also { it[7] = null }
                        redirected = PlayerActivityRouter.launch(activity) {
                            receiver.get()?.let { navigation.invoke(it, *arguments) }
                        }
                    }
                }
            }
            if (redirected) {
                // Q1 is also used by the delegate's navigation queue; release its completion token.
                safeHook("Kugou Lite navigation completion") { (chain.args[7] as? Runnable)?.run() }
                moduleInfo("Kugou Lite player navigation redirected to independent Activity")
                null
            } else chain.proceed()
        }
        installRestoredFragmentRoute(fragment)
        ready = true
        moduleInfo("Kugou Lite independent player route installed")
    }

    /** Restored pages and an inner-screen player folded onto the cover may bypass navigation. */
    private fun installRestoredFragmentRoute(fragment: Class<*>) {
        val pendingTransfers = WeakHashMap<Any, Boolean>()
        val getActivity = fragment.getMethod("getActivity")
        val getView = fragment.getMethod("getView")
        val finish = fragment.getMethod("finish")
        val isVisible = fragment.getDeclaredMethod("Vd").apply { isAccessible = true }
        val callbacks = fragment.declaredMethods.filter {
            it.name in setOf("onResume", "onFragmentResume", "onConfigurationChanged")
        }
        callbacks.forEach { method ->
            hook(method, "restored.${method.name}") { chain ->
                val result = chain.proceed()
                safeHook("Kugou Lite restored player") {
                    val instance = chain.thisObject ?: return@safeHook
                    val activity = getActivity.invoke(instance) as? Activity ?: return@safeHook
                    if (pendingTransfers[instance] == true || !canRedirect(activity) || isVisible.invoke(instance) != true) return@safeHook
                    val root = getView.invoke(instance) as? View ?: return@safeHook
                    val reference = WeakReference(instance)
                    pendingTransfers[instance] = true
                    // Finish the native fragment after its lifecycle callback has completed.
                    val posted = root.post {
                        safeHook("Kugou Lite restored player transfer") {
                            val current = reference.get() ?: return@safeHook
                            try {
                                if (canRedirect(activity) && isVisible.invoke(current) == true) {
                                    val identity = System.identityHashCode(current)
                                    val owner = WeakReference(activity)
                                    PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(
                                        identity, dismiss = {
                                            reference.get()?.let { finish.invoke(it) }
                                            owner.get()?.let { PlayerActivityRouter.onNativePageHidden(it, identity) }
                                            true
                                        },
                                    ))
                                }
                            } finally {
                                // YoungPlayerFragment is cached by the host and may be reused later.
                                pendingTransfers.remove(current)
                            }
                        }
                    }
                    if (!posted) pendingTransfers.remove(instance)
                }
                result
            }
        }
    }

    private fun canRedirect(activity: Activity): Boolean = ready &&
        KugouLitePlayerProfile.isHomeActivity(activity.javaClass.name) &&
        !activity.isFinishing && !activity.isDestroyed && !PlayerActivityRouter.isNativeFallback(activity) && hookEnabled(KugouLitePlayerProfile) &&
        CoverScreenDetector.isCoverScreen(activity)

    private fun hook(method: Method, suffix: String, callback: Hooker) {
        method.isAccessible = true
        module.installHook(method, "musicenhance.kugoulite.activity.$suffix", callback)
    }
}
