package com.jaco.musicenhance.hook

import android.util.Log
import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppProfile
import com.jaco.musicenhance.adapter.MusicAppAdapters
import com.jaco.musicenhance.adapter.MusicAppRegistry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal lateinit var module: MusicEnhanceModule
    private set

class MusicEnhanceModule : XposedModule() {
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        processName = param.processName
        log(Log.INFO, TAG, "API ${apiVersion} loaded; process=${param.processName}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        safeHook("system cover compatibility") {
            SystemCoverHook.install(param.classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == "com.android.systemui" && param.isFirstPackage) {
            safeHook("cover system rotation") { CoverRotationAnimationHook.install(param.classLoader) }
            return
        }
        val profile = MusicAppRegistry.find(param.packageName) ?: return
        log(
            Log.INFO,
            TAG,
            "${profile.displayName} package ready; process=$processName, firstPackage=${param.isFirstPackage}",
        )
        if (!param.isFirstPackage) return
        val enabled = hookEnabled(profile)
        log(Log.INFO, TAG, "Hook ${profile.displayName} preference=$enabled; process=$processName")
        if (!enabled) {
            detach()
            return
        }
        // QQ Music may create MediaSession and AudioTrack in a :service process. Install in every
        // process belonging to the package; the Activity hook is harmless where no Activity exists.
        MusicAppHooks.install(processName)
        MusicAppAdapters.installNativeHooks(profile, param.classLoader)
    }

    internal fun installHook(
        executable: java.lang.reflect.Executable,
        id: String,
        hooker: XposedInterface.Hooker,
    ): XposedInterface.HookHandle = hook(executable).setId(id).intercept(hooker)

    companion object {
        const val TAG = "MusicEnhance"
    }
}

internal fun safeHook(name: String, action: () -> Unit) {
    runCatching(action).onFailure {
        if (::module.isInitialized) module.log(Log.ERROR, MusicEnhanceModule.TAG, "$name failed", it)
    }
}

internal fun hookEnabled(profile: MusicAppProfile): Boolean = runCatching {
    Prefs.isHookEnabled(module.getRemotePreferences(Prefs.NAME), profile.enabledPreference)
}.getOrDefault(false)

internal fun moduleInfo(message: String) {
    if (::module.isInitialized) module.log(Log.INFO, MusicEnhanceModule.TAG, message)
}

internal fun Class<*>.declaredMethod(name: String, vararg params: Class<*>): java.lang.reflect.Method =
    getDeclaredMethod(name, *params).apply { isAccessible = true }
