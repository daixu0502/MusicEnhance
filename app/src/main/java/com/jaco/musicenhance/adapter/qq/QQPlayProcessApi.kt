package com.jaco.musicenhance.adapter.qq

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Shared IPC entry point for QQ 20.8.5.8 and 20.9.0.8 (verified from both APKs). */
internal class QQPlayProcessApi(loader: ClassLoader) {
    val getEnvironment: Method = resolveFactory(loader)
    // Derive the interface from the verified factory: QQ 20.9 renamed the interface too.
    val type: Class<*> = getEnvironment.returnType

    private companion object {
        val FACTORIES = listOf(
            "com.tencent.qqmusic.common.ipc.MusicProcess" to "playEnv", // 20.8.5.8
            "com.tencent.qqmusic.common.ipc.b0" to "t", // 20.9.0.8
        )

        fun resolveFactory(loader: ClassLoader): Method {
            val song = loader.loadClass("com.tencent.qqmusicplayerprocess.songinfo.SongInfo")
            var failure: Throwable? = null
            for ((className, methodName) in FACTORIES) {
                try {
                    val factory = loader.loadClass(className).getMethod(methodName)
                    require(Modifier.isStatic(factory.modifiers)) { "$className.$methodName is not static" }
                    require(factory.returnType.getMethod("getPlaySong").returnType == song) {
                        "$className.$methodName does not return QQ's play process interface"
                    }
                    return factory
                } catch (error: ReflectiveOperationException) {
                    failure = error
                } catch (error: IllegalArgumentException) {
                    failure = error
                }
            }
            throw ReflectiveOperationException("Unsupported QQ play process entry point", failure)
        }
    }
}
