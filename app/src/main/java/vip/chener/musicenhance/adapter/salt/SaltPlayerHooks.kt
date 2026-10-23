package vip.chener.musicenhance.adapter.salt

import android.app.Activity
import android.os.Handler
import android.os.Looper
import vip.chener.musicenhance.adapter.NativePlayerPage
import vip.chener.musicenhance.device.CoverScreenDetector
import vip.chener.musicenhance.hook.PlayerActivityRouter
import vip.chener.musicenhance.hook.module
import vip.chener.musicenhance.hook.moduleInfo
import vip.chener.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.WeakHashMap
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import io.github.libxposed.api.XposedInterface

/** Salt uses a Compose sheet, not a dedicated native playback Activity. */
internal object SaltPlayerHooks {
    private val sheets = WeakHashMap<Activity, WeakReference<Any>>()
    private val homes = WeakHashMap<Activity, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val composingActivity = ThreadLocal<Activity>()
    private var api: SaltSheetApi? = null
    private var routeReady = false
    private var recoveryAttempted = false
    private val installed = mutableSetOf<Executable>()

    fun install(loader: ClassLoader) = safeHook("Salt player sheet") {
        val sheetApi = SaltSheetApi(loader)
        api = sheetApi
        val activityType = loader.loadClass("com.salt.music.ui.MainActivity")
        val compose = HostInterfaces.method(loader, "salt.home.player-composition.v1", validate = { it.parameterCount == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType }) {
            methods { matcher { declaredClass = activityType.name; paramCount = 2; usingEqStrings("auto_open_playback_screen") } }
                .map { it.getMethodInstance(loader) }
        }
        hook(compose, "musicenhance.salt.sheet.owner") { chain ->
            composingActivity.set(chain.thisObject as Activity)
            try { chain.proceed() } finally { composingActivity.remove() }
        }
        hook(sheetApi.sheetType.declaredConstructors.single(), "musicenhance.salt.sheet.create") { chain ->
            val result = chain.proceed()
            composingActivity.get()?.let { activity ->
                chain.thisObject?.let { sheets[activity] = WeakReference(it) }
            }
            result
        }
        // All tap/drag expansion requests converge here, before the native settling animation.
        hook(sheetApi.animate, "musicenhance.salt.sheet.expand") { chain ->
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
        routeReady = true
        homes.keys.toList().forEach(::recoverUnboundHome)
        moduleInfo("Salt player sheet route installed (discovered)")
    }

    @Synchronized private fun hook(executable: Executable, id: String, hooker: XposedInterface.Hooker) {
        if (executable in installed) return
        module.installHook(executable, id, hooker)
        installed.add(executable)
    }

    fun observe(activity: Activity) {
        if (!SaltPlayerProfile.isHomeActivity(activity.javaClass.name)) return
        homes.putIfAbsent(activity, false)
        val sheetApi = api ?: return
        val sheet = sheets[activity]?.get() ?: run { recoverUnboundHome(activity); return }
        // Covers auto-open on startup and moving an already expanded inner-screen sheet outside.
        if (sheetApi.isExpanded(sheet)) show(activity, sheet, sheetApi)
    }

    private fun recoverUnboundHome(activity: Activity) {
        if (!routeReady || recoveryAttempted || homes[activity] != false || sheets[activity]?.get() != null) return
        val owner = WeakReference(activity)
        main.post {
            val home = owner.get() ?: return@post
            if (recoveryAttempted || homes[home] != false || sheets[home]?.get() != null || home.isFinishing || home.isDestroyed ||
                !home.hasWindowFocus() || !CoverScreenDetector.isCoverScreen(home)) return@post
            // A first APK scan can finish after Compose remembered its sheet. Rebuild this
            // foreground page once with saved state, so the installed owner hook sees creation.
            // Cached starts bind normally; never loop if a changed host no longer binds.
            homes[home] = true
            recoveryAttempted = true
            safeHook("Salt first-scan page recovery") {
                moduleInfo("Salt rebuilding unbound home after interface discovery")
                home.recreate()
            }
        }
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
