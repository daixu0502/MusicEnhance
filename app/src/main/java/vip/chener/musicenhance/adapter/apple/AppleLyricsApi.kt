package vip.chener.musicenhance.adapter.apple

import android.app.Application
import vip.chener.musicenhance.adapter.discovery.HostInterfaces

/** Native lyric pointer getters retain their semantic names independently of the ViewModel. */
internal class AppleLyricsApi(private val loader: ClassLoader) {
    private val item = loader.loadClass("com.apple.android.music.model.PlaybackItem")
    val type = HostInterfaces.type(loader, "apple.lyrics.viewmodel.v1", known = {
        loader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
    }, validate = { candidate -> runCatching { candidate.getConstructor(Application::class.java) }.isSuccess }) {
        methods { matcher { name = "loadLyrics"; paramTypes(item) } }
            .map { it.getMethodInstance(loader).declaringClass }.distinct()
    }
    val load = HostInterfaces.method(loader, "apple.lyrics.load.v1", known = { type.getMethod("loadLyrics", item) },
        validate = { it.parameterTypes.contentEquals(arrayOf(item)) && it.returnType == Void.TYPE }) {
        methods { matcher { declaredClass = type.name; paramTypes(item); returnType = "void" } }
            .filter { method -> method.invokes.any { it.name == "getId" } && method.invokes.any { it.name == "getQueueId" } }
            .map { it.getMethodInstance(loader) }
    }
    val publish = HostInterfaces.method(loader, "apple.lyrics.publish.v1", known = {
        type.declaredMethods.single { it.name == "buildTimeRangeToLyricsMap" }
    }, validate = { it.parameterCount == 1 }) {
        type.declaredMethods.filter { method ->
            method.parameterCount == 1 && runCatching {
                val song = method.parameterTypes[0].getMethod("get").returnType
                song.getMethod("getTiming"); song.getMethod("getSections")
                song.getMethod("getAdamId"); song.getMethod("getQueueId")
            }.isSuccess
        }
    }.apply { isAccessible = true }
}
