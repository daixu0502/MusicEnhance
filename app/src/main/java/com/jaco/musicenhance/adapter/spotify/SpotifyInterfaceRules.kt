package com.jaco.musicenhance.adapter.spotify

import android.app.PendingIntent
import android.media.session.MediaSession
import android.os.Bundle
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Spotify 9.1.90.2270 contracts. Discovery inspects DEX; it never calls a candidate. */
internal object SpotifyInterfaceRules {
    fun stateBuilder(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "spotify.state.builder.v1", {
            loader.loadClass("com.spotify.player.model.AutoValue_PlayerState\$Builder").getDeclaredMethod("build")
        }, { method ->
            method.parameterCount == 0 && method.returnType.name == PLAYER_STATE &&
                method.declaringClass.methods.any { it.name == "track" && it.parameterCount == 1 } &&
                method.declaringClass.methods.any { it.name == "options" && it.parameterCount == 1 }
        },
    ) {
        methods { matcher { name("build"); paramTypes(); returnType(PLAYER_STATE) } }
            .map { it.getMethodInstance(loader) }
    }

    fun callbackType(loader: ClassLoader): Class<*> = HostInterfaces.type(
        loader, "spotify.media.callback.v1", { loader.loadClass("p.zpf0") },
        { MediaSession.Callback::class.java.isAssignableFrom(it) &&
            it.getMethod("onCustomAction", String::class.java, Bundle::class.java).returnType == Void.TYPE },
    ) {
        methods { matcher {
            name("onCustomAction"); paramTypes("java.lang.String", "android.os.Bundle"); returnType("void")
            usingStrings("android.support.v4.media.session.action.SET_REPEAT_MODE",
                "android.support.v4.media.session.action.SET_SHUFFLE_MODE",
                "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE",
                "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE")
        } }.map { it.getClassInstance(loader) }.distinct()
    }

    fun favoriteAction(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "spotify.favorite.pending-intent.v1", {
            loader.loadClass("p.yfr0").getMethod("a", Bundle::class.java, String::class.java)
        }, { it.returnType == PendingIntent::class.java &&
            it.parameterTypes.contentEquals(arrayOf(Bundle::class.java, String::class.java)) },
    ) {
        methods { matcher {
            paramTypes("android.os.Bundle", "java.lang.String"); returnType("android.app.PendingIntent")
            usingStrings(ADD_FAVORITE, REMOVE_FAVORITE, "uri", "context_source", "mode")
        } }.map { it.getMethodInstance(loader) }
    }

    fun lyricsService(loader: ClassLoader, version: Int): Class<*> = HostInterfaces.type(
        loader, "spotify.lyrics.service-v$version.v1", { loader.loadClass(if (version == 2) "p.hjd0" else "p.zod0") },
        ::isLyricsService,
    ) {
        val endpoints = methods { matcher {
            returnType(SINGLE); paramTypes("java.lang.String", "java.lang.String")
            annotations { add { usingStrings("color-lyrics/v$version/download/track/{trackId}") } }
        } }.map { it.className }.toSet()
        endpoints.flatMap { endpoint ->
            fields { matcher { type(endpoint) } }.map { it.getFieldInstance(loader).declaringClass }
        }.filter(::isLyricsService).distinct()
    }

