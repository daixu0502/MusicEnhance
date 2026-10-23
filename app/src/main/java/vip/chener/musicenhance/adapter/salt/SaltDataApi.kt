package vip.chener.musicenhance.adapter.salt

import android.graphics.Bitmap
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import java.io.Serializable
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Native data roles are traced through typed casts and constructor arguments, not field order. */
internal class SaltDataApi(private val loader: ClassLoader) {
    private val controller = loader.loadClass("com.salt.music.service.MusicController")
    val documentType by lazy { HostInterfaces.type(loader, "salt.lyrics.document.v1", validate = { type ->
        type.declaredFields.count { !Modifier.isStatic(it.modifiers) && it.type == String::class.java } == 1 &&
            type.declaredFields.count { !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type) } == 1
    }) {
        methods { matcher { name("toString"); usingStrings("LyricsDocument(sourceText=", ", lyricsLines=") } }
            .map { it.getClassInstance(loader) }.distinct()
    } }
    val lyricSource by lazy { documentType.declaredFields.single {
        !Modifier.isStatic(it.modifiers) && it.type == String::class.java
    }.apply { isAccessible = true } }
    val lyricLines by lazy { documentType.declaredFields.single {
        !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
    }.apply { isAccessible = true } }
    val lyricPublish by lazy { HostInterfaces.method(loader, "salt.lyrics.publish.v1", validate = {
        it.declaringClass == controller && Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.contentEquals(arrayOf(documentType, String::class.java)) && it.returnType == Void.TYPE
    }) {
        methods { matcher { declaredClass(controller.name); paramTypes(documentType, String::class.java); returnType("void") } }
            .map { it.getMethodInstance(loader) }
    } }
    val lyricsFlow by lazy { lyricFlow("document", documentType.name) }
    val lyricsOwnerFlow by lazy { lyricFlow("owner", String::class.java.name) }
    private fun lyricFlow(role: String, valueType: String): Field = HostInterfaces.field(loader, "salt.lyrics.$role-flow.v2",
        validate = { it.declaringClass == controller && Modifier.isStatic(it.modifiers) && hasValue(it.type) }) {
        methods { matcher { declaredClass(controller.name); name(lyricPublish.name); paramTypes(documentType, String::class.java) } }
            .flatMap { fieldsReadAs(it, valueType) }.map { it.resolve(loader) as Field }
    }
    val lineType by lazy { HostInterfaces.type(loader, "salt.lyrics.line.v1") {
        methods { matcher { name("toString"); usingStrings("LyricsLine(startTime=", ", endTime=", ", pureMainText='") } }
            .map { it.getClassInstance(loader) }.distinct()
    } }
    val lineStart by lazy { lineField("start", "LyricsLine(startTime=", "long") }
    val lineText by lazy { lineField("text", ", pureMainText='", "java.lang.String") }
    private fun lineField(role: String, label: String, typeName: String): Field = HostInterfaces.field(loader, "salt.lyrics.line-$role.v1",
        validate = { it.declaringClass == lineType && !Modifier.isStatic(it.modifiers) && it.type.name == typeName }) {
        methods { matcher { declaredClass(lineType.name); name("toString"); paramTypes(); returnType("java.lang.String") } }
            .flatMap { fieldsFollowingString(it, label, typeName) }.map { it.resolve(loader) as Field }
    }
    val artworkPublish by lazy { HostInterfaces.method(loader, "salt.artwork.publish.v1", validate = {
        it.declaringClass == controller && Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.contentEquals(arrayOf(controller, Bitmap::class.java)) && it.returnType == Void.TYPE
    }) {
        methods { matcher { declaredClass(controller.name); paramTypes(controller, Bitmap::class.java); returnType("void") } }
            .filter { method -> method.invokes.any { it.className == "com.salt.music.service.SongPicture" && it.name == "getDefaultCoverBitmap" } &&
                method.invokes.any { it.className == "android.graphics.Bitmap" && it.name == "createBitmap" } }
            .map { it.getMethodInstance(loader) }
    } }
    val coverPair by lazy { HostInterfaces.type(loader, "salt.artwork.pair.v2", validate = { type ->
        Serializable::class.java.isAssignableFrom(type) && type.declaredFields.count {
            !Modifier.isStatic(it.modifiers) && it.type == Any::class.java
        } == 2 && type.constructors.any { it.parameterTypes.contentEquals(arrayOf(Any::class.java, Any::class.java)) }
    }) {
        methods { matcher { name("toString"); paramTypes(); returnType("java.lang.String"); usingEqStrings("(", ", ") } }
            .map { it.getClassInstance(loader) }.distinct()
    } }
    val coverPresent by lazy { pairField("present", 0) }
    val coverBitmap by lazy { pairField("bitmap", 1) }
    private fun pairField(role: String, index: Int): Field = HostInterfaces.field(loader, "salt.artwork.pair-$role.v1",
        validate = { it.declaringClass == coverPair && !Modifier.isStatic(it.modifiers) && it.type == Any::class.java }) {
        methods { matcher { declaredClass(coverPair.name); name("<init>"); paramTypes("java.lang.Object", "java.lang.Object") } }
            .flatMap { fieldsAssignedFromParameter(it, index) }.map { it.resolve(loader) as Field }
    }
    val coverFlow by lazy { HostInterfaces.field(loader, "salt.artwork.flow.v2", validate = {
        it.declaringClass == controller && Modifier.isStatic(it.modifiers) && hasValue(it.type)
    }) {
        methods { matcher { paramTypes("java.lang.Object"); returnType("java.lang.Object")
            invokeMethods { add { declaredClass("com.salt.music.service.SongPicture"); name("getDefaultCoverBitmap\$default") } }
        } }.flatMap { fieldsUpdatedWith(it, coverPair.name) }.filter { it.owner == controller.name }
            .map { it.resolve(loader) as Field }
    } }
    private fun hasValue(type: Class<*>) = runCatching { type.getMethod("getValue").parameterCount == 0 }.getOrDefault(false)
}
