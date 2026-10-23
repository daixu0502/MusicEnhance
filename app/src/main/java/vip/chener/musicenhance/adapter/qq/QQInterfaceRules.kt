package vip.chener.musicenhance.adapter.qq

import vip.chener.musicenhance.adapter.discovery.DexSearch
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import vip.chener.musicenhance.hook.moduleInfo
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Field

/** Semantic DEX contracts shared by QQ versions. Discovery never executes a candidate. */
internal object QQInterfaceRules {
    fun prepare(loader: ClassLoader) {
        val song = loader.loadClass(SONG)
        // Each capability remains independent when another protocol changes or is unavailable.
        val capabilities = listOf<() -> Unit>(
            { songTitle(loader, song); songId(loader, song) },
            { environment(loader) },
            { users(loader, song) },
            { playerService(loader, loader.loadClass("com.tencent.qqmusicplayerprocess.servicenew.IQQPlayerServiceNew")) },
            { val owner = controlsType(loader); listOf("seek", "resume", "start").forEach { playback(loader, owner, it) } },
            { artwork(loader, song, "album") },
            { artwork(loader, song, "sized") },
            { artwork(loader, song, "singer") },
            {
                val owner = lyricLoader(loader)
                loadLyrics(loader, owner, song)
                clearLyrics(loader, owner)
                val listener = addLyricListener(loader, owner).parameterTypes.single()
                val result = listener.methods.single { it.name == "onLoadSuc" && it.parameterCount == 1 }.parameterTypes.single()
                resultSong(loader, result, song)
                val lyrics = resultLyrics(loader, result).returnType
                lyricSentences(loader, lyrics); lyricType(loader, lyrics); lyricOffset(loader, lyrics)
                sentenceText(loader, sentenceStart(loader, lyrics).declaringClass)
            },
            {
                val owner = navigationJump(loader).declaringClass
                navigationCompletion(loader, owner); navigationPending(loader, owner)
            },
        )
        capabilities.forEach { runCatching(it).onFailure { error ->
            // The first pass only registers pending rules; report actual missing/ambiguous
            // capabilities from the scan so a future host change remains diagnosable.
            if (error !is HostInterfaces.Unavailable || error.message?.contains("pending") != true) {
                moduleInfo("QQ capability preparation: ${error.message}")
            }
        } }
    }

    fun songTitle(loader: ClassLoader, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.song.title.v1",
        validate = { it.declaringClass == song && it.parameterCount == 0 && it.returnType == String::class.java },
    ) {
        methods { matcher { declaredClass(song.name); usingStrings("[setFingerMatchFail]") } }
            .flatMap { it.invokes }.filter { it.className == song.name && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .map { it.getMethodInstance(loader) }.distinct()
    }

    fun songId(loader: ClassLoader, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.song.playback-identity.v1",
        validate = { it.declaringClass == song && Modifier.isPublic(it.modifiers) &&
            it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType },
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
        loader, "qq.favorite.store.v1",
        validate = { type -> runCatching {
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
        loader, "qq.repeat.service.v1", validate = { Modifier.isStatic(it.modifiers) && it.type == service },
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
        loader, "qq.playback.facade.v1",
        validate = { type -> type.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == type } &&
            type.methods.any { it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)) &&
                it.returnType == Long::class.javaPrimitiveType } },
    ) {
        lyricClickMethods().flatMap { it.invokes }
            .filter { it.paramTypeNames == listOf("long", "int") && it.returnTypeName == "long" }
            .filter { candidate ->
                val owner = candidate.getClassInstance(loader)
                owner.declaredFields.any { Modifier.isStatic(it.modifiers) && it.type == owner }
            }.map { it.getClassInstance(loader) }.distinct()
    }

    fun playback(loader: ClassLoader, owner: Class<*>, operation: String): Method {
        require(operation in setOf("seek", "resume", "start"))
        val parameters = if (operation == "seek") arrayOf("long", "int") else arrayOf("int")
        val result = when (operation) { "seek" -> "long"; "resume" -> "int"; else -> "void" }
        return HostInterfaces.method(loader, "qq.playback.$operation.v1", validate = { method ->
            method.declaringClass == owner && !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.map { it.name } == parameters.toList() && method.returnType.name == result
        }) {
            // The host's lyric click explicitly starts, seeks and resumes. Guest player logs
            // say "play return" for several different actions and do not identify "start".
            lyricClickMethods().flatMap { it.invokes }.filter {
                it.className == owner.name && it.paramTypeNames == parameters.toList() && it.returnTypeName == result
            }.map { it.getMethodInstance(loader) }.distinct()
        }
    }

