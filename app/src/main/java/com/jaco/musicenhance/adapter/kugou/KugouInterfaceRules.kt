package com.jaco.musicenhance.adapter.kugou

import android.app.Activity
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.inClass
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.reaches
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.shape
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.query.enums.StringMatchType

/** Semantic relationships checked in ordinary Kugou 20.8.2 / 20.8.4. */
internal object KugouInterfaceRules {
    fun navigation(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kugou.navigation.v1", {
            loader.loadClass(DELEGATE).declaredMethods.single {
                it.name == "i1" && !it.isBridge && it.parameterCount == 7 && it.parameterTypes[1] == Class::class.java
            }
        }, { !it.isBridge && it.parameterCount == 7 && it.parameterTypes[1] == Class::class.java &&
            it.parameterTypes[2] == android.os.Bundle::class.java && it.returnType == Void.TYPE },
    ) {
        methods { matcher { declaredClass(DELEGATE); paramCount(7); usingEqStrings("startFragment return") } }
            .map { it.getMethodInstance(loader) }
    }

    fun activity(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kugou.navigation.activity.v1", { loader.loadClass(DELEGATE).getMethod("H") },
        { it.parameterCount == 0 && Activity::class.java.isAssignableFrom(it.returnType) },
    ) {
        // This delegate exposes one Activity accessor; restrict to the verified navigation owner.
        inClass(navigation(loader).declaringClass.name) { paramTypes() }.filter { method ->
            method.paramCount == 0 && method.isMethod &&
                Activity::class.java.isAssignableFrom(method.getMethodInstance(loader).returnType)
        }.map { it.getMethodInstance(loader) }
    }

    fun currentSong(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.current-song.v1", known,
        { it.shape(emptyList(), loader.loadClass(KugouInterfaceFeatures.WRAPPER).name, true) },
    ) {
        // The native favorite click reads the current wrapper and sets its KGMusic target state.
        methods { searchPackages("com.kugou.android.app.player.flip"); matcher {
            invokeMethods { add { name("setTargetFavState"); declaredClass(KugouInterfaceFeatures.MUSIC) } }
        } }
            .filter { it.invokes.any { call -> call.name == "setTargetFavState" && call.className == KugouInterfaceFeatures.MUSIC } }
            .flatMap { it.invokes }.filter { it.className == KugouInterfaceFeatures.UTILITY &&
                it.shape(emptyList(), KugouInterfaceFeatures.WRAPPER, true) }
            .distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }

    fun service(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.service.v1", known,
        { it.shape(emptyList(), loader.loadClass(KugouInterfaceFeatures.SERVICE).name, true) },
    ) {
        methods { matcher { declaredClass(KugouInterfaceFeatures.UTILITY);
            addUsingString("PlaybackServiceUtil.setPlayMode:", StringMatchType.Contains) } }.flatMap { it.invokes }
            .filter { it.className == KugouInterfaceFeatures.UTILITY && it.shape(emptyList(), KugouInterfaceFeatures.SERVICE, true) }
            .distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }

    fun repeatSetter(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.repeat.set.v1", known,
        { it.shape(listOf("int", "boolean"), "void", true) },
    ) {
        methods { matcher { declaredClass(KugouInterfaceFeatures.UTILITY); paramTypes("int", "boolean");
            returnType("void"); addUsingString("PlaybackServiceUtil.setPlayMode:", StringMatchType.Contains) } }
            .map { it.getMethodInstance(loader) }
    }

    fun image(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.artwork.image.v1", known, { it.shape(emptyList(), "java.lang.String", false) },
    ) {
        inClass(KugouInterfaceFeatures.WRAPPER) { paramTypes(); returnType("java.lang.String") }.filter {
            it.shape(emptyList(), "java.lang.String", false) && it.invokes.any { call ->
                call.name == "getImgUrl" && call.className == KugouInterfaceFeatures.MUSIC }
        }.map { it.getMethodInstance(loader) }
    }

    fun nativeMusic(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.favorite.music.v1", known,
        { it.shape(emptyList(), loader.loadClass(KugouInterfaceFeatures.MUSIC).name, false) },
    ) {
        inClass(KugouInterfaceFeatures.WRAPPER) { paramTypes(); returnType(KugouInterfaceFeatures.MUSIC) }.filter {
            it.shape(emptyList(), KugouInterfaceFeatures.MUSIC, false) &&
                it.usingFields.any { field -> field.field.typeName == KugouInterfaceFeatures.MUSIC } &&
                it.invokes.none { call -> call.className.startsWith("com.kugou.") }
        }.map { it.getMethodInstance(loader) }
    }

    fun favoriteQuery(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.favorite.query.v1", known,
        { it.shape(listOf("long", "java.lang.String", "java.lang.String"), "int", false) },
    ) {
        // Preserve the native favorite-cache index semantics; other queries use different databases.
        methods { searchPackages("com.kugou.android.app.player.flip"); matcher {
            invokeMethods { add { name("setHasFav"); paramTypes("boolean") } }
        } }.flatMap { it.invokes }.filter { it.className == FAVORITES &&
            it.shape(listOf("long", "java.lang.String", "java.lang.String"), "int", false) }
            .distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }

    fun favoriteToggle(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.favorite.toggle.v1", known, {
            it.parameterCount == 5 && it.parameterTypes[1] == loader.loadClass(KugouInterfaceFeatures.MUSIC) &&
                it.parameterTypes[2] == String::class.java && it.parameterTypes[3] == loader.loadClass(FEES) &&
                it.parameterTypes[4] == Int::class.javaPrimitiveType && it.returnType == Void.TYPE
        },
    ) {
        inClass(FAVORITES) { paramCount(5); returnType("void") }.filter {
            it.paramCount == 5 && it.paramTypeNames.drop(1) == listOf(KugouInterfaceFeatures.MUSIC, "java.lang.String", FEES, "int") &&
                it.returnTypeName == "void" && it.reaches(3) { call ->
                    call.usingStrings.any { text -> "addToMyFav: hash: " in text } }
        }.map { it.getMethodInstance(loader) }
    }

    fun favoriteInstance(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.favorite.instance.v1", known, { it.shape(emptyList(), loader.loadClass(FAVORITES).name, true) },
    ) {
        inClass(FAVORITES) { paramTypes(); returnType(FAVORITES) }.filter { it.shape(emptyList(), FAVORITES, true) &&
            it.usingFields.any { field -> field.field.typeName == FAVORITES && Modifier.isStatic(field.field.modifiers) } }
            .map { it.getMethodInstance(loader) }
    }

    fun queueMethod(loader: ClassLoader, key: String, known: () -> Method): Method = HostInterfaces.method(
        loader, "kugou.queue.$key.v1", known,
        { if (key == "range") it.shape(listOf("int", "int"), "[L${loader.loadClass(KugouInterfaceFeatures.WRAPPER).name};", true)
            else it.shape(emptyList(), "int", true) },
    ) {
        when (key) {
            "mode" -> inClass(KugouInterfaceFeatures.UTILITY) { paramTypes(); returnType("int") }.filter {
                it.shape(emptyList(), "int", true) && it.invokes.any { call -> call.className == KugouInterfaceFeatures.SERVICE && call.name == "getPlayMode" }
            }
            "range" -> inClass(KugouInterfaceFeatures.UTILITY) { paramTypes("int", "int"); returnType("${KugouInterfaceFeatures.WRAPPER}[]") }.filter {
                it.shape(listOf("int", "int"), "${KugouInterfaceFeatures.WRAPPER}[]", true) &&
                    it.invokes.any { call -> call.className == KugouInterfaceFeatures.SERVICE &&
                        call.paramTypeNames == listOf("int", "int") && call.returnTypeName == "${KugouInterfaceFeatures.WRAPPER}[]" }
            }
            // Size/index have multiple identical signatures and no verified independent semantic anchor.
            else -> emptyList()
        }.map { it.getMethodInstance(loader) }
    }

    private const val DELEGATE = "com.kugou.common.base.ViewPagerFrameworkDelegate"
    private const val FAVORITES = "com.kugou.common.utils.MyFavUtils"
    private const val FEES = "com.kugou.common.musicfees.MusicFeesInterface"
}
