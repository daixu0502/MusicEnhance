package com.jaco.musicenhance.hook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.jaco.musicenhance.adapter.MusicAppProfile
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.adapter.isHorizontalPlayerActivityName
import java.util.WeakHashMap

/**
 * Enables HyperOS cover policies only for registered, enabled music apps.
 * These LSPosed hooks require system-server internals; each installation is guarded by safeHook.
 */
@SuppressLint("PrivateApi")
internal object SystemCoverHook {
    private const val CONTINUITY = "miui.continuity.policy"
    private const val SMALL_COVER = "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN"
    private const val FLIP_SCREEN_META_DATA = "miui.supportFlipFullScreen"
    private const val WATCH_OVERLAY_PROPERTY = "miui.supportFlipWatchOverlayGroupView"
    private const val FULL_SCREEN = 0
    private const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3
    private val enhancedOriginalInfo = WeakHashMap<Any, ActivityInfo>()

    fun install(classLoader: ClassLoader) {
        SystemCoverWidgetHook.install(classLoader)
        safeHook("application compatibility manager") {
            val manager = classLoader.loadClass("com.android.server.wm.ApplicationCompatManager")
            safeHook("application compatibility value") { installApplicationValueHook(manager) }
            safeHook("activity compatibility value") { installActivityValueHook(manager) }
            safeHook("application compatibility property") { installApplicationPropertyHook(manager) }
            safeHook("activity compatibility property") { installActivityPropertyHook(manager) }
        }
        safeHook("flip continuity") { installContinuityHook(classLoader) }
        installFullScreenHooks(classLoader)
    }

    private fun installApplicationValueHook(manager: Class<*>) {
        module.installHook(
            manager.declaredMethod("getPropertyIntByApplication", String::class.java, String::class.java),
            "musicenhance.compat.application",
        ) { chain ->
            val property = chain.args[0] as? String
            val packageName = chain.args[1] as? String
            if (enabledProfile(packageName) != null) propertyValue(property)
                ?: chain.proceed()
            else chain.proceed()
        }
    }

    private fun installActivityValueHook(manager: Class<*>) {
        module.installHook(
            manager.declaredMethod("getPropertyIntByActivity", String::class.java, ComponentName::class.java),
            "musicenhance.compat.activity",
        ) { chain ->
            val property = chain.args[0] as? String
            val component = chain.args[1] as? ComponentName
            if (enabledProfile(component?.packageName) != null) propertyValue(property)
                ?: chain.proceed()
            else chain.proceed()
        }
    }

    private fun installApplicationPropertyHook(manager: Class<*>) {
        module.installHook(
            manager.declaredMethod("hasPropertyByApplication", String::class.java, String::class.java),
            "musicenhance.has.application",
        ) { chain ->
            val property = chain.args[0] as? String
            val packageName = chain.args[1] as? String
            if (enabledProfile(packageName) != null && property in supportedProperties) true
            else chain.proceed()
        }
    }

    private fun installActivityPropertyHook(manager: Class<*>) {
        module.installHook(
            manager.declaredMethod("hasPropertyByActivity", String::class.java, ComponentName::class.java),
            "musicenhance.has.activity",
        ) { chain ->
            val property = chain.args[0] as? String
            val component = chain.args[1] as? ComponentName
            if (enabledProfile(component?.packageName) != null && property in supportedProperties) true
            else chain.proceed()
        }
    }

    private fun installContinuityHook(classLoader: ClassLoader) {
        val controller = classLoader.loadClass("com.android.server.wm.InterceptActivityController")
        module.installHook(
            controller.declaredMethod(
                "isFlipContinuityEnabledFromSetting",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
            ),
            "musicenhance.flip.continuity",
        ) { chain ->
            if (enabledProfile(chain.args[0] as? String) != null) true else chain.proceed()
        }
    }

