package com.jaco.musicenhance.hook

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.jaco.musicenhance.adapter.MusicAppProfile
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.adapter.isHorizontalPlayerActivityName
import io.github.libxposed.api.XposedInterface.Hooker

/** Enables Xiaomi's own small-cover-screen policy for Music app only. */
internal object SystemCoverHook {
    private const val CONTINUITY = "miui.continuity.policy"
    private const val SMALL_COVER = "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN"
    private const val FLIP_SCREEN_META_DATA = "miui.supportFlipFullScreen"
    private const val WATCH_OVERLAY_PROPERTY = "miui.supportFlipWatchOverlayGroupView"
    private const val FULL_SCREEN = 0
    private const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3

    fun install(classLoader: ClassLoader) {
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
            Hooker { chain ->
                val property = chain.args[0] as? String
                val packageName = chain.args[1] as? String
                if (enabledProfile(packageName) != null) propertyValue(property)
                    ?: chain.proceed()
                else chain.proceed()
            },
        )
    }

    private fun installActivityValueHook(manager: Class<*>) {
        module.installHook(
            manager.declaredMethod("getPropertyIntByActivity", String::class.java, ComponentName::class.java),
            "musicenhance.compat.activity",
            Hooker { chain ->
                val property = chain.args[0] as? String
                val component = chain.args[1] as? ComponentName
                if (enabledProfile(component?.packageName) != null) propertyValue(property)
                    ?: chain.proceed()
                else chain.proceed()
            },
        )
    }

    private fun installApplicationPropertyHook(manager: Class<*>) {
        module.installHook(
            manager.declaredMethod("hasPropertyByApplication", String::class.java, String::class.java),
            "musicenhance.has.application",
            Hooker { chain ->
                val property = chain.args[0] as? String
                val packageName = chain.args[1] as? String
                if (enabledProfile(packageName) != null && property in supportedProperties) true
                else chain.proceed()
            },
        )
    }

    private fun installActivityPropertyHook(manager: Class<*>) {
        module.installHook(
            manager.declaredMethod("hasPropertyByActivity", String::class.java, ComponentName::class.java),
            "musicenhance.has.activity",
            Hooker { chain ->
                val property = chain.args[0] as? String
                val component = chain.args[1] as? ComponentName
                if (enabledProfile(component?.packageName) != null && property in supportedProperties) true
                else chain.proceed()
            },
        )
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
            Hooker { chain ->
                if (enabledProfile(chain.args[0] as? String) != null) true else chain.proceed()
            },
        )
    }

    /** Forces Music app out of HyperOS' narrow watch-overlay compatibility window. */
    private fun installFullScreenHooks(classLoader: ClassLoader) {
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
                Hooker { chain ->
                    val result = chain.proceed()
                    val info = (result as? ActivityInfo)?.takeIf(::isTargetPackage)
                    // PackageManager may return a cached object; never mutate another caller's metadata.
                    if (info != null) ActivityInfo(info).apply { applyFullScreenMetadata() } else result
                },
            )
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
                Hooker { chain ->
                    val property = chain.args.getOrNull(0) as? String
                    val packageName = chain.args.getOrNull(1) as? String
                    val className = chain.args.getOrNull(2) as? String
                    val profile = enabledProfile(packageName)
                    if (property == WATCH_OVERLAY_PROPERTY && profile != null) {
                        when {
                            profile.isHomeActivity(className) -> constructor.newInstance(property, true, packageName, className)
                            profile.isPlayerActivity(className) -> constructor.newInstance(property, false, packageName, className)
                            else -> chain.proceed()
                        }
                    } else chain.proceed()
                },
            )
        }
        safeHook("Music app flexible outer-screen bounds") {
            val controller = classLoader.loadClass("com.android.server.wm.BoundsCompatController")
            val configuration = classLoader.loadClass("android.content.res.Configuration")
            module.installHook(
                controller.declaredMethod("canUseFixedAspectRatio", configuration),
                "musicenhance.fullscreen.aspect",
                Hooker { chain ->
                    val owner = fieldValue(chain.thisObject, "mOwner")
                    val packageName = fieldValue(owner, "packageName") as? String
                    val activityInfo = fieldValue(owner, "info") as? ActivityInfo
                    val result = chain.proceed()
                    if (
                        enabledProfile(packageName)?.isPlayerActivity(activityInfo?.name) == true
                    ) false else result
                },
            )
        }
        safeHook("Music app display cutout layout") {
            val windowLayout = classLoader.loadClass("android.view.WindowLayoutStubImpl")
            module.installHook(
                windowLayout.declaredMethod("getLayoutInDisplayCutoutMode", WindowManager.LayoutParams::class.java),
                "musicenhance.fullscreen.cutout",
                Hooker { chain ->
                    val attrs = chain.args.firstOrNull() as? WindowManager.LayoutParams
                    if (
                        attrs != null && enabledProfile(attrs.packageName) != null &&
                        isPlayerWindow(attrs)
                    ) {
                        LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        chain.proceed()
                    }
                },
            )
        }
        module.log(Log.INFO, MusicEnhanceModule.TAG, "Music app outer-screen full-screen hooks installed")
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