    fun loadLyrics(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "spotify.lyrics.load.${owner.name}.v1", { owner.getMethod("b", String::class.java, String::class.java) },
        { it.returnType.name == SINGLE && it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java)) },
    ) {
        // The declaring type is already anchored to the native authenticated lyric endpoint.
        methods { matcher { declaredClass(owner.name); paramTypes("java.lang.String", "java.lang.String"); returnType(SINGLE) } }
            .map { it.getMethodInstance(loader) }
    }

    fun providerConstructor(loader: ClassLoader): Constructor<*> = HostInterfaces.constructor(
        loader, "spotify.lyrics.application-provider.v1", {
            loader.loadClass("p.xzo").getConstructor(loader.loadClass("p.yzo"), Int::class.javaPrimitiveType)
        }, { it.parameterCount == 2 && it.parameterTypes[1] == Int::class.javaPrimitiveType },
    ) {
        // The shared DI switch ordinal is not semantic. Unknown builds capture naturally created
        // endpoint services instead; never select a different provider by a reused numeric case.
        emptyList()
    }

    fun optionalValue(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "spotify.state.optional.${owner.name}.v1", { owner.getMethod("h") },
        { it.parameterCount == 0 && it.returnType == Any::class.java && !Modifier.isStatic(it.modifiers) },
    ) {
        val present = methods { matcher {
            declaredClass(owner.name); name("toString"); paramTypes(); returnType("java.lang.String"); usingStrings("Optional.of(")
        } }
        val values = present.flatMap { it.usingFields }.map { it.field }
            .filter { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Object" }.map { it.descriptor }.toSet()
        if (values.size != 1) emptyList() else methods { matcher {
            declaredClass(owner.name); paramTypes(); returnType("java.lang.Object")
        } }.filter { it.usingFields.any { field -> field.field.descriptor in values } }
            .map { it.getMethodInstance(loader) }
    }

    fun responseLyrics(loader: ClassLoader, response: Class<*>): Field = HostInterfaces.field(
        loader, "spotify.lyrics.response-body.${response.name}.v1", { response.getField("a") },
        { isLyricsBody(it.type) },
    ) {
        fields { matcher { declaredClass(response.name) } }.map { it.getFieldInstance(loader) }
            .filter { !Modifier.isStatic(it.modifiers) && isLyricsBody(it.type) }
    }

    fun lyricRows(loader: ClassLoader, body: Class<*>): Field = HostInterfaces.field(
        loader, "spotify.lyrics.rows.${body.name}.v1", { body.getField("a") },
        { List::class.java.isAssignableFrom(it.type) },
    ) {
        // A body contains timed rows, translations and languages, all typed List. Without a
        // unique element contract, choosing one by field order would misinterpret another list.
        emptyList()
    }

    fun lyricSync(loader: ClassLoader, body: Class<*>): Field = HostInterfaces.field(
        loader, "spotify.lyrics.sync.${body.name}.v1", { body.getField("b") },
        { it.type == Int::class.javaPrimitiveType },
    ) {
        // Synchronization mode and lyric access level share the same numeric range. A reused
        // field name or a plausible int value is insufficient to identify the timing contract.
        emptyList()
    }

    fun rowStart(loader: ClassLoader, row: Class<*>): Field = rowField(loader, row, "start", "a", Long::class.javaPrimitiveType!!)
    fun rowWords(loader: ClassLoader, row: Class<*>): Field = rowField(loader, row, "words", "b", String::class.java)

    private fun rowField(loader: ClassLoader, row: Class<*>, meaning: String, knownName: String, type: Class<*>): Field = HostInterfaces.field(
        loader, "spotify.lyrics.row-$meaning.${row.name}.v1", { row.getField(knownName) }, { it.type == type },
    ) {
        val contract = row.declaredConstructors.any { it.parameterTypes.contentEquals(
            arrayOf(Long::class.javaPrimitiveType, String::class.java, java.util.ArrayList::class.java)) }
        if (!contract) emptyList() else fields { matcher { declaredClass(row.name); type(type.name) } }
            .map { it.getFieldInstance(loader) }.filter { !Modifier.isStatic(it.modifiers) }
    }

    private fun isLyricsService(type: Class<*>): Boolean = !type.isInterface && type.interfaces.any { contract ->
        contract.methods.any { it.parameterTypes.contentEquals(arrayOf(String::class.java)) && it.returnType.name == SINGLE } &&
            contract.methods.any { it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java)) && it.returnType.name == SINGLE }
    }

    private fun isLyricsBody(type: Class<*>): Boolean {
        val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        return fields.count { it.type == List::class.java } == 3 && fields.count { it.type == Int::class.javaPrimitiveType } == 2 &&
            fields.count { it.type == String::class.java } == 1 && fields.count { it.type == Boolean::class.javaPrimitiveType } == 1
    }

    const val ADD_FAVORITE = "com.spotify.app.music.scopes.utils.action.player.NOTIFICATION_ADD_TO_COLLECTION"
    const val REMOVE_FAVORITE = "com.spotify.app.music.scopes.utils.action.player.NOTIFICATION_REMOVE_FROM_COLLECTION"
    private const val SINGLE = "io.reactivex.rxjava3.core.Single"
    private const val PLAYER_STATE = "com.spotify.player.model.PlayerState"
}