    /** Forces Music app out of HyperOS' narrow watch-overlay compatibility window. */
    private fun installFullScreenHooks(classLoader: ClassLoader) {
        safeHook("enhanced player window configuration") { installPlayerRelayoutHook(classLoader) }
        safeHook("enhanced player window cleanup") { installPlayerRemovalHook(classLoader) }
        safeHook("Music app activity full-screen metadata") {
            val packageManager = classLoader.loadClass("com.android.server.pm.IPackageManagerBase")
            module.installHook(
                packageManager.declaredMethod(
                    "getActivityInfo",
                    ComponentName::class.java,
                    Long::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                ),
                "musicenhance.fullscreen.activity.info",
            ) { chain ->
                val result = chain.proceed()
                val info = (result as? ActivityInfo)?.takeIf(::isTargetPackage)
                // PackageManager may return a cached object; never mutate another caller's metadata.
                if (info != null) ActivityInfo(info).apply { applyFullScreenMetadata() } else result
            }
        }
        safeHook("Music app watch overlay property") {
            val packageManager = classLoader.loadClass("com.android.server.pm.IPackageManagerBase")
            val propertyClass = classLoader.loadClass("android.content.pm.PackageManager\$Property")
            val constructor = propertyClass.getDeclaredConstructor(
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            module.installHook(
                packageManager.declaredMethod(
                    "getPropertyAsUser",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
                "musicenhance.fullscreen.watch.overlay",
            ) { chain ->
                val property = chain.args.getOrNull(0) as? String
                val packageName = chain.args.getOrNull(1) as? String
                val className = chain.args.getOrNull(2) as? String
                val userId = chain.args.getOrNull(3) as? Int
                val profile = enabledProfile(packageName)
                if (property == WATCH_OVERLAY_PROPERTY && profile != null) {
                    when {
                        isEnhancedComponentActive(profile, className, userId) -> constructor.newInstance(property, false, packageName, className)
                        profile.isHomeActivity(className) -> constructor.newInstance(property, true, packageName, className)
                        profile.isPlayerActivity(className) -> constructor.newInstance(property, false, packageName, className)
                        else -> chain.proceed()
                    }
                } else chain.proceed()
            }
        }
        safeHook("Music app flexible outer-screen bounds") {
            val controller = classLoader.loadClass("com.android.server.wm.BoundsCompatController")
            val configuration = classLoader.loadClass("android.content.res.Configuration")
            module.installHook(
                controller.declaredMethod("canUseFixedAspectRatio", configuration),
                "musicenhance.fullscreen.aspect",
            ) { chain ->
                val owner = fieldValue(chain.thisObject, "mOwner")
                val packageName = fieldValue(owner, "packageName") as? String
                val activityInfo = fieldValue(owner, "info") as? ActivityInfo
                val profile = enabledProfile(packageName)
                if (
                    profile?.isPlayerActivity(activityInfo?.name) == true ||
                    synchronized(enhancedOriginalInfo) { enhancedOriginalInfo.containsKey(owner) }
                ) false else chain.proceed()
            }
        }
        safeHook("Music app display cutout layout") {
            val windowLayout = classLoader.loadClass("android.view.WindowLayoutStubImpl")
            module.installHook(
                windowLayout.declaredMethod("getLayoutInDisplayCutoutMode", WindowManager.LayoutParams::class.java),
                "musicenhance.fullscreen.cutout",
            ) { chain ->
                val attrs = chain.args.firstOrNull() as? WindowManager.LayoutParams
                if (
                    attrs != null && enabledProfile(attrs.packageName) != null &&
                    isPlayerWindow(attrs)
                ) {
                    LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    chain.proceed()
                }
            }
        }
        module.log(Log.INFO, MusicEnhanceModule.TAG, "Music app outer-screen full-screen hooks installed; build=${com.jaco.musicenhance.BuildConfig.VERSION_CODE}")
    }

    private fun ActivityInfo.applyFullScreenMetadata() {
        metaData = (metaData?.let(::Bundle) ?: Bundle()).apply {
            putInt(FLIP_SCREEN_META_DATA, FULL_SCREEN)
        }
        if (isHorizontalPlayerActivityName(name)) {
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        }
    }

    private fun isTargetPackage(info: ActivityInfo): Boolean =
        enabledProfile(info.packageName)?.isPlayerActivity(info.name) == true

    private fun isPlayerWindow(attrs: WindowManager.LayoutParams): Boolean {
        val title = runCatching { attrs.title?.toString() }.getOrNull()
        return enabledProfile(attrs.packageName)?.isPlayerWindow(title) == true
    }

    /** A title change alone doesn't invalidate HyperOS' cached ActivityRecord configuration. */
    private fun installPlayerRelayoutHook(loader: ClassLoader) {
        val service = loader.loadClass("com.android.server.wm.WindowManagerService")
        service.declaredMethods.filter { it.name == "relayoutWindow" }.forEachIndexed { index, method ->
            module.installHook(method, "musicenhance.player.relayout.$index") { chain ->
                var changedOwner: ActivityInfo? = null
                val attrs = chain.args.filterIsInstance<WindowManager.LayoutParams>().firstOrNull()
                val profile = attrs?.packageName?.let(MusicAppRegistry::find)
                if (profile != null) {
                    safeHook("enhanced player bounds refresh") {
                        val manager = chain.thisObject ?: return@safeHook
                        val lock = fieldValue(manager, "mGlobalLock") ?: return@safeHook
                        val callingIdentity = android.os.Binder.clearCallingIdentity()
                        try {
                            synchronized(lock) {
                                val windows = fieldValue(manager, "mWindowMap") as? Map<*, *> ?: return@synchronized
                                val window = resolveRelayoutWindow(chain.args, windows) ?: return@synchronized
                                val owner = fieldValue(window, "mActivityRecord") ?: return@synchronized
                                val info = fieldValue(owner, "info") as? ActivityInfo ?: return@synchronized
                                if (info.packageName != profile.packageName || !profile.ownsActivity(info.name)) return@synchronized
                                val active = hookEnabled(profile) && profile.isEnhancedPlayerWindow(attrs.title?.toString())
                                if (active == synchronized(enhancedOriginalInfo) { enhancedOriginalInfo.containsKey(owner) }) return@synchronized
                                val infoField = owner.javaClass.getDeclaredField("info").apply { isAccessible = true }
                                if (active) {
                                    infoField.set(owner, ActivityInfo(info).apply {
                                        applyFullScreenMetadata()
                                        metaData.putBoolean(com.jaco.musicenhance.player.PlayerActivitySessions.OWNED_ACTIVITY_METADATA, true)
                                        // Our Activity handles rotation/fold changes without losing lyric/UI state.
                                        configChanges = configChanges or ActivityInfo.CONFIG_ORIENTATION or
                                            ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                                            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_KEYBOARD_HIDDEN
                                    })
                                    synchronized(enhancedOriginalInfo) { enhancedOriginalInfo[owner] = info }
                                } else {
                                    infoField.set(owner, synchronized(enhancedOriginalInfo) { enhancedOriginalInfo.remove(owner) } ?: return@synchronized)
                                }
                                changedOwner = info
                                // Recompute only on ownership transitions, never on every frame/relayout.
                                findMethod(owner, "recomputeConfiguration", 0)?.invoke(owner)
                                findMethod(owner, "ensureActivityConfiguration", 2)?.takeIf {
                                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
                                }?.invoke(owner, 0, true)
                                moduleInfo("Enhanced player bounds ownership=$active; app=${profile.packageName}")
                            }
                        } finally {
                            android.os.Binder.restoreCallingIdentity(callingIdentity)
                        }
                    }
                }
                // Publish ownership before WMS calculates frames and sends this relayout's result.
                // Recent Android releases pass an IBinder here, older ones pass an IWindow.
                val result = chain.proceed()
                changedOwner?.let { info ->
                    SystemCoverWidgetHook.notifyOwnershipChanged(ComponentName(info.packageName, info.name), info.applicationInfo.uid)
                }
                result
            }
        }
    }

    /** A killed process may never send the relayout that restores its original window title. */
    private fun installPlayerRemovalHook(loader: ClassLoader) {
        val windowState = loader.loadClass("com.android.server.wm.WindowState")
        module.installHook(windowState.getDeclaredMethod("removeImmediately"), "musicenhance.player.remove") { chain ->
            val window = chain.thisObject
            val owner = fieldValue(window, "mActivityRecord")
            val attrs = fieldValue(window, "mAttrs") as? WindowManager.LayoutParams
            val enhanced = MusicAppRegistry.find(attrs?.packageName)?.isEnhancedPlayerWindow(attrs?.title?.toString()) == true
            val result = chain.proceed()
            if (enhanced && owner != null) safeHook("enhanced player ownership cleanup") {
                val original = synchronized(enhancedOriginalInfo) { enhancedOriginalInfo.remove(owner) } ?: return@safeHook
                owner.javaClass.getDeclaredField("info").apply { isAccessible = true }.set(owner, original)
                SystemCoverWidgetHook.notifyOwnershipChanged(ComponentName(original.packageName, original.name), original.applicationInfo.uid)
            }
            result
        }
    }

    internal fun resolveRelayoutWindow(args: List<Any?>, windows: Map<*, *>): Any? =
        args.firstNotNullOfOrNull { argument ->
            val token = when (argument) {
                is android.os.IBinder -> argument
                is android.os.IInterface -> argument.asBinder()
                else -> null
            }
            token?.let(windows::get)
        }

    private fun findMethod(instance: Any, name: String, parameterCount: Int): java.lang.reflect.Method? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == parameterCount }?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        return null
    }

    private fun isEnhancedComponentActive(profile: MusicAppProfile, className: String?, userId: Int?): Boolean {
        if (!profile.ownsActivity(className)) return false
        val owners = synchronized(enhancedOriginalInfo) { enhancedOriginalInfo.keys.toList() }
        return owners.any { owner ->
            val info = fieldValue(owner, "info") as? ActivityInfo
            info?.packageName == profile.packageName && info.name == className &&
                SystemCoverWidgetHook.userIdForUid(info.applicationInfo.uid) == userId
        }
    }

    private fun fieldValue(instance: Any?, name: String): Any? {
        var type: Class<*>? = instance?.javaClass ?: return null
        while (type != null) {
            runCatching {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            type = type.superclass
        }
        return null
    }

    private fun enabledProfile(packageName: String?): MusicAppProfile? =
        MusicAppRegistry.find(packageName)?.takeIf(::hookEnabled)

    private fun propertyValue(property: String?): Int? = when (property) {
        CONTINUITY -> 5
        SMALL_COVER -> 1
        else -> null
    }

    private val supportedProperties = setOf(CONTINUITY, SMALL_COVER)
}
