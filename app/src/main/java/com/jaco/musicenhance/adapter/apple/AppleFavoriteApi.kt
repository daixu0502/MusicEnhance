package com.jaco.musicenhance.adapter.apple

import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import java.lang.reflect.Modifier

/** Follow the item/like/query protocol; don't guess interchangeable boolean() or void() actions. */
internal class AppleFavoriteApi(private val loader: ClassLoader) {
    private val itemType = loader.loadClass("com.apple.android.music.model.BaseContentItem")
    val libraryType = HostInterfaces.type(loader, "apple.favorite.library.v1", known = {
        loader.loadClass("com.apple.android.medialibrary.library.a")
    }, validate = { it.getMethod("isReady").returnType == Boolean::class.javaPrimitiveType }) {
        methods {
            searchPackages("com.apple.android.medialibrary.library")
            matcher { name = "isReady"; paramCount = 0; returnType = "boolean" }
        }.mapNotNull { it.declaredClass?.getInstance(loader) }.distinct()
    }
    val library = HostInterfaces.method(loader, "apple.favorite.library-instance.v1", known = { libraryType.getMethod("W") },
        validate = { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == libraryType }) {
        libraryType.methods.filter { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == libraryType }
    }
    val likeType = HostInterfaces.type(loader, "apple.favorite.like-enum.v1", known = {
        loader.loadClass("com.apple.android.medialibrary.library.MediaLibrary\$f")
    }, validate = { type -> runCatching { type.getField("None"); type.getField("Liked") }.isSuccess }) {
        methods {
            searchPackages("com.apple.android.medialibrary.library")
            matcher { name = "<clinit>"; usingEqStrings("None", "Liked") }
        }.mapNotNull { it.declaredClass?.getInstance(loader) }.distinct()
    }
    val update = HostInterfaces.method(loader, "apple.favorite.update.v1", known = {
        loader.loadClass("B8.s").getMethod("u", itemType, likeType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }, validate = { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(itemType, likeType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) && it.returnType == Void.TYPE }) {
        methods { matcher { paramTypes(itemType, likeType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType); returnType = "void" } }
            .map { it.getMethodInstance(loader) }.filter { Modifier.isStatic(it.modifiers) }
    }
    private val actions get() = update.declaringClass
    val supported = HostInterfaces.method(loader, "apple.favorite.supported.v1", known = { actions.getMethod("r", itemType) },
        validate = { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(itemType)) && it.returnType == Boolean::class.javaPrimitiveType }) {
        methods { matcher { declaredClass = actions.name; name = update.name; paramCount = 4 } }
            .flatMap { it.invokes }.filter { it.className == actions.name && it.paramTypeNames == listOf(itemType.name) && it.returnTypeName == "boolean" }
            .map { it.getMethodInstance(loader) }.distinct()
    }
    val query = HostInterfaces.method(loader, "apple.favorite.query.v1", known = { actions.getMethod("w", itemType) },
        validate = { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(itemType)) && it.returnType != Void.TYPE && !it.returnType.isPrimitive }) {
        methods { matcher { declaredClass = actions.name; paramTypes(itemType) } }
            .map { it.getMethodInstance(loader) }.filter { method -> Modifier.isStatic(method.modifiers) && hasSubscribeProtocol(method.returnType) }
    }
    val singleType get() = query.returnType
    val subscribe = HostInterfaces.method(loader, "apple.favorite.subscribe.v1", known = {
        singleType.getMethod("k", loader.loadClass("Wf.e"), loader.loadClass("Wf.e"))
    }, validate = ::isSubscribe) {
        singleType.methods.filter(::isSubscribe)
    }
    val consumerType get() = subscribe.parameterTypes[0]
    private val disposableType get() = subscribe.returnType
    val dispose = HostInterfaces.method(loader, "apple.favorite.dispose.v1", known = { disposableType.getMethod("f") },
        validate = { it.parameterCount == 0 && it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) }) {
        disposableType.methods.filter { it.parameterCount == 0 && it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) && it.declaringClass != Any::class.java }
    }

    fun optionalItem(optional: Any): Any? {
        val type = optional.javaClass
        val empty = HostInterfaces.method(loader, "apple.favorite.optional-empty.v1", known = { type.getMethod("b") },
            validate = { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType }) {
            methods { matcher { declaredClass = type.name; name = "isEmpty"; paramCount = 0; returnType = "boolean" } }
                .map { it.getMethodInstance(loader) }
        }
        if (empty.invoke(optional) == true) return null
        val value = HostInterfaces.method(loader, "apple.favorite.optional-item.v1", known = { type.getMethod("a") },
            validate = { it.parameterCount == 0 && it.returnType != Void.TYPE && !it.returnType.isPrimitive }) {
            methods { matcher { declaredClass = type.name; name = "get"; paramCount = 0; returnType = "java.lang.Object" } }
                .map { it.getMethodInstance(loader) }
        }
        return value.invoke(optional)
    }

    private fun hasSubscribeProtocol(type: Class<*>) = type.methods.any(::isSubscribe)
    private fun isSubscribe(method: java.lang.reflect.Method) = method.parameterCount == 2 &&
        method.parameterTypes[0] == method.parameterTypes[1] && method.parameterTypes[0].isInterface &&
        runCatching { method.parameterTypes[0].getMethod("accept", Any::class.java) }.isSuccess &&
        method.returnType != Void.TYPE && !Modifier.isStatic(method.modifiers)
}
