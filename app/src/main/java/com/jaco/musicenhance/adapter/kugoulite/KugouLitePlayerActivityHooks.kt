package com.jaco.musicenhance.adapter.kugoulite

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.hook.hookEnabled
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** 5.2.9 already declares a separate player Activity with live artwork/favorite controls. */
internal object KugouLitePlayerActivityHooks {
    private val launcher = KugouLitePlayerLauncher()
    private var ready = false

    fun install(loader: ClassLoader) = safeHook("Kugou Lite independent player") {
        val player = loader.loadClass(KugouLitePlayerLauncher.ACTIVITY_NAME)
        val delegate = loader.loadClass("com.kugou.common.base.ViewPagerFrameworkDelegate")
        val fragment = loader.loadClass(KugouLitePlayerLauncher.FRAGMENT_NAME)
        val getActivity = delegate.getMethod("getActivity")
        val navigation = delegate.declaredMethods.single {
            it.name == "Q1" && it.parameterCount == 8 && it.parameterTypes[1] == Class::class.java &&
                it.parameterTypes.last() == Runnable::class.java && it.returnType == Void.TYPE
        }
        // Resolve the complete policy first. Never redirect into an Activity whose landscape or
        // idle-exit hooks failed to install. Original native lifecycles still run and release data.
        val windowPolicies = listOf("w", "C", "A", "v", "handleStatusBarMode").map { player.getDeclaredMethod(it) } +
            player.getDeclaredMethod("onEvent", loader.loadClass("of.a"))
        windowPolicies.forEach { method ->
            hook(method, "policy.${method.name}") { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && ownsWindow(activity)) null else chain.proceed()
            }
        }
        // A queued exit runnable may have been scheduled before the window was taken over.
        hook(player.getDeclaredMethod("x", player), "idle.exit") { chain ->
            val activity = chain.args.firstOrNull() as? Activity
            if (activity != null && ownsWindow(activity)) null else chain.proceed()
        }
        hook(player.getDeclaredMethod("onCreate", Bundle::class.java), "create") { chain ->
            val activity = chain.thisObject as Activity
            val result = chain.proceed()
            if (KugouLitePlayerLauncher.isModuleLaunch(activity)) {
                launcher.onCreated(activity)
                moduleInfo("Kugou Lite independent player created; task=${activity.taskId}")
            }
            result
        }
        hook(player.getDeclaredMethod("onDestroy"), "destroy") { chain ->
            try { chain.proceed() } finally { launcher.onDestroyed(chain.thisObject as Activity) }
        }
        hook(navigation, "navigate") { chain ->
            val destination = chain.args[1] as? Class<*>
            var redirected = false
            if (destination?.name == KugouLitePlayerLauncher.FRAGMENT_NAME && chain.args[6] != true) {
                safeHook("Kugou Lite player navigation") {
                    val activity = getActivity.invoke(chain.thisObject) as? Activity
                    if (activity != null && canRedirect(activity)) redirected = launcher.launch(activity)
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
                                if (canRedirect(activity) && isVisible.invoke(current) == true && launcher.launch(activity)) {
                                    finish.invoke(current)
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
        KugouLiteMusicProfile.isHomeActivity(activity.javaClass.name) &&
        !activity.isFinishing && !activity.isDestroyed && hookEnabled(KugouLiteMusicProfile) &&
        CoverScreenDetector.isCoverScreen(activity)

    private fun ownsWindow(activity: Activity): Boolean = ready &&
        (KugouLitePlayerLauncher.isModuleLaunch(activity) ||
            (hookEnabled(KugouLiteMusicProfile) && CoverScreenDetector.isCoverScreen(activity)))

    fun onCoverPlayerUnavailable(activity: Activity) {
        // A cover-only launch must not expose the native landscape page after unfolding or
        // disabling the module. Finishing restores the existing home task and widget policy.
        if (KugouLitePlayerLauncher.isModuleLaunch(activity) && !activity.isFinishing) activity.finish()
    }

    private fun hook(method: Method, suffix: String, callback: Hooker) {
        method.isAccessible = true
        module.installHook(method, "musicenhance.kugoulite.activity.$suffix", callback)
    }
}
