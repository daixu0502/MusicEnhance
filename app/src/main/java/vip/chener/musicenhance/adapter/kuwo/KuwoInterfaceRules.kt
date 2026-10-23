package vip.chener.musicenhance.adapter.kuwo

import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType

/** Kuwo module contracts and URL protocol verified in 12.2.2.4. */
internal object KuwoInterfaceRules {
    fun playbackType(loader: ClassLoader): Class<*> = HostInterfaces.type(
        loader, "kuwo.playback.interface.v1", { loader.loadClass("cn.kuwo.mod.playcontrol.c") },
        { isPlayback(it, loader.loadClass(MUSIC)) },
    ) {
        methods { matcher { name("continuePlay"); paramTypes(); returnType("boolean") } }
            .map { it.getClassInstance(loader) }.filter { isPlayback(it, loader.loadClass(MUSIC)) }.distinct()
    }

    fun playerFactory(loader: ClassLoader): Method {
        val type = playbackType(loader)
        return HostInterfaces.method(loader, "kuwo.playback.factory.v1", {
            loader.loadClass("q1.b").getMethod("e0")
        }, { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == type }) {
            methods { matcher { returnType(type.name); paramTypes() } }.filter { candidate ->
                Modifier.isStatic(candidate.modifiers) && candidate.usingFields.any { field ->
                    field.field.typeName == type.name && Modifier.isStatic(field.field.modifiers)
                } && candidate.invokes.any { it.isConstructor && it.declaredClass?.interfaces?.any { contract -> contract.name == type.name } == true }
            }.map { it.getMethodInstance(loader) }
        }
    }

    fun currentSong(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "kuwo.playback.current-song.v1", { owner.getMethod("X") },
        { it.parameterCount == 0 && it.returnType == loader.loadClass(MUSIC) },
    ) {
        methods { matcher { declaredClass(owner.name); paramTypes(); returnType(MUSIC) } }.map { it.getMethodInstance(loader) }
    }

    fun queueList(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kuwo.queue.current-list.v1", { loader.loadClass("cn.kuwo.mod.playcontrol.n").getMethod("U") },
        { it.parameterCount == 0 && it.returnType.name == MUSIC_LIST && !Modifier.isStatic(it.modifiers) },
    ) {
        val activeFields = methods { searchPackages("cn.kuwo.mod.playcontrol"); matcher {
            usingStrings("循环模式，获取歌曲上一曲,列表空，返回null")
        } }.flatMap { it.usingFields }.map { it.field }.filter { it.typeName == MUSIC_LIST }
            .map { it.descriptor }.toSet()
        methods { searchPackages("cn.kuwo.mod.playcontrol"); matcher { paramTypes(); returnType(MUSIC_LIST) } }
            .filter { it.invokes.isEmpty() && it.usingFields.size == 1 && it.usingFields.single().field.descriptor in activeFields }
            .map { it.getMethodInstance(loader) }
    }

    fun activeQueue(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "kuwo.playback.active-queue.v1", { owner.getMethod("a7") },
        { it.parameterCount == 0 && it.returnType.name == MUSIC_LIST },
    ) {
        val getter = queueList(loader)
        methods { searchPackages("cn.kuwo.mod.playcontrol"); matcher { paramTypes(); returnType(MUSIC_LIST) } }
            .filter { it.declaredClass?.interfaces?.any { contract -> contract.name == owner.name } == true &&
                it.invokes.any { call -> call.className == getter.declaringClass.name && call.name == getter.name && call.paramCount == 0 } }
            .mapNotNull { candidate -> owner.methods.singleOrNull { it.name == candidate.name && it.parameterCount == 0 && it.returnType.name == MUSIC_LIST } }
            .distinct()
    }

    fun queueFactory(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "kuwo.queue.factory.v1", { owner.getMethod("O") },
        { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == owner },
    ) {
        methods { matcher { declaredClass(owner.name); paramTypes(); returnType(owner.name); modifiers(Modifier.STATIC) } }
            .filter { it.usingFields.any { field -> field.field.typeName == owner.name && Modifier.isStatic(field.field.modifiers) } }
            .map { it.getMethodInstance(loader) }
    }

    fun shuffleOrder(loader: ClassLoader, owner: Class<*>): Field = HostInterfaces.field(
        loader, "kuwo.queue.shuffle-order.v1", { owner.getDeclaredField("m") }, { it.type == IntArray::class.java },
    ) {
        fields { matcher { declaredClass(owner.name); type("int[]") } }.map { it.getFieldInstance(loader) }
            .filter { !Modifier.isStatic(it.modifiers) }
    }.apply { isAccessible = true }

    fun shufflePosition(loader: ClassLoader, owner: Class<*>): Field = HostInterfaces.field(
        loader, "kuwo.queue.shuffle-position.v1", { owner.getDeclaredField("o") }, { it.type == Int::class.javaPrimitiveType },
    ) {
        // The queue has several integer cursors. A matching int type is not enough to establish
        // the actual permutation position; unknown orders are not advertised as prefetched.
        emptyList()
    }.apply { isAccessible = true }

