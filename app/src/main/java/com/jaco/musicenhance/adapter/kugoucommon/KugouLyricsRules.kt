package com.jaco.musicenhance.adapter.kugoucommon

import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.inClass
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.shape
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures.reaches
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Both editions share the lyric parser's verified semantics, with separate resolver/cache keys. */
internal class KugouLyricsRules(private val loader: ClassLoader, private val app: String) {
    private val lite get() = app == "kugoulite"
    val managerType = loader.loadClass(KugouInterfaceFeatures.MANAGER)
    val dataType = loader.loadClass(KugouInterfaceFeatures.DATA)

    val activeManager: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.active-manager.v1", { managerType.getMethod(if (lite) "d" else "e") },
        { it.shape(emptyList(), managerType.name, true) },
    ) {
        inClass(managerType.name) { paramTypes(); returnType(managerType.name) }.filter { method ->
            method.shape(emptyList(), managerType.name, true) && method.usingFields.any { field ->
                field.usingType == FieldUsingType.Write && field.field.typeName == managerType.name &&
                    Modifier.isStatic(field.field.modifiers) }
        }.map { it.getMethodInstance(loader) }
    }
    val activeData: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.active-data.v1", { managerType.getMethod(if (lite) "e" else "f") },
        { it.shape(emptyList(), dataType.name, false) },
    ) {
        inClass(managerType.name) { paramTypes(); returnType(dataType.name) }.filter { method ->
            method.shape(emptyList(), dataType.name, false) && method.usingFields.any {
                it.field.typeName == dataType.name && it.usingType == FieldUsingType.Read }
        }.map { it.getMethodInstance(loader) }
    }
    val independentParser: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.independent-parser.v1", { managerType.getMethod(if (lite) "m" else "n") },
        { it.shape(emptyList(), managerType.name, true) },
    ) {
        inClass(managerType.name) { paramTypes(); returnType(managerType.name) }.filter { method ->
            method.shape(emptyList(), managerType.name, true) &&
                method.invokes.any { it.className == managerType.name && it.isConstructor } &&
                method.usingFields.none { it.field.typeName == managerType.name }
        }.map { it.getMethodInstance(loader) }
    }
    val parseFile: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.parse-file.v1", {
            managerType.getMethod(if (lite) "k" else "l", String::class.java, Boolean::class.javaPrimitiveType)
        }, { it.parameterTypes.contentEquals(arrayOf(String::class.java, Boolean::class.javaPrimitiveType)) &&
            !it.returnType.isPrimitive && it.returnType.declaredFields.any { field -> field.type == dataType } },
    ) {
        methods { matcher { declaredClass(managerType.name); paramTypes("java.lang.String", "boolean");
            addUsingString("filePath: ", StringMatchType.Contains)
            addUsingString("lyric path is empty", StringMatchType.Equals)
            addUsingString("lyric file load error", StringMatchType.Equals) } }
            .map { it.getMethodInstance(loader) }
    }

    fun resultData(result: Class<*>): Field = HostInterfaces.field(
        loader, "$app.lyrics.parse-result.v1", { result.getField("e") },
        { it.type == dataType && !Modifier.isStatic(it.modifiers) },
    ) {
        fields { matcher { declaredClass(result.name); type(dataType.name) } }
            .filter { !Modifier.isStatic(it.modifiers) && it.writers.any { writer ->
                writer.className == managerType.name && writer.usingStrings.any { text -> "filePath: " in text } } }
            .map { it.getFieldInstance(loader) }
    }

    val headers: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.headers.v1", { dataType.getMethod(if (lite) "l" else "getHeaders") },
        { it.parameterCount == 0 && Map::class.java.isAssignableFrom(it.returnType) && !Modifier.isStatic(it.modifiers) },
    ) {
        inClass(dataType.name) { paramTypes(); returnType("java.util.HashMap") }.filter { method ->
            method.shape(emptyList(), "java.util.HashMap", false) && method.usingFields.any {
                it.field.typeName == "java.util.HashMap" && it.usingType == FieldUsingType.Read }
        }.map { it.getMethodInstance(loader) }
    }
    val rowStarts: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.row-starts.v1", { dataType.getMethod(if (lite) "r" else "getRowBeginTime") },
        { it.shape(emptyList(), "[J", false) },
    ) {
        // The ordinary edition retains descriptive API names. Concept's two long[] fields cannot
        // be distinguished safely using signatures: never interpret row durations as start times.
        inClass(dataType.name) { name("getRowBeginTime"); paramTypes(); returnType("long[]") }
            .filter { it.shape(emptyList(), "long[]", false) }
            .map { it.getMethodInstance(loader) }
    }
    val words: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.words.v1", { dataType.getMethod(if (lite) "E" else "getWords") },
        { it.shape(emptyList(), "[[Ljava.lang.String;", false) },
    ) {
        // The current-line StringBuilder consumes the original lyric matrix; translations and
        // romanizations are separate matrices and must not be selected by return type alone.
        val matrices = inClass(dataType.name) { paramTypes(); returnType("java.lang.String") }.filter { method ->
            method.shape(emptyList(), "java.lang.String", false) && method.invokes.any {
                it.className == "java.lang.StringBuilder" && it.name == "append" }
        }.flatMap { it.usingFields }.filter { it.field.typeName == "java.lang.String[][]" }
            .map { it.field.descriptor }.toSet()
        inClass(dataType.name) { paramTypes(); returnType("java.lang.String[][]") }.filter { method -> method.shape(emptyList(), "java.lang.String[][]", false) &&
            method.usingFields.any { it.field.descriptor in matrices && it.usingType == FieldUsingType.Read } }
            .map { it.getMethodInstance(loader) }
    }

    val downloader: Class<*> get() = HostInterfaces.type(
        loader, "$app.lyrics.downloader.v1", {
            loader.loadClass(if (lite) "com.kugou.framework.lyric.i" else "com.kugou.framework.lyric.g")
        }, { type -> type.constructors.any { it.parameterCount == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType } },
    ) {
        methods { searchPackages("com.kugou.framework.lyric"); matcher {
            paramTypes(); returnType("void")
            addUsingString("pushLyric-start:", StringMatchType.Contains)
            addUsingString("pushLyric-localExist:", StringMatchType.Contains)
        } }.map { it.getClassInstance(loader) }.distinct()
    }
    val download: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.download.v1", { downloader.getMethod(if (lite) "v" else "G") },
        { it.shape(emptyList(), "void", false) },
    ) {
        methods { matcher { declaredClass(downloader.name); paramTypes(); returnType("void");
            addUsingString("pushLyric-start:", StringMatchType.Contains)
            addUsingString("pushLyric-localExist:", StringMatchType.Contains) } }
            .map { it.getMethodInstance(loader) }
    }
    val downloadedPath: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.downloaded-path.v1", { downloader.getMethod(if (lite) "d" else "f") },
        { it.shape(emptyList(), "java.lang.String", false) },
    ) {
        val entry = inClass(downloader.name) { paramTypes(); returnType("void"); name(download.name) }
            .filter { it.isMethod && it.getMethodInstance(loader) == download }
        val written = entry.flatMap { it.usingFields }.filter {
            it.usingType == FieldUsingType.Write && it.field.typeName == "java.lang.String"
        }.map { it.field.descriptor }.toSet()
        inClass(downloader.name) { paramTypes(); returnType("java.lang.String") }.filter { method ->
            method.shape(emptyList(), "java.lang.String", false) && method.usingFields.any {
                it.field.descriptor in written && it.usingType == FieldUsingType.Read } &&
                method.invokes.none { it.className.startsWith("com.kugou.") }
        }.map { it.getMethodInstance(loader) }
    }

    val downloadConstructor: Constructor<*> get() = HostInterfaces.constructor(
        loader, "$app.lyrics.download-constructor.v1", {
            downloader.constructors.single { it.parameterCount == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType }
        }, { it.declaringClass == downloader && it.parameterCount == 2 &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType && !it.parameterTypes[0].isPrimitive },
    ) {
        inClass(downloader.name) { name("<init>"); paramCount(2) }.filter { it.isConstructor && it.paramTypeNames[1] == "boolean" }
            .map { it.getConstructorInstance(loader) }
    }

    val searchType: Class<*> get() = HostInterfaces.type(
        loader, "$app.lyrics.search-protocol.v1", {
            loader.loadClass(if (lite) "com.kugou.framework.lyric.protocol.d" else "com.kugou.framework.lyric.protocol.b")
        }, { it.constructors.any { constructor -> constructor.parameterCount in 4..5 &&
            constructor.parameterTypes.take(4) == listOf(String::class.java, Long::class.javaPrimitiveType,
                String::class.java, Long::class.javaPrimitiveType) } },
    ) {
        methods { searchPackages("com.kugou.framework.lyric"); matcher { usingEqStrings("https://lyrics.kugou.com/v1/search") } }
            .mapNotNull { it.getClassInstance(loader).enclosingClass }.distinct()
    }
    val search: Method get() = HostInterfaces.method(
        loader, "$app.lyrics.search.v1", { searchType.getMethod(if (lite) "s" else "y", Boolean::class.javaPrimitiveType) },
        { it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) && List::class.java.isAssignableFrom(it.returnType) },
    ) {
        inClass(searchType.name) { paramTypes("boolean") }.filter { method ->
            method.reaches(3) { call -> call.usingStrings.any { "downloadMoreLyric reuslt size@" in it } } }
            .map { it.getMethodInstance(loader) }
    }

    fun requestMethod(owner: Class<*>, semantic: String, knownName: String, result: Class<*>, vararg parameters: Class<*>): Method =
        KugouInterfaceFeatures.verified(loader, app, "lyrics.request.$semantic", owner, knownName, result, *parameters)

    fun candidateMethod(owner: Class<*>, semantic: String, knownName: String, result: Class<*>): Method = HostInterfaces.method(
        loader, "$app.lyrics.candidate.$semantic.v1", { owner.getMethod(knownName) },
        { it.parameterCount == 0 && if (result == Any::class.java)
            it.returnType in setOf(String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            else it.returnType == result }, discover = { emptyList() },
    )

    fun wrapperMethod(semantic: String, knownName: String, result: Class<*>): Method = HostInterfaces.method(
        loader, "$app.lyrics.song.$semantic.v1", { loader.loadClass(KugouInterfaceFeatures.WRAPPER).getMethod(knownName) },
        { it.shape(emptyList(), result.name, false) },
    ) {
        if (semantic == "duration") inClass(KugouInterfaceFeatures.WRAPPER) { paramTypes(); returnType("long") }.filter { method ->
            method.shape(emptyList(), "long", false) && method.invokes.any { call ->
                call.name == "getDuration" && call.className == "com.kugou.common.filemanager.entity.KGFile" }
        }.map { it.getMethodInstance(loader) } else emptyList()
    }
}
