package com.jaco.musicenhance.adapter.qq

import android.app.Activity
import android.app.Instrumentation
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.EnhancedPlayerActivity
import com.jaco.musicenhance.player.PlayerActivitySessions
import java.lang.reflect.Modifier
import java.lang.reflect.Method

/** QQ 20.8.5.8 / 20.9.0.8 normally complete player navigation in BasePlayerActivity.onResume. */
internal object QQPlayerHooks {
    private val installed = mutableSetOf<Method>()
    fun install(loader: ClassLoader) = safeHook("QQ player navigation completion") {
        val api = Api(loader)
        val resume = Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java)
        if (resume in installed) return@safeHook
        module.installHook(
            resume,
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
        installed.add(resume)
        moduleInfo("QQ player navigation completion installed")
    }

    private class Api(loader: ClassLoader) {
        private val jump = QQInterfaceRules.navigationJump(loader)
        private val operations = jump.declaringClass
        private val complete = QQInterfaceRules.navigationCompletion(loader, operations)
        private val pending = QQInterfaceRules.navigationPending(loader, operations)

        init {
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