    fun lyricsFactory(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kuwo.lyrics.factory.v1", { loader.loadClass("q1.b").getMethod("O") },
        { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && isLyrics(it.returnType) },
    ) {
        methods { matcher {
            paramTypes(); modifiers(Modifier.STATIC)
            returnType {
                methods {
                    add { name("getOffset"); paramTypes(); returnType("long") }
                    add { paramTypes(); returnType("android.graphics.Bitmap") }
                }
            }
        } }.filter { candidate ->
            Modifier.isStatic(candidate.modifiers) && candidate.returnType?.methods?.any {
                it.name == "getOffset" && it.paramCount == 0 && it.returnTypeName == "long"
            } == true && candidate.returnType?.methods?.any { it.returnTypeName == "android.graphics.Bitmap" } == true &&
                candidate.usingFields.any { it.field.typeName == candidate.returnTypeName }
        }.map { it.getMethodInstance(loader) }
    }

    fun artworkLookup(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kuwo.artwork.lookup.v1", {
            loader.loadClass("cn.kuwo.base.utils.s2").getMethod("p9", Long::class.javaPrimitiveType,
                String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)
        }, { Modifier.isStatic(it.modifiers) && it.returnType == String::class.java && it.parameterCount == 5 },
    ) {
        val builders = methods { matcher {
            returnType(String::class.java.name)
            usingStrings("type=rid_pic&pictype=url&content=list", "&size=", "&rid=")
        } }.map { it.descriptor }.toSet()
        methods { matcher { returnType(String::class.java.name); paramTypes("long", "java.lang.String", "java.lang.String", "java.lang.String", "int") } }
            .filter { Modifier.isStatic(it.modifiers) && it.invokes.any { call -> call.descriptor in builders } }
            .map { it.getMethodInstance(loader) }
    }

    fun lyricsDispatch(loader: ClassLoader): Method = HostInterfaces.method(
        loader, "kuwo.lyrics.dispatch.v1", {
            loader.loadClass("cn.kuwo.mod.lyrics.d0").declaredMethods.single { it.name == "i" && it.parameterCount == 5 }
        }, { candidate ->
            Modifier.isStatic(candidate.modifiers) && candidate.returnType == Void.TYPE && candidate.parameterCount == 5 &&
                candidate.parameterTypes[0].name == MUSIC && candidate.parameterTypes[1].isEnum &&
                candidate.parameterTypes[2] == candidate.parameterTypes[3] && candidate.parameterTypes[4] == Boolean::class.javaPrimitiveType
        },
    ) {
        methods { matcher { returnType("void"); paramTypes(MUSIC, STATUS, null, null, "boolean") } }.filter { candidate ->
            Modifier.isStatic(candidate.modifiers) && candidate.paramTypeNames[2] == candidate.paramTypeNames[3] &&
                candidate.invokes.any { it.isConstructor && it.paramTypeNames == listOf(STATUS,
                    candidate.paramTypeNames[2], candidate.paramTypeNames[3], "boolean", MUSIC) }
        }.map { it.getMethodInstance(loader) }
    }

    fun originalLyrics(loader: ClassLoader, owner: Class<*>, data: Class<*>): Method = HostInterfaces.method(
        loader, "kuwo.lyrics.original.v1", { owner.getMethod("Wc") },
        { it.parameterCount == 0 && it.returnType == data },
    ) {
        val primary = methods { searchPackages("cn.kuwo.mod.lyrics"); matcher { name("getOffset"); paramTypes(); returnType("long") } }
            .filter { it.declaredClass?.interfaces?.any { contract -> contract.name == owner.name } == true }
            .flatMap { it.usingFields }.map { it.field }.filter { it.typeName == data.name }
            .map { it.descriptor }.toSet()
        methods { searchPackages("cn.kuwo.mod.lyrics"); matcher { paramTypes(); returnType(data.name) } }.filter { candidate ->
            candidate.declaredClass?.interfaces?.any { it.name == owner.name } == true &&
                candidate.usingFields.any { it.field.descriptor in primary }
        }.mapNotNull { candidate -> owner.methods.singleOrNull {
            it.name == candidate.name && it.parameterCount == 0 && it.returnType == data
        } }.distinct()
    }

    fun originalArtwork(loader: ClassLoader, owner: Class<*>): Method = HostInterfaces.method(
        loader, "kuwo.artwork.original.v1", { owner.getMethod("h") },
        { it.parameterCount == 0 && it.returnType == android.graphics.Bitmap::class.java },
    ) {
        val bitmapWriters = methods { searchPackages("cn.kuwo.mod.lyrics"); matcher { paramTypes(STATUS, "android.graphics.Bitmap"); returnType("void") } }
            .filter { it.declaredClass?.interfaces?.any { contract -> contract.name == owner.name } == true }
        val primary = bitmapWriters.flatMap { it.usingFields }.map { it.field }
            .filter { it.typeName == "android.graphics.Bitmap" }.map { it.descriptor }.toSet()
        methods { searchPackages("cn.kuwo.mod.lyrics"); matcher { paramTypes(); returnType("android.graphics.Bitmap") } }.filter { candidate ->
            candidate.declaredClass?.interfaces?.any { it.name == owner.name } == true &&
                candidate.usingFields.any { it.field.descriptor in primary }
        }.mapNotNull { candidate -> owner.methods.singleOrNull {
            it.name == candidate.name && it.parameterCount == 0 && it.returnType == android.graphics.Bitmap::class.java
        } }.distinct()
    }

    fun lyricOffset(loader: ClassLoader, data: Class<*>): Method = HostInterfaces.method(
        loader, "kuwo.lyrics.offset.v1", { data.getMethod("a") },
        { it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType },
    ) {
        methods { matcher { declaredClass(data.name); paramTypes(); returnType("long") } }
            .map { it.getMethodInstance(loader) }
    }

    fun lyricRows(loader: ClassLoader, data: Class<*>): Method = HostInterfaces.method(
        loader, "kuwo.lyrics.rows.v1", { data.getMethod("q") },
        { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) },
    ) {
        methods { matcher { declaredClass(data.name); paramTypes(); returnType("java.util.List") } }
            .map { it.getMethodInstance(loader) }.filter { rowType(it)?.let(::isLyricRow) == true }
    }

    fun lyricRowType(loader: ClassLoader, rows: Method): Class<*> = HostInterfaces.type(
        loader, "kuwo.lyrics.row-type.v1", { loader.loadClass("cn.kuwo.mod.lyrics.j") }, ::isLyricRow,
    ) { listOfNotNull(rowType(rows)?.takeIf(::isLyricRow)) }

    fun lyricStart(loader: ClassLoader, row: Class<*>): Field = HostInterfaces.field(
        loader, "kuwo.lyrics.row-start.v1", { row.getField("b") }, { it.type == java.lang.Integer::class.java },
    ) {
        val comparison = methods { matcher { declaredClass(row.name); name("compareTo"); paramTypes(row.name); returnType("int") } }
            .filter { it.invokes.any { call -> call.className == "java.lang.Integer" && call.name == "compareTo" } }
        comparison.flatMap { it.usingFields }.map { it.field }.filter { it.className == row.name && it.typeName == "java.lang.Integer" }
            .map { it.getFieldInstance(loader) }.distinct()
    }

    fun lyricText(loader: ClassLoader, row: Class<*>): Field = HostInterfaces.field(
        loader, "kuwo.lyrics.row-text.v1", { row.getField("d") }, { it.type == String::class.java },
    ) {
        fields { matcher { declaredClass(row.name); type("java.lang.String") } }
            .map { it.getFieldInstance(loader) }.filter { !Modifier.isStatic(it.modifiers) }
    }

    private fun rowType(method: Method): Class<*>? {
        val element = (method.genericReturnType as? ParameterizedType)?.actualTypeArguments?.singleOrNull()
        return when (element) {
            is Class<*> -> element
            is WildcardType -> element.upperBounds.singleOrNull() as? Class<*>
            else -> null
        }
    }

    private fun isLyricRow(type: Class<*>): Boolean = Comparable::class.java.isAssignableFrom(type) &&
        type.declaredFields.count { !Modifier.isStatic(it.modifiers) && it.type == String::class.java } == 1 &&
        type.declaredFields.count { !Modifier.isStatic(it.modifiers) && it.type == java.lang.Integer::class.java } == 2

    private fun isPlayback(type: Class<*>, music: Class<*>): Boolean = type.isInterface && runCatching {
        type.getMethod("continuePlay").returnType == Boolean::class.javaPrimitiveType &&
            type.getMethod("getPlayMode").returnType == Int::class.javaPrimitiveType &&
            type.getMethod("setPlayMode", Int::class.javaPrimitiveType).returnType == Void.TYPE &&
            type.getMethod("seek", Int::class.javaPrimitiveType).returnType == Void.TYPE &&
            type.methods.any { it.parameterCount == 0 && it.returnType == music }
    }.getOrDefault(false)

    private fun isLyrics(type: Class<*>): Boolean = type.isInterface && runCatching {
        type.getMethod("getOffset").returnType == Long::class.javaPrimitiveType &&
            type.methods.any { it.parameterCount == 0 && it.returnType == android.graphics.Bitmap::class.java }
    }.getOrDefault(false)

    private const val MUSIC = "cn.kuwo.base.bean.Music"
    private const val MUSIC_LIST = "cn.kuwo.base.bean.MusicList"
    private const val STATUS = "cn.kuwo.mod.lyrics.LyricsDefine\$DownloadStatus"
}
