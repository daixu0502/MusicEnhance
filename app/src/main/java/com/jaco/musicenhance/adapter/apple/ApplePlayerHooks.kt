package com.jaco.musicenhance.adapter.apple

import android.app.Activity
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.adapter.NativePlayerPage
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.WeakHashMap
import io.github.libxposed.api.XposedInterface

/** Apple Music 6.5.2: audio uses a bottom sheet; video activities are deliberately excluded. */
internal object ApplePlayerHooks {
    private val activities = WeakHashMap<Activity, Boolean>()
    private var playerType: Class<*>? = null
    private var api: AppleSheetApi? = null
    private val installed = mutableSetOf<Executable>()

    fun install(loader: ClassLoader) {
        safeHook("Apple Music audio sheet") {
            val sheetApi = AppleSheetApi(loader)
            api = sheetApi
            playerType = sheetApi.playerType
            // Mini-player data binding calls G(3) directly and never enters PlayerActivity.j1.
            // Intercept the sheet request before its settling animation starts; L also covers drags.
            val requests = listOf(
                sheetApi.request to 0,
                sheetApi.settle to 1,
            )
            for ((method, stateIndex) in requests) {
                hook(method, "musicenhance.apple.sheet.request.${method.name}") { chain ->
                    var handled = false
                    if (chain.args[stateIndex] == 3) safeHook("Apple Music early audio sheet") {
                        val activity = activities.keys.firstOrNull { sheetApi.sheet.get(it) === chain.thisObject }
                        handled = activity?.let(::show) == true
                        if (activity != null) moduleInfo("Apple Music sheet request: method=${method.name}, intercepted=$handled")
                    }
                    if (handled) null else chain.proceed()
                }
            }
            hook(sheetApi.changed, "musicenhance.apple.sheet.state") { chain ->
                val result = chain.proceed()
                safeHook("Apple Music sheet state") {
                    activities.keys.toList().forEach { activity ->
                        if (sheetApi.sheet.get(activity) === chain.thisObject) {
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

    @Synchronized private fun hook(executable: Executable, id: String, hooker: XposedInterface.Hooker) {
        if (executable in installed) return
        module.installHook(executable, id, hooker)
        installed.add(executable)
    }

    fun observe(activity: Activity) {
        if (playerType?.isInstance(activity) != true) return
        activities[activity] = true
        reconcile(activity)
    }

    private fun reconcile(activity: Activity) {
        val sheetApi = api ?: return
        if (sheetApi.isExpanded(activity)) {
            show(activity)
        }
    }

    private fun show(activity: Activity): Boolean {
        val sheetApi = api ?: return false
        val identity = sheetApi.sheet.get(activity) ?: return false
        val pageId = System.identityHashCode(identity)
        activities[activity] = true
        val owner = WeakReference(activity)
        val accepted = PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(pageId, onLaunchFailed = {
            owner.get()?.takeUnless { it.isDestroyed || it.isFinishing }?.let {
                if (!sheetApi.isExpanded(it)) sheetApi.expand.invoke(it, booleanArrayOf(true))
            }
        }) {
            owner.get()?.let {
                sheetApi.collapse.invoke(it, booleanArrayOf(true))
                // An intercepted sheet was already collapsed, so its state callback may never fire.
                PlayerActivityRouter.onNativePageHidden(it, pageId)
            }
            true
        })
        // Swipe/resume fallback may have already expanded the sheet. Collapse it before making the enhanced window translucent,
        // while our Activity opens, rather than exposing a collapsing native player on return.
        if (accepted && sheetApi.state.getInt(identity) != 4) {
            sheetApi.collapse.invoke(activity, booleanArrayOf(true))
        }
        return accepted
    }

    private fun hide(activity: Activity) {
        val identity = api?.sheet?.get(activity) ?: return
        PlayerActivityRouter.onNativePageHidden(activity, System.identityHashCode(identity))
    }
}
