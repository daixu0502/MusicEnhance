package com.jaco.musicenhance.adapter.salt

import android.app.Activity
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.NativePlayerPage
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Salt 12.3.2 uses a Compose sheet, not a dedicated native playback Activity. */
internal object SaltPlayerHooks {
    private val sheets = WeakHashMap<Activity, WeakReference<Any>>()
    private val composingActivity = ThreadLocal<Activity>()
    private var api: SheetApi? = null

    fun install(loader: ClassLoader) = safeHook("Salt player sheet") {
        val sheetApi = SheetApi(loader)
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
        val animate = loader.loadClass("androidx.media3.f52").declaredMethods.single {
            it.name == "\u0781" && it.parameterTypes.size == 4 && it.parameterTypes[0] == sheetApi.stateType
        }
        module.installHook(animate, "musicenhance.salt.sheet.expand") { chain ->
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
        moduleInfo("Salt player sheet route installed (12.3.2)")
    }

    fun observe(activity: Activity) {
        if (!SaltPlayerProfile.isHomeActivity(activity.javaClass.name)) return
        val sheetApi = api ?: return
        val sheet = sheets[activity]?.get() ?: return
        // Covers auto-open on startup and moving an already expanded inner-screen sheet outside.
        if (sheetApi.isExpanded(sheet)) show(activity, sheet, sheetApi)
    }

    private fun show(activity: Activity, sheet: Any, sheetApi: SheetApi): Boolean {
        val root = activity.window.decorView as? ViewGroup ?: return false
        val owner = WeakReference(activity)
        val nativeSheet = WeakReference(sheet)
        val identity = System.identityHashCode(sheet)
        val accepted = PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(
            identity, WeakReference(root),
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

    /** Only the player's enum is intercepted; Compose sheets elsewhere remain untouched. */
    private class SheetApi(loader: ClassLoader) {
        val sheetType: Class<*> = loader.loadClass("androidx.media3.\u07e8")
        val stateType: Class<*> = loader.loadClass("androidx.media3.\u0cbd")
        private val sheetState = sheetType.getField("\u037f")
        private val currentValue = stateType.getField("\u052e")
        private val offsetValue = stateType.getField("\u0588")
        private val setCurrent = stateType.getMethod("\u052d", Any::class.java)
        private val anchors = stateType.getMethod("\u052a")
        private val anchorPosition = loader.loadClass("androidx.media3.mh1").getMethod("\u052b", Any::class.java)
        private val readValue = loader.loadClass("androidx.media3.mi2").getMethod("getValue")
        private val setOffset = loader.loadClass("androidx.media3.ii2").getMethod("setValue", Any::class.java)
        private val values = loader.loadClass("androidx.media3.\u0cb2").enumConstants.orEmpty()
        val expanded: Any = values.single { (it as Enum<*>).name == "Expanded" }
        val collapsed: Any = values.single { (it as Enum<*>).name == "Collapsed" }
        val unit: Any = requireNotNull(loader.loadClass("androidx.media3.fp4").getField("\u037f").get(null))

        fun state(sheet: Any): Any = requireNotNull(sheetState.get(sheet))
        fun isExpanded(sheet: Any) = readValue.invoke(currentValue.get(state(sheet))) === expanded
        fun snap(sheet: Any, value: Any) {
            val state = state(sheet)
            val position = anchorPosition.invoke(anchors.invoke(state), value) as Float
            if (position.isFinite()) setOffset.invoke(offsetValue.get(state), position)
            setCurrent.invoke(state, value)
        }
    }
}
