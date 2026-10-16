package com.jaco.musicenhance.adapter.salt

import android.graphics.Bitmap
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import io.github.libxposed.api.XposedInterface

/** Observe verified publication points instead of guessing among erased StateFlow fields. */
internal object SaltNativeDataHooks {
    data class Lyrics(val ownerId: String, val sourceText: String)
    private data class Cover(val ownerId: String, val bitmap: WeakReference<Bitmap>)
    @Volatile var lyrics: Lyrics? = null
        private set
    @Volatile private var cover: Cover? = null
    private val installed = mutableSetOf<Executable>()

    fun artwork(ownerId: String): Bitmap? = cover?.takeIf { it.ownerId == ownerId }?.bitmap?.get()?.takeUnless { it.isRecycled }

    fun install(loader: ClassLoader) {
        val data = SaltDataApi(loader)
        safeHook("Salt native lyric publication") {
            val source = data.lyricSource
            val publish = data.lyricPublish
            hook(publish, "musicenhance.salt.lyrics.publish") { chain ->
                val result = chain.proceed()
                safeHook("Salt copy lyric source") {
                    val owner = chain.args[1] as? String
                    val text = chain.args[0]?.let { source.get(it) as? String }
                    lyrics = if (owner.isNullOrBlank() || text == null || text.length > MAX_LYRIC_CHARS) null else Lyrics(owner, text)
                }
                result
            }
        }
        safeHook("Salt native artwork publication") {
            val controls = SaltControlApi(loader)
            val current = controls.currentSong
            val value = current.type.getMethod("getValue")
            val publish = data.artworkPublish
            hook(publish, "musicenhance.salt.artwork.publish") { chain ->
                // The host committed this original bitmap to the current song before creating its blurred background.
                safeHook("Salt copy native artwork") {
                    val song = value.invoke(current.get(null))
                    val id = song?.javaClass?.getMethod("getId")?.invoke(song) as? String
                    val bitmap = chain.args[1] as? Bitmap
                    cover = if (id.isNullOrBlank() || bitmap == null || bitmap.isRecycled) null else Cover(id, WeakReference(bitmap))
                }
                chain.proceed()
            }
        }
    }

    @Synchronized private fun hook(executable: Executable, id: String, hooker: XposedInterface.Hooker) {
        if (executable in installed) return
        module.installHook(executable, id, hooker)
        installed.add(executable)
    }

    private const val MAX_LYRIC_CHARS = 512_000
}
