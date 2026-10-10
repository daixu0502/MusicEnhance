package com.jaco.musicenhance.adapter.salt

import android.app.Activity
import com.jaco.musicenhance.adapter.NativePlayerPage
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Salt uses a Compose sheet, not a dedicated native playback Activity. */
internal object SaltPlayerHooks {
    private val sheets = WeakHashMap<Activity, WeakReference<Any>>()
    private val composingActivity = ThreadLocal<Activity>()
    private var api: SaltSheetApi? = null

    fun install(loader: ClassLoader) = safeHook("Salt player sheet") {
        val sheetApi = SaltSheetApi(loader)
        api = sheetApi
        val activityType = loader.loadClass("com.salt.music.ui.MainActivity")
        val compose = activityType.declaredMethods.single {
            it.name == "\u078f" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        module.installHook(compose, "musicenhance.salt.sheet.owner") { chain ->
            composingActivity.set(chain.thisObject as Activity)
            try { chain.proceed() } finally { composingActivity.remove() }
        }
        module.installHook(sheetApi.sheetType.declaredConstructors.single(), "musicenhance.salt.sheet.create") { chain ->
            val result = chain.proceed()
            composingActivity.get()?.let { activity ->
                chain.thisObject?.let { sheets[activity] = WeakReference(it) }
            }
            result
        }
        // All tap/drag expansion requests converge here, before the native settling animation.
        module.installHook(sheetApi.animate, "musicenhance.salt.sheet.expand") { chain ->
            var intercepted = false
            if (chain.args[1] === sheetApi.expanded) safeHook("Salt early player launch") {
                val owner = sheets.entries.firstOrNull { (_, reference) ->
                    reference.get()?.let(sheetApi::state) === chain.args[0]
                }
                val sheet = owner?.value?.get()
                if (owner != null && sheet != null) intercepted = show(owner.key, sheet, sheetApi)
            }
            if (intercepted) sheetApi.unit else chain.proceed()
        }
        moduleInfo("Salt player sheet route installed (${sheetApi.contract.version})")
    }

    fun observe(activity: Activity) {
        if (!SaltPlayerProfile.isHomeActivity(activity.javaClass.name)) return
        val sheetApi = api ?: return
        val sheet = sheets[activity]?.get() ?: return
        // Covers auto-open on startup and moving an already expanded inner-screen sheet outside.
        if (sheetApi.isExpanded(sheet)) show(activity, sheet, sheetApi)
    }

    private fun show(activity: Activity, sheet: Any, sheetApi: SaltSheetApi): Boolean {
        val owner = WeakReference(activity)
        val nativeSheet = WeakReference(sheet)
        val identity = System.identityHashCode(sheet)
        val accepted = PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(
            identity,
            onLaunchFailed = {
                nativeSheet.get()?.let { sheetApi.snap(it, sheetApi.expanded) }
                owner.get()?.let { PlayerActivityRouter.onNativePageHidden(it, identity) }
            },
            dismiss = {
                nativeSheet.get()?.let { sheetApi.snap(it, sheetApi.collapsed) }
                owner.get()?.let { PlayerActivityRouter.onNativePageHidden(it, identity) }
                true
            },
        ))
        if (accepted) sheetApi.snap(sheet, sheetApi.collapsed)
        else PlayerActivityRouter.onNativePageHidden(activity, identity)
        return accepted
    }

}
