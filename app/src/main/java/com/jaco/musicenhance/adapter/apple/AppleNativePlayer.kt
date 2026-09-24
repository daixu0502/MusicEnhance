package com.jaco.musicenhance.adapter.apple

import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.model.PlayerSnapshot
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference

/** Holds no Activity strongly. Read on the UI thread, where the host updates its media browser. */
internal object AppleNativePlayer {
    private var fragment = WeakReference<Any>(null)

    fun install(loader: ClassLoader) {
        safeHook("Apple Music native player model") {
            val type = loader.loadClass("com.apple.android.music.player.fragment.PlayerSongViewFragment")
            type.declaredMethods.filter { it.name == "onResume" || it.name == "onViewCreated" }.forEach { method ->
                module.installHook(method, "musicenhance.apple.metadata.${method.name}", Hooker { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { fragment = WeakReference(it) }
                    result
                })
            }
        }
    }

    fun item(player: PlayerSnapshot): Any? = runCatching {
        val owner = fragment.get() ?: return null
        val item = owner.javaClass.getField("c").get(owner) ?: return null
        item.takeIf { it.javaClass.getMethod("getTitle").invoke(it) == player.title }
    }.getOrNull()

    fun browser(): Any? = runCatching {
        val owner = fragment.get() ?: return null
        owner.javaClass.getMethod("getMediaBrowser").invoke(owner)
    }.getOrNull()
}
