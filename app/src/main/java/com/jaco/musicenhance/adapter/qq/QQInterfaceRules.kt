package com.jaco.musicenhance.adapter.qq

import android.app.Activity
import android.os.Bundle
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Field

/** Features verified in QQ 20.8.5.8 and 20.9.0.8. No candidate is executed during discovery. */
internal object QQInterfaceRules {
    fun songTitle(loader: ClassLoader, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.song.title.v1", { song.getMethod("j3") },
        { it.parameterCount == 0 && it.returnType == String::class.java },
    ) {
        methods { matcher { declaredClass(song.name); usingStrings("[setFingerMatchFail]") } }
            .flatMap { it.invokes }.filter { it.className == song.name && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .map { it.getMethodInstance(loader) }.distinct()
    }

    fun songId(loader: ClassLoader, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.song.playback-identity.v1", { song.getMethod("C3") },
        { it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType },
    ) {
        // QQ's collection protocol uses the cloud ID. Its playback identity wrapper chooses
        // between that ID and the matched local ID using two native song-type predicates.
        val cloud = methods { matcher {
            name("addToILike"); returnType("int"); usingStrings("[addToILike] ret:")
        } }.filter { it.paramTypeNames.firstOrNull() == song.name }.flatMap { it.invokes }
            .filter { it.className == song.name && it.paramCount == 0 && it.returnTypeName == "long" }
            .map { it.descriptor }.toSet()
        if (cloud.size != 1) emptyList() else methods { matcher { declaredClass(song.name); paramTypes(); returnType("long") } }
            .filter { candidate ->
                val own = candidate.invokes.filter { it.className == song.name && it.paramCount == 0 }.distinctBy { it.descriptor }
                own.count { it.returnTypeName == "long" } == 2 && own.count { it.returnTypeName == "boolean" } == 2 &&
                    own.any { it.descriptor in cloud }
            }.map { it.getMethodInstance(loader) }
    }

    fun users(loader: ClassLoader, song: Class<*>): Class<*> = HostInterfaces.type(
        loader, "qq.favorite.store.v1", { loader.loadClass("com.tencent.qqmusic.business.userdata.UserDataManager") },
        { type -> runCatching {
            type.getMethod("isILike", song).returnType == Boolean::class.javaPrimitiveType &&
                type.getMethod("isILikeDataInit").returnType == Boolean::class.javaPrimitiveType &&
                type.getMethod("addToILike", song).returnType == Int::class.javaPrimitiveType &&
                type.getMethod("deleteFromILike", song).returnType == Boolean::class.javaPrimitiveType
        }.getOrDefault(false) },
    ) {
        methods { matcher { name("isILike"); paramTypes(song.name); returnType("boolean") } }
            .map { it.getClassInstance(loader) }.distinct()
    }

    fun playerService(loader: ClassLoader, service: Class<*>): Field = HostInterfaces.field(
        loader, "qq.repeat.service.v1", {
            loader.loadClass("com.tencent.qqmusicplayerprocess.servicenew.k").declaredFields.single {
                Modifier.isStatic(it.modifiers) && it.type == service
            }
        }, { Modifier.isStatic(it.modifiers) && it.type == service },
    ) {
        val connections = methods { matcher {
            name("onServiceConnected"); paramTypes("android.content.ComponentName", "android.os.IBinder"); returnType("void")
            usingStrings("QQMusicServiceHelperNew", "onServiceConnected() ComponentName:")
        } }
        connections.flatMap { it.usingFields }.map { it.field }
            .filter { Modifier.isStatic(it.modifiers) && it.typeName == service.name }
            .map { it.getFieldInstance(loader) }.distinct()
    }.apply { isAccessible = true }

    fun controlsType(loader: ClassLoader): Class<*> = HostInterfaces.type(
        loader, "qq.playback.facade.v1", { loader.loadClass("com.tencent.qqmusic.common.player.g") },
        { type -> type.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == type } &&
            type.methods.any { it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)) &&
                it.returnType == Long::class.javaPrimitiveType } },
    ) {
        methods {
            searchPackages("com.tencent.qqmusic.common.player")
            matcher { paramTypes("long", "int"); returnType("long") }
        }.filter { candidate ->
            val owner = candidate.getClassInstance(loader)
            owner.interfaces.isEmpty() && owner.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == owner } &&
                candidate.invokes.any { it.paramTypeNames == listOf("long", "int") && it.returnTypeName == "long" &&
                    it.declaredClass?.let { called -> Modifier.isInterface(called.modifiers) } == true }
        }.map { it.getClassInstance(loader) }.distinct()
    }

    fun playback(loader: ClassLoader, owner: Class<*>, operation: String): Method {
        val name = when (operation) { "seek" -> "s"; "resume" -> "r"; else -> "o" }
        val parameters = if (operation == "seek") arrayOf("long", "int") else arrayOf("int")
        val result = when (operation) { "seek" -> "long"; "resume" -> "int"; else -> "void" }
        return HostInterfaces.method(loader, "qq.playback.$operation.v1", {
            owner.getMethod(name, *if (operation == "seek") arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                else arrayOf(Int::class.javaPrimitiveType))
        }, { method -> method.parameterTypes.map { it.name } == parameters.toList() && method.returnType.name == result }) {
            val nativeName = if (operation == "start") "play" else operation
            val implementations = methods {
                searchPackages("com.tencent.qqmusic.common.player")
                matcher { paramTypes(*parameters); returnType(result); usingStrings("FollowListenPlayerGuestImpl", "$nativeName return") }
            }
            val contracts = implementations.flatMap { implementation ->
                implementation.declaredClass?.interfaces.orEmpty().flatMap { contract -> contract.methods }
                    .filter { it.name == implementation.name && it.paramTypeNames == parameters.toList() && it.returnTypeName == result }
            }.map { it.descriptor }.toSet()
            methods { matcher { declaredClass(owner.name); paramTypes(*parameters); returnType(result) } }
                .filter { it.invokes.any { call -> call.descriptor in contracts } }.map { it.getMethodInstance(loader) }
        }
    }

    fun environment(loader: ClassLoader, known: () -> Method): Method = HostInterfaces.method(
        loader, "qq.play-environment.v1", known, { factory ->
            Modifier.isStatic(factory.modifiers) && factory.parameterCount == 0 &&
                factory.returnType.methods.any { it.name == "getPlaySong" && it.parameterCount == 0 &&
                    it.returnType == loader.loadClass(SONG) } && factory.returnType.methods.any { it.name == "getPlayMode" }
        },
    ) {
        methods { matcher { paramTypes(); usingStrings(listOf("MusicProcess.playEnv() can't be called."), StringMatchType.Contains) } }
            .map { it.getMethodInstance(loader) }
    }

    fun artwork(loader: ClassLoader, song: Class<*>, name: String, parameters: Int): Method = HostInterfaces.method(
        loader, "qq.artwork.${if (name == "o") "singer" else if (parameters == 3) "sized" else "album"}.v1",
        { loader.loadClass("com.tencent.qqmusiccommon.appconfig.albumpic.b").getMethod(
            name, *if (parameters == 3) arrayOf(song, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            else arrayOf(song, Int::class.javaPrimitiveType!!),
        ) },
        { Modifier.isStatic(it.modifiers) && it.returnType == String::class.java && it.parameterCount == parameters },
    ) {
        methods { matcher {
            returnType(String::class.java.name)
            paramTypes(*if (parameters == 3) arrayOf(song.name, "int", "int") else arrayOf(song.name, "int"))
        } }.filter { candidate ->
            val strings = calledStrings(candidate, 2)
            if (name == "o") strings.any { "photo_new/T001" in it } && strings.none { "photo_new/T002" in it }
            else strings.any { "photo_new/T002" in it } && strings.any { "photo_new/T001" in it }
        }.map { it.getMethodInstance(loader) }
    }

    fun lyricLoader(loader: ClassLoader): Class<*> = HostInterfaces.type(
        loader, "qq.lyrics.loader.v1", { loader.loadClass("com.tencent.qqmusic.business.lyricnew.load.manager.m") },
        { it.constructors.any { constructor -> constructor.parameterCount == 0 } },
    ) {
        methods { matcher { usingStrings(" [loadBySongInfo] song == null.", "LyricLoadManager") } }
            .map { it.getClassInstance(loader) }.distinct()
    }

    fun loadLyrics(loader: ClassLoader, owner: Class<*>, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.load-song.v1", { owner.getMethod("h", song) },
        { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(song)) },
    ) {
        val entry = methods { matcher {
            declaredClass(owner.name); usingStrings(" [loadBySongInfo] song == null.")
        } }.map { it.descriptor }.toSet()
        methods { matcher { declaredClass(owner.name); paramTypes(song.name); returnType("void") } }
            .filter { it.invokes.any { call -> call.descriptor in entry } }.map { it.getMethodInstance(loader) }
    }

    fun addLyricListener(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.add-listener.v1", {
            owner.getMethod("c", loader.loadClass("com.tencent.qqmusic.business.lyricnew.load.listener.c"))
        }, { it.parameterCount == 1 && it.returnType == Void.TYPE && it.parameterTypes[0].isInterface },
    ) {
        methods { matcher { declaredClass(owner.name); paramCount(1); returnType("void") } }.filter { candidate ->
            val parameter = candidate.paramTypes.singleOrNull() ?: return@filter false
            parameter.methods.any { it.name == "onLoadSuc" && it.paramCount == 1 } &&
                parameter.methods.any { it.name == "onLyricSeek" && it.paramTypeNames == listOf("long", "float") } &&
                candidate.invokes.any { it.name == "add" && it.className in COLLECTIONS }
        }.map { it.getMethodInstance(loader) }
    }

    fun clearLyrics(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.release-loader.v1", { owner.getMethod("e") },
        { it.parameterCount == 0 && it.returnType == Void.TYPE },
    ) {
        methods { matcher { declaredClass(owner.name); paramTypes(); returnType("void") } }
            .filter { it.invokes.any { call -> call.name == "clear" && call.className in COLLECTIONS } }
            .map { it.getMethodInstance(loader) }
    }

    fun resultSong(loader: ClassLoader, result: Class<*>, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.result-song.v1", { result.getMethod("f") },
        { it.parameterCount == 0 && it.returnType == song },
    ) {
        methods { matcher { declaredClass(result.name); paramTypes(); returnType(song.name) } }.map { it.getMethodInstance(loader) }
    }

    fun resultLyrics(loader: ClassLoader, result: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.result-primary.v1", { result.getMethod("c") }, {
            it.parameterCount == 0 && it.returnType.declaredFields.any { field ->
                field.type == java.util.concurrent.CopyOnWriteArrayList::class.java
            }
        },
    ) {
        // Primary, translated, romanized and assist lyrics share the same type. Until a unique
        // semantic consumer is available, renamed getters stay unavailable instead of guessing.
        emptyList()
    }

    fun lyricSentences(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sentences.v1", { lyrics.getField("e") },
        { it.type == java.util.concurrent.CopyOnWriteArrayList::class.java },
    ) {
        fields { matcher { declaredClass(lyrics.name); type("java.util.concurrent.CopyOnWriteArrayList") } }
            .map { it.getFieldInstance(loader) }.filter { !Modifier.isStatic(it.modifiers) }
    }

    fun lyricType(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sync-type.v1", { lyrics.getField("a") }, { it.type == Int::class.javaPrimitiveType },
    ) {
        methods { matcher { declaredClass(lyrics.name); paramTypes(); returnType("java.lang.String")
            usingStrings("DefaultType", "FakeLyric", "TextLyric", "QRC", "Lrc_No_H", "Lrc") } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.className == lyrics.name && it.typeName == "int" }
            .map { it.getFieldInstance(loader) }.distinct()
    }

    fun lyricOffset(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.offset.v1", { lyrics.getField("f") }, { it.type == Int::class.javaPrimitiveType },
    ) { emptyList() }

    fun sentenceStart(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sentence-start.v1", { loader.loadClass("com.lyricengine.base.t").getField("b") },
        { it.type == Long::class.javaPrimitiveType },
    ) {
        methods { matcher { declaredClass(lyrics.name); paramTypes(); returnType("java.lang.String"); usingStrings("mm:ss.SS") } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.className != lyrics.name && it.typeName == "long" }
            .map { it.getFieldInstance(loader) }.distinct()
    }

    fun sentenceText(loader: ClassLoader, row: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sentence-text.v1", { row.getField("a") }, { it.type == String::class.java },
    ) {
        fields { matcher { declaredClass(row.name); type("java.lang.String") } }
            .map { it.getFieldInstance(loader) }.filter { !Modifier.isStatic(it.modifiers) }
    }

    fun navigationJump(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "qq.navigation.request.v1", {
            loader.loadClass("com.tencent.qqmusic.business.playercommon.a").getDeclaredMethod(
                "i", Activity::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                loader.loadClass("com.tencent.qqmusic.business.playernew.interactor.playerstyle.PlayerStyle"),
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Bundle::class.java,
            )
        }, { Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 10 },
    ) {
        methods { matcher { usingStrings("[jumpToPlayerActivity] step2", "should not show player "); paramCount(10); returnType("boolean") } }
            .map { it.getMethodInstance(loader) }
    }

    fun navigationCompletion(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.navigation.complete.v1", { owner.getDeclaredMethod("j") },
        { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 0 },
    ) {
        methods { matcher { declaredClass(owner.name); paramTypes(); returnType("void") } }.filter { candidate ->
            candidate.invokes.any { it.className == "java.util.concurrent.atomic.AtomicBoolean" && it.name == "set" } &&
                candidate.invokes.any { it.name == "onCompleted" && it.returnTypeName == "boolean" }
        }.map { it.getMethodInstance(loader) }
    }

    fun navigationPending(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.navigation.pending.v1", { owner.getDeclaredMethod("k") },
        { Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0 },
    ) {
        val completion = methods { matcher { declaredClass(owner.name); paramTypes(); returnType("void") } }
            .filter { it.invokes.any { call -> call.name == "onCompleted" } }
        val locks = completion.flatMap { it.usingFields }.map { it.field.descriptor }.toSet()
        methods { matcher { declaredClass(owner.name); paramTypes(); returnType("boolean") } }.filter { candidate ->
            candidate.invokes.any { it.className == "java.util.concurrent.atomic.AtomicBoolean" && it.name == "get" } &&
                candidate.usingFields.any { it.field.descriptor in locks && it.field.typeName == "java.util.concurrent.atomic.AtomicBoolean" }
        }.map { it.getMethodInstance(loader) }
    }

    private fun calledStrings(method: MethodData, depth: Int): Set<String> = buildSet {
        addAll(method.usingStrings)
        if (depth > 0) method.invokes.filter { it.className.startsWith("com.tencent.qqmusiccommon.appconfig.albumpic.") }
            .forEach { addAll(calledStrings(it, depth - 1)) }
    }

    private const val SONG = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"
    private val COLLECTIONS = setOf("java.util.ArrayList", "java.util.List", "java.util.Collection")
}
