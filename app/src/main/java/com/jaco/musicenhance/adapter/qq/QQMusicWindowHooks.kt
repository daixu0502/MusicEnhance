package com.jaco.musicenhance.adapter.qq

import android.app.Activity
import com.jaco.musicenhance.hook.declaredMethod
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.CoverPlayerInjector
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Modifier

/** QQ 20.8.5.8's own status-bar restore operations, intercepted before any Window API is called. */
internal object QQMusicWindowHooks {
    fun install(classLoader: ClassLoader) {
        safeHook("QQ PlayerUtil status-bar restore") {
            val type = classLoader.loadClass("com.tencent.qqmusic.business.playercommon.normalplayer.common.i")
            val showStatusBar = type.declaredMethod("s", Activity::class.java)
            check(Modifier.isStatic(showStatusBar.modifiers) && showStatusBar.returnType == Void.TYPE)
            module.installHook(showStatusBar, "musicenhance.qq.player.statusbar.restore", Hooker { chain ->
                if (ownsBars(chain.args.firstOrNull() as? Activity)) {
                    moduleInfo("Blocked QQ PlayerUtil.showStatusBar before window request")
                    null
                } else chain.proceed()
            })
            moduleInfo("QQ PlayerUtil status-bar restore hook installed")
        }
        safeHook("QQ BaseActivity status-bar restore") {
            val type = classLoader.loadClass("com.tencent.qqmusic.activity.baseactivity.BaseActivity")
            val showStatusBar = type.declaredMethod("showStatusBar")
            check(showStatusBar.returnType == Void.TYPE)
            module.installHook(showStatusBar, "musicenhance.qq.activity.statusbar.restore", Hooker { chain ->
                if (ownsBars(chain.thisObject as? Activity)) {
                    moduleInfo("Blocked QQ BaseActivity.showStatusBar before window flags")
                    null
                } else chain.proceed()
            })
        }
        // TrafficDataFreeController schedules message 101 after 10,000 ms. Its e2() calls
        // PlayerUtil.s(Activity); retain the message's other work (e.g. removing the native logo).
        // Deoptimize the small controller and its Handler, including synthetic wrapper methods,
        // so a previously compiled inline copy cannot bypass the hook.
        safeHook("QQ delayed status-bar restore caller") {
            for (name in listOf(
                "com.tencent.qqmusic.business.playernew.view.hn",
                "com.tencent.qqmusic.business.playernew.view.hn\$b",
            )) {
                classLoader.loadClass(name).declaredMethods.forEach { method ->
                    if (!Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers)) {
                        module.deoptimize(method)
                    }
                }
            }
        }
        safeHook("QQ 3D player status-bar restore caller") {
            val caller = classLoader.loadClass(
                "com.tencent.qqmusic.business.playernew.view.playersong.tme3dplayer.layer.LayersPlayerView",
            )
            module.deoptimize(caller.declaredMethod("J2"))
        }
    }

    private fun ownsBars(activity: Activity?): Boolean =
        runCatching { CoverPlayerInjector.ownsSystemBars(activity) }.getOrDefault(false)
}