    fun environment(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "qq.play-environment.v1", validate = { factory ->
            Modifier.isStatic(factory.modifiers) && factory.parameterCount == 0 &&
                factory.returnType.methods.any { it.name == "getPlaySong" && it.parameterCount == 0 &&
                    it.returnType == loader.loadClass(SONG) } && factory.returnType.methods.any { it.name == "getPlayMode" }
        },
    ) {
        methods { matcher { paramTypes(); usingStrings(listOf("MusicProcess.playEnv() can't be called."), StringMatchType.Contains) } }
            .map { it.getMethodInstance(loader) }
    }

    fun artwork(loader: ClassLoader, song: Class<*>, kind: String): Method = HostInterfaces.method(
        loader, "qq.artwork.$kind.v1",
        validate = { Modifier.isStatic(it.modifiers) && it.returnType == String::class.java &&
            it.parameterTypes.map(Class<*>::getName) == if (kind == "sized") listOf(song.name, "int", "int") else listOf(song.name, "int") },
    ) {
        val singerGetters = if (kind == "singer") emptySet() else {
            val singer = artwork(loader, song, "singer")
            methods { matcher { declaredClass(singer.declaringClass.name); name(singer.name); paramTypes(song.name, "int") } }
                .flatMap { it.invokes }.filter { it.className == song.name && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
                .map { it.descriptor }.toSet()
        }
        methods { searchPackages("com.tencent.qqmusiccommon.appconfig.albumpic"); matcher {
            returnType(String::class.java.name)
            paramTypes(*if (kind == "sized") arrayOf(song.name, "int", "int") else arrayOf(song.name, "int"))
        } }.filter { candidate ->
            val strings = calledStrings(candidate, 4)
            if (kind == "singer") strings.any { "photo_new/T001" in it } && strings.none { "photo_new/T002" in it }
            else strings.any { "photo_new/T002" in it } && strings.any { "photo_new/T001" in it } &&
                candidate.invokes.any { it.descriptor in singerGetters }
        }.map { it.getMethodInstance(loader) }
    }

    fun lyricLoader(loader: ClassLoader): Class<*> = HostInterfaces.type(
        loader, "qq.lyrics.loader.v1",
        validate = { it.constructors.any { constructor -> constructor.parameterCount == 0 } },
    ) {
        methods { matcher { usingStrings(" [loadBySongInfo] song == null.", "LyricLoadManager") } }
            .map { it.getClassInstance(loader) }.distinct()
    }

    fun loadLyrics(loader: ClassLoader, owner: Class<*>, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.load-song.v1",
        validate = { it.declaringClass == owner && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(song)) },
    ) {
        val entry = methods { matcher {
            declaredClass(owner.name); usingStrings(" [loadBySongInfo] song == null.")
        } }.map { it.descriptor }.toSet()
        methods { matcher { declaredClass(owner.name); paramTypes(song.name); returnType("void") } }
            .filter { it.invokes.any { call -> call.descriptor in entry } }.map { it.getMethodInstance(loader) }
    }

