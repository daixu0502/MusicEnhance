package com.jaco.musicenhance.hook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IInterface

/** Runs entirely in system_server; FlipHome receives its existing MIUI observer callback. */
internal object SystemCoverWidgetHook {
    private const val FLIP_HOME_PACKAGE = "com.miui.fliphome"
    private const val OBSERVER_TYPE = "android.app.IMiuiActivityObserver"
    // UserHandle.PER_USER_RANGE is hidden from the public SDK.
    private const val PER_USER_UID_RANGE = 100_000
    private val observers = CoverActivityObservers()
    private val worker by lazy {
        Handler(HandlerThread("MusicEnhance-cover-policy").apply { isDaemon = true; start() }.looper)
    }

    // HyperOS exposes no public widget ownership API; capture only FlipHome's observer via LSPosed.
    @SuppressLint("PrivateApi")
    fun install(loader: ClassLoader) = safeHook("system cover widget observer") {
        val observerType = loader.loadClass(OBSERVER_TYPE)
        val proxyType = loader.loadClass("$OBSERVER_TYPE\$Stub\$Proxy")
        val resumed = observerType.getMethod("activityResumed", Intent::class.java)
        for (event in listOf("activityResumed", "activityPaused", "activityStopped", "activityDestroyed")) {
            module.installHook(proxyType.getDeclaredMethod(event, Intent::class.java), "musicenhance.widget.observer.$event") { chain ->
                val callback = chain.thisObject as? IInterface
                if (callback == null) chain.proceed() else observers.deliverNative(
                    callback.asBinder(), event, chain.args.firstOrNull() as? Intent,
                ) { chain.proceed() }
            }
        }

        // FlipHome tries AMS first and ATMS second. Match the observer argument, including
        // implementations inherited from a vendor base service, instead of fixing an overload.
        val services = listOf("com.android.server.am.ActivityManagerService", "com.android.server.wm.ActivityTaskManagerService")
        val methods = services.flatMap { name ->
            val service = runCatching { loader.loadClass(name) }.getOrNull()
            generateSequence(service) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
                .filter {
                    it.name in setOf("registerActivityObserver", "unregisterActivityObserver") &&
                        observerType in it.parameterTypes && !java.lang.reflect.Modifier.isAbstract(it.modifiers)
                }
                .toList()
        }.distinct()
        check(methods.any { it.name == "registerActivityObserver" }) { "MIUI activity observer registration unavailable" }
        methods.forEachIndexed { index, method ->
            module.installHook(method, "musicenhance.widget.observer.registration.$index") { chain ->
                val callerUid = Binder.getCallingUid()
                val callback = chain.args.firstOrNull(observerType::isInstance) as? IInterface
                val result = chain.proceed()
                safeHook("cover activity observer registration") {
                    if (callback == null) return@safeHook
                    if (method.name == "unregisterActivityObserver") {
                        observers.unregister(callback.asBinder())
                    } else {
                        val context = serviceContext(chain.thisObject) ?: return@safeHook
                        val identity = Binder.clearCallingIdentity()
                        val packages = try { context.packageManager.getPackagesForUid(callerUid) } finally {
                            Binder.restoreCallingIdentity(identity)
                        }
                        // The supported FlipHome has its own UID. Do not capture another system
                        // app's observer if a future ROM places the launcher in a shared UID.
                        if (packages?.toList() != listOf(FLIP_HOME_PACKAGE)) return@safeHook
                        observers.register(callback.asBinder(), userIdForUid(callerUid)) { intent ->
                            resumed.invoke(callback, intent)
                        }
                        moduleInfo("System cover widget observer registered")
                    }
                }
                result
            }
        }
        moduleInfo("System cover widget observer hooks installed")
    }

    fun notifyOwnershipChanged(component: ComponentName, uid: Int) {
        val userId = userIdForUid(uid)
        // Binder work stays off WMS's lock and main thread. The registry checks the latest real
        // foreground event at delivery time and ignores a player that has already lost focus.
        worker.post {
            val count = observers.refresh(component, userId)
            moduleInfo("Cover widget policy refreshed via system observer; callbacks=$count; app=${component.packageName}")
        }
    }

    internal fun userIdForUid(uid: Int): Int = uid / PER_USER_UID_RANGE

    private fun serviceContext(service: Any?): Context? {
        var type = service?.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField("mContext") }.getOrNull()
            if (field != null) return field.apply { isAccessible = true }.get(service) as? Context
            type = type.superclass
        }
        return null
    }
}
