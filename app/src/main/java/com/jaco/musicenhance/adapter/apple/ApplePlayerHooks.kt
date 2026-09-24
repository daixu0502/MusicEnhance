package com.jaco.musicenhance.adapter.apple

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.hook.moduleInfo
import android.view.View
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.adapter.NativePlayerPage
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Apple Music 6.5.2: audio uses a bottom sheet; video activities are deliberately excluded. */
internal object ApplePlayerHooks {
    private val activities = WeakHashMap<Activity, Boolean>()
    private var playerType: Class<*>? = null

    fun install(loader: ClassLoader) {
        safeHook("Apple Music audio sheet") {
            val type = loader.loadClass("com.apple.android.music.common.activity.PlayerActivity")
            val sheetType = loader.loadClass("com.google.android.material.bottomsheet.BottomSheetBehavior")
            // H is setStateInternal in this APK. This also covers swipe expansion/collapse.
            val stateChanged = sheetType.getDeclaredMethod("H", Int::class.javaPrimitiveType)
            type.getMethod("o1")
            type.getMethod("h1", BooleanArray::class.java)
            type.getMethod("m1")
            type.getField("a1")
            playerType = type
            // Mini-player data binding calls G(3) directly and never enters PlayerActivity.j1.
            // Intercept the sheet request before its settling animation starts; L also covers drags.
            val requests = listOf(
                sheetType.getMethod("G", Int::class.javaPrimitiveType) to 0,
                sheetType.getMethod("L", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) to 1,
            )
            for ((method, stateIndex) in requests) {
                module.installHook(method, "musicenhance.apple.sheet.request.${method.name}") { chain ->
                    var handled = false
                    if (chain.args[stateIndex] == 3) safeHook("Apple Music early audio sheet") {
                        val activity = activities.keys.firstOrNull { type.getField("a1").get(it) === chain.thisObject }
                        handled = activity?.let(::show) == true
                        if (activity != null) moduleInfo("Apple Music sheet request: method=${method.name}, intercepted=$handled")
                    }
                    if (handled) null else chain.proceed()
                }
            }
            module.installHook(stateChanged, "musicenhance.apple.sheet.state") { chain ->
                val result = chain.proceed()
                safeHook("Apple Music sheet state") {
                    activities.keys.toList().forEach { activity ->
                        if (type.getField("a1").get(activity) === chain.thisObject) {
                            // Don't dismiss the pending launch during STATE_SETTLING/DRAGGING.
                            when (chain.args[0] as? Int) {
                                3 -> show(activity)
                                4, 5 -> hide(activity)
                            }
                        }
                    }
                }
                result
            }
        }
    }

    fun observe(activity: Activity) {
        if (playerType?.isInstance(activity) != true) return
        activities[activity] = true
        reconcile(activity)
    }

    private fun reconcile(activity: Activity) {
        val type = playerType ?: return
        if (type.getMethod("o1").invoke(activity) == true) {
            show(activity)
        }
    }

    private fun show(activity: Activity): Boolean {
        val type = playerType ?: return false
        val identity = type.getField("a1").get(activity) ?: return false
        val pageId = System.identityHashCode(identity)
        activities[activity] = true
        val owner = WeakReference(activity)
        val root = type.getMethod("m1").invoke(activity) as? ViewGroup ?: return false
        val accepted = PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(pageId, WeakReference(root), onLaunchFailed = {
            owner.get()?.takeUnless { it.isDestroyed || it.isFinishing }?.let {
                if (type.getMethod("o1").invoke(it) != true) type.getMethod("j1", BooleanArray::class.java).invoke(it, booleanArrayOf(true))
            }
        }) {
            owner.get()?.let {
                type.getMethod("h1", BooleanArray::class.java).invoke(it, booleanArrayOf(true))
                // An intercepted sheet was already collapsed, so its state callback may never fire.
                PlayerActivityRouter.onNativePageHidden(it, pageId)
            }
            true
        })
        // Swipe/resume fallback may have already expanded the sheet. Collapse it behind the shield,
        // while our Activity opens, rather than exposing a collapsing native player on return.
        if (accepted && identity.javaClass.getField("G").getInt(identity) != 4) {
            type.getMethod("h1", BooleanArray::class.java).invoke(activity, booleanArrayOf(true))
        }
        return accepted
    }

    private fun hide(activity: Activity) {
        val identity = playerType?.getField("a1")?.get(activity) ?: return
        PlayerActivityRouter.onNativePageHidden(activity, System.identityHashCode(identity))
    }
}
