package com.jaco.musicenhance.adapter.salt

import java.lang.reflect.Modifier

/** Obfuscated contracts verified in 12.3.2 and 12.4.1-play; names are not stable across releases. */
internal enum class SaltApi(
    val version: String,
    val playingMethod: String,
    val playMethod: String,
    val pauseMethod: String,
    val nextMethod: String,
    val previousMethod: String,
    val repeatMethod: String,
    val coverPairClass: String,
    val coverPresentField: String,
    val coverBitmapField: String,
    val cueClass: String,
    val lyricsClass: String,
    val lyricLineClass: String,
    val sheetClass: String,
    val animateClass: String,
    val animateMethod: String,
    val unitClass: String,
) {
    V12_3_2("12.3.2", "\u078c", "\u0794", "\u0792", "\u0798", "\u079a", "\u0780",
        "eh2", "\u052e", "\u052f", "jd3", "uc1", "ed1", "\u07e8", "f52", "\u0781", "fp4"),
    V12_4_1("12.4.1-play", "\u078b", "\u0793", "\u0791", "\u0797", "\u0799", "\u0620",
        "fi2", "\u052c", "\u052d", "ue3", "sc1", "id1", "\u0cb6", "\u093d", "\u0782", "cr4");

    companion object {
        /** Only a hint for the version-gated resolver, never a declaration of compatibility. */
        fun hint(loader: ClassLoader): SaltApi = runCatching { resolve(loader) }.getOrDefault(V12_4_1)

        fun resolve(loader: ClassLoader): SaltApi {
            val controller = loader.loadClass("com.salt.music.service.MusicController")
            return entries.singleOrNull { api ->
                runCatching {
                    val playing = controller.getMethod(api.playingMethod)
                    playing.returnType == Boolean::class.javaPrimitiveType && Modifier.isStatic(playing.modifiers) &&
                        listOf(api.playMethod, api.pauseMethod, api.nextMethod, api.previousMethod, api.repeatMethod).all {
                            val method = controller.getMethod(it)
                            method.returnType == Void.TYPE && Modifier.isStatic(method.modifiers)
                        }
                }.getOrDefault(false)
            } ?: error("Unsupported Salt playback contract")
        }
    }
}
