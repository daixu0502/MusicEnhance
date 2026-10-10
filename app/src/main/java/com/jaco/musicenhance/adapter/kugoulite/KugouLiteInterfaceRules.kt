package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.inClass
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.shape
import java.lang.reflect.Method
import org.luckypray.dexkit.query.enums.StringMatchType

internal object KugouLiteInterfaceRules {
    fun navigation(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kugoulite.navigation.v1", {
            loader.loadClass(DELEGATE).declaredMethods.single {
                it.name == "Q1" && it.parameterCount == 8 && it.parameterTypes[1] == Class::class.java &&
                    it.parameterTypes.last() == Runnable::class.java && it.returnType == Void.TYPE
            }
        }, { it.parameterCount == 8 && it.parameterTypes[1] == Class::class.java &&
            it.parameterTypes[2] == android.os.Bundle::class.java && it.parameterTypes.last() == Runnable::class.java &&
            it.returnType == Void.TYPE },
    ) {
        methods { matcher { declaredClass(DELEGATE); paramCount(8); returnType("void");
            addUsingString("startFragment:cls=", StringMatchType.Contains)
            addUsingString("fromRestore=", StringMatchType.Contains)
            addUsingString("viewpager_framework_delegate_open_two_fragment", StringMatchType.Equals) } }
            .map { it.getMethodInstance(loader) }
    }

    fun favoriteToggle(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kugoulite.favorite.toggle.v1", {
            loader.loadClass(FAVORITE_DELEGATE).getMethod("u", Boolean::class.javaPrimitiveType)
        }, { it.shape(listOf("boolean"), "void", false) },
    ) {
        methods { searchPackages("com.kugou"); matcher { paramTypes("boolean"); returnType("void"); usingEqStrings("AI音色暂不支持收藏") } }
            .filter { it.className.startsWith("com.kugou.") &&
                it.invokes.any { call -> call.className == KugouInterfaceFeatures.UTILITY &&
                    call.returnTypeName == KugouInterfaceFeatures.WRAPPER } }
            .map { it.getMethodInstance(loader) }
    }

    fun favoriteRelease(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kugoulite.favorite.release.v1", { loader.loadClass(FAVORITE_DELEGATE).getMethod("v") },
        { it.shape(emptyList(), "void", false) },
    ) {
        inClass(favoriteToggle(loader).declaringClass.name) { paramTypes(); returnType("void") }.filter {
            it.shape(emptyList(), "void", false) &&
                it.usingFields.any { field -> field.field.name == "favSubscription" } &&
                it.usingFields.any { field -> field.field.name == "callback" } &&
                it.invokes.none { call -> call.className == "rx.Observable" }
        }.map { it.getMethodInstance(loader) }
    }

    fun favoriteQuery(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kugoulite.favorite.query.v1", {
            loader.loadClass(FAVORITES).getMethod("O", String::class.java, Long::class.javaPrimitiveType)
        }, { it.shape(listOf("java.lang.String", "long"), "boolean", true) },
    ) {
        val loggedQuery = methods { matcher { declaredClass(FAVORITES);
            paramTypes("java.lang.String", "long"); returnType("boolean"); addUsingString("isMyFav: hash", StringMatchType.Contains) } }
        val loggedDescriptors = loggedQuery.map { it.descriptor }.toSet()
        // O includes native cache lookup and then delegates to the logged database lookup L.
        inClass(FAVORITES) { paramTypes("java.lang.String", "long"); returnType("boolean") }.filter { method ->
            method.shape(listOf("java.lang.String", "long"), "boolean", true) &&
                method.invokes.any { it.descriptor in loggedDescriptors } &&
                method.invokes.any { call -> call.className == FAVORITES && call.returnTypeName == FAVORITES }
        }.map { it.getMethodInstance(loader) }
    }

    fun aiCheck(loader: ClassLoader, timbre: Boolean): Method = HostInterfaces.method(
        loader, "kugoulite.favorite.${if (timbre) "ai-timbre" else "ai-song"}.v1", {
            loader.loadClass("ra0.b").getMethod(if (timbre) "o" else "j", loader.loadClass(ISONG))
        }, { it.shape(listOf(loader.loadClass(ISONG).name), "boolean", true) },
    ) {
        val toggle = favoriteToggle(loader)
        inClass(toggle.declaringClass.name) { name(toggle.name); paramTypes("boolean"); returnType("void") }.filter { it.isMethod && it.getMethodInstance(loader) == toggle }
            .flatMap { it.invokes }.filter { method ->
                method.shape(listOf(ISONG), "boolean", true) &&
                    if (timbre) method.invokes.any { it.className == ISONG && it.name == "getAudioType" } &&
                        method.invokes.none { it.name == "getAISongId" }
                    else method.invokes.count { call -> call.shape(listOf(ISONG), "boolean", true) &&
                        call.invokes.any { it.className == ISONG && it.name == "getAISongId" } } == 2
            }.map { it.getMethodInstance(loader) }
    }

    fun providerCurrent(loader: ClassLoader, provider: Class<*>): Method = HostInterfaces.method(
        loader, "kugoulite.favorite.provider-current.v1", { provider.getMethod("j0") },
        { it.shape(emptyList(), loader.loadClass(KugouInterfaceFeatures.WRAPPER).name, false) },
    ) {
        inClass(favoriteToggle(loader).declaringClass.name).flatMap { it.invokes }.filter {
            it.className == provider.name && it.shape(emptyList(), KugouInterfaceFeatures.WRAPPER, false) &&
                it.callers.any { call -> call.invokes.any { invoked ->
                    invoked.className == "com.kugou.android.app.player.statistic.b" } }
        }.distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }

    fun providerContribution(loader: ClassLoader, provider: Class<*>): Method = HostInterfaces.method(
        loader, "kugoulite.favorite.provider-contribution.v1", { provider.getMethod("l0") },
        { it.shape(emptyList(), loader.loadClass("com.kugou.framework.database.contribution.entity.ContributionEntity").name, false) },
    ) {
        inClass(favoriteToggle(loader).declaringClass.name).flatMap { it.invokes }.filter {
            it.className == provider.name &&
                it.shape(emptyList(), "com.kugou.framework.database.contribution.entity.ContributionEntity", false)
        }.distinctBy { it.descriptor }.map { it.getMethodInstance(loader) }
    }

    private const val DELEGATE = "com.kugou.common.base.ViewPagerFrameworkDelegate"
    private const val FAVORITE_DELEGATE = "com.kugou.android.app.player.song.delegate.SongFavDelegate"
    private const val FAVORITES = "com.kugou.common.utils.MyFavUtils"
    private const val ISONG = "com.kugou.android.common.entity.ISong"
}