    fun addLyricListener(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.add-listener.v1", validate = {
            it.declaringClass == owner && it.parameterCount == 1 && it.returnType == Void.TYPE && it.parameterTypes[0].isInterface
        },
    ) {
        methods { matcher { declaredClass(owner.name); paramCount(1); returnType("void") } }.filter { candidate ->
            val parameter = candidate.paramTypes.singleOrNull() ?: return@filter false
            parameter.methods.any { it.name == "onLoadSuc" && it.paramCount == 1 } &&
                parameter.methods.any { it.name == "onLyricSeek" && it.paramTypeNames == listOf("long", "float") } &&
                candidate.invokes.any { it.name == "add" && it.className in COLLECTIONS }
        }.map { it.getMethodInstance(loader) }
    }

    fun clearLyrics(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.release-loader.v1",
        validate = { it.declaringClass == owner && it.parameterCount == 0 && it.returnType == Void.TYPE },
    ) {
        methods { matcher { declaredClass(owner.name); paramTypes(); returnType("void") } }
            .filter { it.invokes.any { call -> call.name == "clear" && call.className in COLLECTIONS } }
            .map { it.getMethodInstance(loader) }
    }

    fun resultSong(loader: ClassLoader, result: Class<*>, song: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.result-song.v1",
        validate = { it.declaringClass == result && it.parameterCount == 0 && it.returnType == song },
    ) {
        methods { matcher { declaredClass(result.name); paramTypes(); returnType(song.name) } }.map { it.getMethodInstance(loader) }
    }

    fun resultLyrics(loader: ClassLoader, result: Class<*>): Method = HostInterfaces.method(
        loader, "qq.lyrics.result-primary.v1", validate = {
            it.declaringClass == result && it.parameterCount == 0 && it.returnType.declaredFields.any { field ->
                field.type == java.util.concurrent.CopyOnWriteArrayList::class.java
            }
        },
    ) {
        // This native consumer displays the original timed lyric. Other getters have the same
        // return type, so a signature or the order of result fields cannot identify it.
        methods { matcher {
            name("onLoadSuc"); paramTypes(result.name); returnType("void")
            usingStrings("PlayerDynamicLyricViewDelegate", "[onLoadSuc] no lyric")
        } }.flatMap { it.invokes }.filter { it.className == result.name && it.paramCount == 0 }
            .map { it.getMethodInstance(loader) }.distinct()
    }

    fun lyricSentences(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sentences.v1",
        validate = { it.declaringClass == lyrics && it.type == java.util.concurrent.CopyOnWriteArrayList::class.java },
    ) {
        fields { matcher { declaredClass(lyrics.name); type("java.util.concurrent.CopyOnWriteArrayList") } }
            .map { it.getFieldInstance(loader) }.filter { !Modifier.isStatic(it.modifiers) }
    }

    fun lyricType(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sync-type.v1", validate = { it.declaringClass == lyrics && it.type == Int::class.javaPrimitiveType },
    ) {
        methods { matcher { declaredClass(lyrics.name); paramTypes(); returnType("java.lang.String")
            usingStrings("DefaultType", "FakeLyric", "TextLyric", "QRC", "Lrc_No_H", "Lrc") } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.className == lyrics.name && it.typeName == "int" }
            .map { it.getFieldInstance(loader) }.distinct()
    }

    fun lyricOffset(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.offset.v1", validate = { it.declaringClass == lyrics && it.type == Int::class.javaPrimitiveType },
    ) {
        val syncType = lyricType(loader, lyrics)
        // Native seek subtracts the file offset, separately from the user's manual offset.
        methods { matcher {
            paramTypes("long"); returnType("void")
            usingStrings("start [seek] position:", "manualOffset:", "mLyricOffset:")
        } }.flatMap { it.usingFields }.map { it.field }
            .filter { it.className == lyrics.name && it.typeName == "int" }
            .map { it.getFieldInstance(loader) }.distinct().filter { it != syncType }
    }

    fun sentenceStart(loader: ClassLoader, lyrics: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sentence-start.v1", validate = { it.type == Long::class.javaPrimitiveType },
    ) {
        methods { matcher { declaredClass(lyrics.name); paramTypes(); returnType("java.lang.String"); usingStrings("mm:ss.SS") } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.className != lyrics.name && it.typeName == "long" }
            .map { it.getFieldInstance(loader) }.distinct()
    }

    fun sentenceText(loader: ClassLoader, row: Class<*>): Field = HostInterfaces.field(
        loader, "qq.lyrics.sentence-text.v1", validate = { it.declaringClass == row && it.type == String::class.java },
    ) {
        fields { matcher { declaredClass(row.name); type("java.lang.String") } }
            .map { it.getFieldInstance(loader) }.filter { !Modifier.isStatic(it.modifiers) }
    }

    fun navigationJump(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "qq.navigation.request.v1", validate = {
            Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 10
        },
    ) {
        methods { matcher { usingStrings("[jumpToPlayerActivity] step2", "should not show player "); paramCount(10); returnType("boolean") } }
            .map { it.getMethodInstance(loader) }
    }

    fun navigationCompletion(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.navigation.complete.v1",
        validate = { it.declaringClass == owner && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 0 },
    ) {
        methods { matcher { declaredClass(owner.name); paramTypes(); returnType("void") } }.filter { candidate ->
            candidate.invokes.any { it.className == "java.util.concurrent.atomic.AtomicBoolean" && it.name == "set" } &&
                candidate.invokes.any { it.name == "onCompleted" && it.returnTypeName == "boolean" }
        }.map { it.getMethodInstance(loader) }
    }

    fun navigationPending(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "qq.navigation.pending.v1",
        validate = { it.declaringClass == owner && Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0 },
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
        if (depth > 0) method.invokes.filter {
            it.className.startsWith("com.tencent.qqmusiccommon.appconfig.albumpic.") && it.returnTypeName == "java.lang.String"
        }
            .forEach { addAll(calledStrings(it, depth - 1)) }
    }

    private fun DexSearch.lyricClickMethods(): List<MethodData> = methods {
        searchPackages("com.tencent.qqmusic.business.playernew")
        matcher { returnType("void"); usingStrings("PlayerLyricViewModel", "lyric onClick") }
    }

    private const val SONG = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"
    private val COLLECTIONS = setOf("java.util.ArrayList", "java.util.List", "java.util.Collection")
}
