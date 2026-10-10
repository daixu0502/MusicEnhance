package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.inClass
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.shape
import java.lang.reflect.Method
import org.luckypray.dexkit.query.enums.StringMatchType

/** Concept edition contracts and independent semantic fallbacks, verified in 5.2.9. */
internal class KugouLiteApi(private val loader: ClassLoader) {
    val utility = loader.loadClass(KugouInterfaceFeatures.UTILITY)
    val wrapper by lazy { loader.loadClass(KugouInterfaceFeatures.WRAPPER) }
    val serviceType by lazy { loader.loadClass(KugouInterfaceFeatures.SERVICE) }
    val currentSong: Method get() = HostInterfaces.method(
        loader, "kugoulite.current-song.v1", { utility.getMethod("s0") },
        { it.shape(emptyList(), wrapper.name, true) },
    ) {
        methods { searchPackages("com.kugou"); matcher { usingEqStrings("AI音色暂不支持收藏"); paramTypes("boolean"); returnType("void") } }
            .flatMap { it.invokes }.filter { it.className == KugouInterfaceFeatures.UTILITY &&
                it.shape(emptyList(), KugouInterfaceFeatures.WRAPPER, true) }
            .distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }
    val service: Method get() = HostInterfaces.method(
        loader, "kugoulite.service.v1", { utility.getMethod("P0") },
        { it.shape(emptyList(), serviceType.name, true) },
    ) {
        methods { matcher { declaredClass(KugouInterfaceFeatures.UTILITY); paramTypes();
            returnType(KugouInterfaceFeatures.SERVICE); addUsingString("call getDefault from fore process times:", StringMatchType.Contains) } }
            .map { it.getMethodInstance(loader) }
    }
    val image: Method get() = HostInterfaces.method(
        loader, "kugoulite.artwork.image.v1", { wrapper.getMethod("b1") },
        { it.shape(emptyList(), "java.lang.String", false) },
    ) {
        inClass(KugouInterfaceFeatures.WRAPPER) { paramTypes(); returnType("java.lang.String") }.filter { it.shape(emptyList(), "java.lang.String", false) &&
            it.invokes.any { call -> call.className == KugouInterfaceFeatures.MUSIC && call.name == "getImgUrl" } }
            .map { it.getMethodInstance(loader) }
    }
    val readMode: Method get() = HostInterfaces.method(
        loader, "kugoulite.repeat.read.v1", { serviceType.getMethod("n0") },
        { it.shape(emptyList(), "int", false) },
    ) {
        inClass(KugouInterfaceFeatures.UTILITY) { paramTypes(); returnType("com.kugou.common.player.manager.PlayMode") }.filter {
            it.shape(emptyList(), "com.kugou.common.player.manager.PlayMode", true) &&
                it.usingFields.any { field -> field.field.className == "com.kugou.common.player.manager.PlayMode" &&
                    field.field.name == "REPEAT_SINGLE" }
        }.flatMap { it.invokes }.filter { it.className == KugouInterfaceFeatures.SERVICE && it.shape(emptyList(), "int", false) }
            .distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }
    val setMode: Method get() = HostInterfaces.method(
        loader, "kugoulite.repeat.set.v1", { utility.getMethod("S6", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) },
        { it.shape(listOf("int", "boolean"), "void", true) },
    ) {
        // The native queued mode change captures a field explicitly named val$playMode.
        fields { searchPackages("com.kugou.framework.service.util"); matcher { name("val\$playMode"); type("int") } }.flatMap { it.readers }
            .filter { it.name == "run" && it.paramCount == 0 }.flatMap { it.invokes }
            .filter { it.className == KugouInterfaceFeatures.UTILITY && it.shape(listOf("int", "boolean"), "void", true) }
            .distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }

    fun queue(key: String): Method = HostInterfaces.method(
        loader, "kugoulite.queue.$key.v1", {
            if (key == "range") utility.getMethod("J1", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            else utility.getMethod(when (key) { "size" -> "V1"; "position" -> "N1"; "mode" -> "M1"; else -> error(key) })
        }, { if (key == "range") it.shape(listOf("int", "int"), "[L${wrapper.name};", true)
            else it.shape(emptyList(), "int", true) },
    ) {
        when (key) {
            "mode" -> inClass(KugouInterfaceFeatures.UTILITY) { paramTypes(); returnType("int") }.filter { method ->
                method.shape(emptyList(), "int", true) && method.invokes.any { call ->
                    call.getMethodInstance(loader) == readMode }
            }
            "range" -> inClass(KugouInterfaceFeatures.UTILITY) { paramTypes("int", "int"); returnType("${KugouInterfaceFeatures.WRAPPER}[]") }.filter { method ->
                method.shape(listOf("int", "int"), "${KugouInterfaceFeatures.WRAPPER}[]", true) &&
                    method.invokes.any { call -> call.className == KugouInterfaceFeatures.SERVICE &&
                        call.shape(listOf("int", "int"), "${KugouInterfaceFeatures.WRAPPER}[]", false) }
            }
            else -> emptyList()
        }.map { it.getMethodInstance(loader) }
    }

    fun verified(key: String, owner: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>): Method =
        KugouInterfaceFeatures.verified(loader, "kugoulite", key, owner, name, result, *parameters)
}
