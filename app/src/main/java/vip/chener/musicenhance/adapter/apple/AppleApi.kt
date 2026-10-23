package vip.chener.musicenhance.adapter.apple

import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Semantic Media3 names are useful anchors; an erased return type alone is never an action. */
internal class AppleApi(private val loader: ClassLoader) {
    val controllerType: Class<*> = HostInterfaces.type(loader, "apple.browser.type.v1",
        known = { loader.loadClass("E3.F") },
        validate = ::isController,
    ) {
        methods { matcher { name = "getRepeatMode"; paramCount = 0; returnType = "int" } }
            .map { it.getMethodInstance(loader).declaringClass }.distinct().filter(::isController)
    }

    val browser: Field = HostInterfaces.field(loader, "apple.browser.instance.v1",
        known = { loader.loadClass("c9.a").getField("f") },
        validate = { Modifier.isStatic(it.modifiers) && controllerType.isAssignableFrom(it.type) },
    ) {
        fields { matcher { type = controllerType.name } }.map { it.getFieldInstance(loader) }
            .filter { Modifier.isStatic(it.modifiers) }
    }

    val convertItem: Method by lazy { HostInterfaces.method(loader, "apple.metadata.item.v1",
        known = { loader.loadClass("com.apple.android.music.player.O").getMethod("b", loader.loadClass("v3.v")) },
        validate = { Modifier.isStatic(it.modifiers) && it.parameterCount == 1 },
    ) {
        methods {
            searchPackages("com.apple.android.music.player")
            matcher { paramCount = 1; returnType = "com.apple.android.music.model.PlaybackItem" }
        }.filter { method -> method.invokes.any { it.isConstructor && it.className == method.returnTypeName } }
            .map { it.getMethodInstance(loader) }.filter { Modifier.isStatic(it.modifiers) }
    } }

    val metadata: Method by lazy { HostInterfaces.method(loader, "apple.browser.metadata.v1",
        known = { controllerType.getMethod("m0") },
        validate = { it.parameterCount == 0 && it.returnType == convertItem.parameterTypes[0] },
    ) {
        methods { matcher { declaredClass = controllerType.name; paramCount = 0; returnType = convertItem.parameterTypes[0].name } }
            .map { it.getMethodInstance(loader) }
    } }

    val commandAvailable: Method by lazy { method("commands", "d", "isCommandAvailable", Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!) }
    val currentIndex: Method by lazy { method("queue.index", "e0", "getCurrentMediaItemIndex", Int::class.javaPrimitiveType!!) }

    private fun method(key: String, knownName: String, semanticName: String, result: Class<*>, vararg params: Class<*>) =
        HostInterfaces.method(loader, "apple.browser.$key.v1", known = { controllerType.getMethod(knownName, *params) },
            validate = { it.returnType == result && it.parameterTypes.contentEquals(params) },
        ) {
            methods { matcher { declaredClass = controllerType.name; name = semanticName; paramTypes(*params); returnType = result.name } }
                .map { it.getMethodInstance(loader) }
        }

    private fun isController(type: Class<*>) = runCatching {
        type.getMethod("getRepeatMode").returnType == Int::class.javaPrimitiveType &&
            type.getMethod("getShuffleModeEnabled").returnType == Boolean::class.javaPrimitiveType &&
            type.getMethod("setRepeatMode", Int::class.javaPrimitiveType).returnType == Void.TYPE &&
            type.getMethod("setShuffleModeEnabled", Boolean::class.javaPrimitiveType).returnType == Void.TYPE &&
            type.getMethod("getCurrentTimeline").returnType != Void.TYPE
    }.getOrDefault(false)
}
