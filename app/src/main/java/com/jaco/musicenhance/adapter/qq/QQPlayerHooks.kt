package com.jaco.musicenhance.adapter.qq

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.EnhancedPlayerActivity
import com.jaco.musicenhance.player.PlayerActivitySessions
import java.lang.reflect.Modifier

/** QQ 20.8.5.8 / 20.9.0.8 normally complete player navigation in BasePlayerActivity.onResume. */
internal object QQPlayerHooks {
    fun install(loader: ClassLoader) = safeHook("QQ player navigation completion") {
        val api = Api(loader)
        module.installHook(
            Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java),
            "musicenhance.qq.player.ready",
        ) { chain ->
            val result = chain.proceed()
            safeHook("QQ enhanced player ready") ready@ {
                val activity = chain.args.firstOrNull() as? EnhancedPlayerActivity ?: return@ready
                if (activity.packageName != QQPlayerProfile.packageName || activity.isFinishing ||
                    activity.isDestroyed || activity.intent?.action != PlayerActivitySessions.ACTION) return@ready
                val token = activity.intent.getStringExtra(PlayerActivitySessions.EXTRA_SESSION)
                val session = PlayerActivitySessions.find(token) ?: return@ready
                if (session.activity.get() !== activity || !session.canShow(activity)) return@ready
                if (api.isLaunchPending()) {
                    // The intercepted native page never resumes to release QQ's five-second
                    // launch lock. Use its completion protocol, including the host callback.
                    api.completeLaunch()
                    moduleInfo("QQ player navigation completed by enhanced Activity")
                }
            }
            result
        }
        moduleInfo("QQ player navigation completion installed")
    }

    private class Api(loader: ClassLoader) {
        private val operations = loader.loadClass("com.tencent.qqmusic.business.playercommon.a")
        private val complete = operations.getDeclaredMethod("j")
        private val pending = operations.getDeclaredMethod("k")

        init {
            val style = loader.loadClass("com.tencent.qqmusic.business.playernew.interactor.playerstyle.PlayerStyle")
            val jump = operations.getDeclaredMethod(
                "i", Activity::class.java, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, style,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Bundle::class.java,
            )
            require(Modifier.isStatic(jump.modifiers) && jump.returnType == Boolean::class.javaPrimitiveType &&
                Modifier.isStatic(complete.modifiers) && complete.returnType == Void.TYPE &&
                Modifier.isStatic(pending.modifiers) && pending.returnType == Boolean::class.javaPrimitiveType) {
                "Unsupported QQ player navigation contract"
            }
        }

        fun isLaunchPending(): Boolean = pending.invoke(null) == true
        fun completeLaunch() { complete.invoke(null) }
    }
}
