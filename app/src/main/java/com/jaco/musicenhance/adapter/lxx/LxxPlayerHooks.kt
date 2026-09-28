package com.jaco.musicenhance.adapter.lxx

import android.app.Activity
import android.os.Looper
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.NativePlayerPage
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.hook.hookEnabled
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** 26.09.20 RNN: intercept only PlayDetailScreen, on the navigation module's UI-thread path. */
internal object LxxPlayerHooks {
    private var installedApi: Api? = null
    private val pages = WeakHashMap<Activity, Page>()
    private var restoring = false

    private class Page(val bridge: WeakReference<Any>, val id: String, val arguments: Array<Any?>) {
        var nativeShown = false
        var popRequested = false
    }

    fun install(loader: ClassLoader) = safeHook("LX-X player navigation") {
        val api = Api(loader)
        module.installHook(api.push, "musicenhance.lxx.navigation.push") { chain ->
            var intercepted = false
            safeHook("LX-X player entry") {
                val node = chain.args.firstOrNull() ?: return@safeHook
                if (!isPlayerLayout(api.nodeData.get(node) as JSONObject) || Looper.myLooper() != Looper.getMainLooper()) return@safeHook
                val bridge = chain.thisObject ?: return@safeHook
                val activity = api.activity.invoke(bridge) as? Activity ?: return@safeHook
                if (!LxxPlayerProfile.isHomeActivity(activity.javaClass.name)) return@safeHook
                val page = Page(WeakReference(bridge), api.nodeId.get(node) as String, chain.args.toTypedArray().apply { this[3] = null })
                pages[activity] = page
                if (!restoring && canShow(activity) && show(activity, page, api)) {
                    intercepted = true
                    // Match RNN's normal completion protocol, including its JS push lock release.
                    api.completePush(bridge, page.id, chain.args[2] as String, chain.args[3])
                    moduleInfo("LX-X PlayDetailScreen redirected to enhanced Activity")
                } else page.nativeShown = true
            }
            if (intercepted) null else chain.proceed()
        }
        module.installHook(api.appeared, "musicenhance.lxx.navigation.appeared") { chain ->
            val result = chain.proceed()
            safeHook("LX-X visible native component") {
                val context = api.eventContext.get(chain.thisObject)
                val activity = api.contextActivity.invoke(context) as? Activity ?: return@safeHook
                val page = pages[activity] ?: return@safeHook
                val name = chain.args[1] as? String ?: return@safeHook
                if (name.startsWith("lxm.")) {
                    page.nativeShown = name == "lxm.PlayDetailScreen" && page.id == chain.args[0]
                    if (page.nativeShown) observe(activity)
                    else if (!page.popRequested) {
                        pages.remove(activity)
                        PlayerActivityRouter.onNativePageHidden(activity, page.id)
                    }
                }
            }
            result
        }
        installedApi = api
        moduleInfo("LX-X native player route installed (26.09.20)")
    }

    fun observe(activity: Activity) {
        val api = installedApi ?: return
        val page = pages[activity] ?: return
        if (page.nativeShown && !page.popRequested && canShow(activity)) show(activity, page, api)
    }

    private fun canShow(activity: Activity) = !activity.isFinishing && !activity.isDestroyed &&
        hookEnabled(LxxPlayerProfile) && CoverScreenDetector.isCoverScreen(activity)

