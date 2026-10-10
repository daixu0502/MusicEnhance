package com.jaco.musicenhance.adapter.salt

import android.media.session.MediaSession
import com.jaco.musicenhance.adapter.discovery.DexSearch
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** The Android callback names distinguish actions even when all host wrappers are void(). */
internal class SaltControlApi(private val loader: ClassLoader, private val hint: SaltApi) {
    private val controller = loader.loadClass("com.salt.music.service.MusicController")
    val callback by lazy { HostInterfaces.field(loader, "salt.controls.callback.v1", known = { controller.getField("\u078b") },
        validate = { Modifier.isStatic(it.modifiers) }) {
        val types = callbackMethods("onPlay").flatMap { wrapper ->
            wrapper.invokes.filter { call -> call.paramCount == 0 && call.returnTypeName == "void" &&
                wrapper.usingFields.any { it.field.typeName == call.className } }
        }.map { it.getMethodInstance(loader).declaringClass }.distinct()
        controller.declaredFields.filter { field -> Modifier.isStatic(field.modifiers) && types.any { it.isAssignableFrom(field.type) } }
    } }

    val currentSong by lazy { HostInterfaces.field(loader, "salt.song.flow.v1", known = { controller.getField("\u07a0") },
        validate = { Modifier.isStatic(it.modifiers) && hasValue(it.type) }) {
        actionMethods("onPlay", 0).flatMap { it.usingFields }.map { it.field }
            .filter { it.className == controller.name }.map { it.getFieldInstance(loader) }
            .filter { hasValue(it.type) }.distinct()
    } }

    val play by lazy { action("play", hint.playMethod, "onPlay") }
    val pause by lazy { action("pause", hint.pauseMethod, "onPause") }
    val next by lazy { action("next", hint.nextMethod, "onSkipToNext") }
    val previous by lazy { action("previous", hint.previousMethod, "onSkipToPrevious") }
    val seek by lazy { HostInterfaces.method(loader, "salt.controls.seek.v1", known = {
        callback.type.getMethod("\u052e", Long::class.javaPrimitiveType)
    }, validate = { it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) && it.returnType == Void.TYPE }) {
        actionMethods("onSeekTo", 1).map { it.getMethodInstance(loader) }
    } }

    val playing by lazy { HostInterfaces.method(loader, "salt.controls.playing.v1", known = { controller.getMethod(hint.playingMethod) },
        validate = { Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0 }) {
        // A semantic getter is safe when retained. Other boolean() methods cannot be guessed.
        methods { matcher { declaredClass = controller.name; name = "isPlaying"; paramCount = 0; returnType = "boolean" } }
            .map { it.getMethodInstance(loader) }
    } }

    val repeat by lazy { HostInterfaces.method(loader, "salt.controls.cycle-repeat.v1", known = { controller.getMethod(hint.repeatMethod) },
        validate = { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE }) {
        val enums = methods { matcher { name = "<clinit>"; usingEqStrings("CIRCLE", "REPEAT_ONE", "RANDOM") } }.map { it.className }
        methods { matcher { declaredClass = controller.name; paramCount = 0; returnType = "void" } }
            .filter { method -> method.usingFields.filter { it.field.className in enums }.map { it.field.name }.distinct().size >= 3 &&
                method.invokes.any { it.className == controller.name && it.paramTypeNames.size == 1 && it.paramTypeNames[0] in enums } }
            .map { it.getMethodInstance(loader) }
    } }

    val repeatFlow by lazy { HostInterfaces.field(loader, "salt.controls.repeat-flow.v1", known = { controller.getField("\u07a4") },
        validate = { Modifier.isStatic(it.modifiers) && hasValue(it.type) }) {
        methods { matcher { declaredClass = controller.name; name = repeat.name; paramCount = 0 } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.className == controller.name }
            .map { it.getFieldInstance(loader) }.filter { hasValue(it.type) }.distinct()
    } }

    val cueOffset by lazy { HostInterfaces.field(loader, "salt.lyrics.cue-offset.v1", known = {
        loader.loadClass("androidx.media3.${hint.cueClass}").getField("\u0868")
    }, validate = { Modifier.isStatic(it.modifiers) && it.type == Long::class.javaPrimitiveType }) {
        val target = actionMethods("onSeekTo", 1)
        target.flatMap { it.invokes }.filter { it.paramTypeNames == listOf("long") && it.returnTypeName == "void" }
            .filter { method -> method.invokes.any { it.className == "android.media.MediaPlayer" && it.name == "seekTo" } &&
                method.invokes.any { it.className == "com.un4seen.bass.BASS" && it.name == "BASS_ChannelSeconds2Bytes" } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.typeName == "long" && Modifier.isStatic(it.modifiers) }
            .map { it.getFieldInstance(loader) }.distinct()
    } }

    private fun action(key: String, oldName: String, callbackName: String): Method = HostInterfaces.method(loader,
        "salt.controls.$key.v1", known = { controller.getMethod(oldName) },
        validate = { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE }) {
        val targets = actionMethods(callbackName, 0)
        methods { matcher { declaredClass = controller.name; paramCount = 0; returnType = "void" } }
            .filter { method -> method.usingFields.any { it.field.name == callback.name && it.field.className == controller.name } &&
                method.invokes.any { call -> targets.any { target -> call.name == target.name && call.paramTypeNames == target.paramTypeNames } } }
            .map { it.getMethodInstance(loader) }
    }

    private fun DexSearch.callbackMethods(name: String) = methods { matcher {
        declaredClass { superClass(MediaSession.Callback::class.java.name) }
        this.name = name; paramCount = if (name == "onSeekTo") 1 else 0; returnType = "void"
    } }
        .filter { method -> MediaSession.Callback::class.java.isAssignableFrom(method.getMethodInstance(loader).declaringClass) }

    private fun DexSearch.actionMethods(name: String, params: Int) = callbackMethods(name).flatMap { wrapper ->
        wrapper.invokes.filter { call -> call.paramCount == params && call.returnTypeName == "void" &&
            wrapper.usingFields.any { it.field.typeName == call.className } }
    }.flatMap { called ->
        methods { matcher { declaredClass = callback.type.name; this.name = called.name; paramCount = params; returnType = "void" } }
    }.distinctBy { it.descriptor }

    private fun hasValue(type: Class<*>) = runCatching { type.getMethod("getValue").parameterCount == 0 }.getOrDefault(false)
}
