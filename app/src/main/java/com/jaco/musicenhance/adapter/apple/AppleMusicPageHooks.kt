package com.jaco.musicenhance.adapter.apple

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.CoverPlayerInjector
import com.jaco.musicenhance.player.EmbeddedPlayerPage
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Apple Music 6.5.2: audio uses a bottom sheet; video activities are deliberately excluded. */
internal object AppleMusicPageHooks {
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
            module.installHook(type.getMethod("j1", BooleanArray::class.java), "musicenhance.apple.sheet.expand", Hooker { chain ->
                safeHook("Apple Music early audio sheet") {
                    (chain.thisObject as? Activity)?.let { show(it) }
                }
                chain.proceed()
            })
            module.installHook(stateChanged, "musicenhance.apple.sheet.state", Hooker { chain ->
                val result = chain.proceed()
                safeHook("Apple Music sheet state") {
                    activities.keys.toList().forEach { activity ->
                        if (type.getField("a1").get(activity) === chain.thisObject) {
                            // Don't dismiss the early overlay during STATE_SETTLING/DRAGGING.
                            when (chain.args[0] as? Int) {
                                3 -> show(activity)
                                4, 5 -> hide(activity)
                            }
                        }
                    }
                }
                result
            })
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

    private fun show(activity: Activity) {
        val type = playerType ?: return
        val identity = type.getField("a1").get(activity) ?: return
        val pageId = System.identityHashCode(identity)
        activities[activity] = true
        val owner = WeakReference(activity)
        val root = type.getMethod("m1").invoke(activity) as? ViewGroup ?: return
        CoverPlayerInjector.showEmbeddedPage(activity, EmbeddedPlayerPage(pageId, WeakReference(root)) {
            owner.get()?.let { type.getMethod("h1", BooleanArray::class.java).invoke(it, booleanArrayOf(true)) == true } ?: false
        })
    }

    private fun hide(activity: Activity) {
        val identity = playerType?.getField("a1")?.get(activity) ?: return
        CoverPlayerInjector.hideEmbeddedPage(activity, System.identityHashCode(identity))
    }
}
