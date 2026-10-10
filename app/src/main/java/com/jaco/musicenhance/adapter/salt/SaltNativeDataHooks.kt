package com.jaco.musicenhance.adapter.salt

import android.graphics.Bitmap
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Executable
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.query.enums.StringMatchType

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
        val hint = SaltApi.hint(loader)
        val controller = loader.loadClass("com.salt.music.service.MusicController")
        safeHook("Salt native lyric publication") {
            val document = HostInterfaces.type(loader, "salt.lyrics.document.v1", known = { loader.loadClass("androidx.media3.${hint.lyricsClass}") },
                validate = { type -> type.declaredFields.count { it.type == String::class.java && !Modifier.isStatic(it.modifiers) } == 1 }) {
                methods { matcher { name = "toString"; usingStrings(listOf("LyricsDocument(sourceText=", ", lyricsLines="), StringMatchType.Contains) } }
                    .map { it.getMethodInstance(loader).declaringClass }.distinct()
            }
            val source = document.declaredFields.single { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
            val publish = HostInterfaces.method(loader, "salt.lyrics.publish.v1", known = { controller.getMethod("\u0865", document, String::class.java) },
                validate = { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(document, String::class.java)) && it.returnType == Void.TYPE }) {
                methods { matcher { declaredClass = controller.name; paramTypes(document, String::class.java); returnType = "void" } }
                    .map { it.getMethodInstance(loader) }
            }
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
            val controls = SaltControlApi(loader, hint)
            val current = controls.currentSong
            val value = current.type.getMethod("getValue")
            val publish = HostInterfaces.method(loader, "salt.artwork.publish.v1", known = {
                controller.getMethod("\u052e", controller, Bitmap::class.java)
            }, validate = { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(controller, Bitmap::class.java)) && it.returnType == Void.TYPE }) {
                methods { matcher { declaredClass = controller.name; paramTypes(controller, Bitmap::class.java); returnType = "void" } }
                    .filter { method -> method.invokes.any { it.className == "com.salt.music.service.SongPicture" && it.name == "getDefaultCoverBitmap" } &&
                        method.invokes.any { it.className == "android.graphics.Bitmap" && it.name == "createBitmap" } }
                    .map { it.getMethodInstance(loader) }
            }
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