    private fun show(activity: Activity, page: Page, api: Api): Boolean {
        val owner = WeakReference(activity)
        val root = activity.window.decorView as? ViewGroup ?: return false
        val accepted = PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(
            page.id, WeakReference(root),
            onLaunchFailed = {
                owner.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { current ->
                    PlayerActivityRouter.onNativePageHidden(current, page.id)
                    if (!page.nativeShown) page.bridge.get()?.let { bridge ->
                        restoring = true
                        try {
                            // The original promise was already resolved by the intercepted push.
                            val args = page.arguments.copyOf().apply { this[3] = null }
                            api.push.invoke(bridge, *args)
                        } finally { restoring = false }
                    }
                }
            },
            dismiss = {
                val current = owner.get()
                if (current == null || current.isDestroyed) true else {
                    val bridge = page.bridge.get()
                    if (page.nativeShown && bridge == null) false else {
                        if (page.nativeShown && !page.popRequested) {
                            // Inner-screen/restored native page: remove it behind the common shield.
                            page.popRequested = true
                            try {
                                api.pop.invoke(bridge, page.id, api.createPopOptions(), "musicenhance.lxx.pop",
                                    api.promise { success ->
                                        page.popRequested = false
                                        if (success) page.nativeShown = false
                                        else moduleInfo("LX-X native page pop rejected; exit remains retryable")
                                    })
                            } catch (error: Exception) {
                                page.popRequested = false
                                throw error
                            }
                        }
                        // RNN's disabled-animation pop normally resolves synchronously. Never
                        // finish on rejection or while the native stack is still changing.
                        if (page.nativeShown || page.popRequested) false else {
                            pages.remove(current)
                            PlayerActivityRouter.onNativePageHidden(current, page.id)
                            true
                        }
                    }
                }
            },
        ))
        if (!accepted) PlayerActivityRouter.onNativePageHidden(activity, page.id)
        return accepted
    }

    internal fun isPlayerLayout(data: JSONObject) = data.optString("name") == "lxm.PlayDetailScreen"

    private class Api(loader: ClassLoader) {
        private val bridgeType = loader.loadClass("com.reactnativenavigation.react.NavigationModule")
        private val promiseType = loader.loadClass("com.facebook.react.bridge.Promise")
        private val readableMap = loader.loadClass("com.facebook.react.bridge.ReadableMap")
        val push = bridgeType.declaredMethods.single {
            it.name == "lambda\$push\$3" && it.parameterCount == 4 && it.parameterTypes.last() == promiseType
        }.apply { isAccessible = true }
        val nodeData = push.parameterTypes[0].declaredFields.single { it.type == JSONObject::class.java }.apply { isAccessible = true }
        val nodeId = push.parameterTypes[0].declaredFields.single { it.type == String::class.java }.apply { isAccessible = true }
        val activity = loader.loadClass("com.facebook.react.bridge.ReactContextBaseJavaModule")
            .getDeclaredMethod("getCurrentActivity").apply { isAccessible = true }
        val pop = bridgeType.getDeclaredMethod("lambda\$pop\$5", String::class.java, readableMap, String::class.java, promiseType)
            .apply { isAccessible = true }
        private val eventEmitter = bridgeType.getDeclaredField("eventEmitter").apply { isAccessible = true }
        val appeared = eventEmitter.type.declaredMethods.single { it.name == "f" && it.parameterCount == 3 && it.parameterTypes[0] == String::class.java }
        val eventContext = eventEmitter.type.declaredFields.single { android.content.Context::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
        val contextActivity = eventContext.type.getMethod("getCurrentActivity")
        private val now = bridgeType.getDeclaredField("now").apply { isAccessible = true }
        private val completion = loader.loadClass("com.reactnativenavigation.react.k")
        private val completionConstructor = completion.getConstructor(String::class.java, String::class.java, promiseType, eventEmitter.type, now.type)
        private val completed = completion.getMethod("a", String::class.java)
        private val makeNativeMap = loader.loadClass("com.facebook.react.bridge.Arguments")
            .getMethod("makeNativeMap", Map::class.java)

        // Package-ready runs before Application.onCreate/SoLoader.init. Invoking this JNI
        // factory then permanently poisons NativeMap's class initialization for the process.
        // Only construct options when closing an existing RN page, after its runtime is ready.
        fun createPopOptions(): Any = requireNotNull(makeNativeMap.invoke(null,
            mapOf("animations" to mapOf("pop" to mapOf("enabled" to false)))))

        fun promise(completed: (Boolean) -> Unit): Any = Proxy.newProxyInstance(promiseType.classLoader, arrayOf(promiseType)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "MusicEnhance LX-X navigation completion"
                "reject" -> { completed(false); null }
                "resolve" -> { completed(true); null }
                else -> null
            }
        }

        fun completePush(bridge: Any, id: String, command: String, promise: Any?) {
            val listener = completionConstructor.newInstance("push", command, promise, eventEmitter.get(bridge), now.get(bridge))
            completed.invoke(listener, id)
        }
    }
}
