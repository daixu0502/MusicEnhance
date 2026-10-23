package vip.chener.musicenhance.adapter.discovery

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Only descriptors cross the disk boundary; never persist a host object or a native DEX handle. */
internal data class InterfaceDescriptor(
    val kind: String, val owner: String, val name: String,
    val parameters: List<String>, val result: String, val isStatic: Boolean,
) {
    fun resolve(loader: ClassLoader): Any {
        val type = loadType(owner, loader)
        val member = when (kind) {
            "class" -> type
            "method" -> type.getDeclaredMethod(name, *parameters.map { loadType(it, loader) }.toTypedArray())
                .also { require(it.returnType.name == result && Modifier.isStatic(it.modifiers) == isStatic) }
            "field" -> type.getDeclaredField(name)
                .also { require(it.type.name == result && Modifier.isStatic(it.modifiers) == isStatic) }
            "constructor" -> type.getDeclaredConstructor(*parameters.map { loadType(it, loader) }.toTypedArray())
            else -> error("Unknown interface descriptor: $kind")
        }
        if (member is java.lang.reflect.AccessibleObject) member.isAccessible = true
        return member
    }

    fun toJson() = JSONObject().put("kind", kind).put("owner", owner).put("name", name)
        .put("parameters", JSONArray(parameters)).put("result", result).put("static", isStatic)

    companion object {
        fun of(value: Any): InterfaceDescriptor = when (value) {
            is Class<*> -> InterfaceDescriptor("class", value.name, "", emptyList(), "", false)
            is Method -> InterfaceDescriptor("method", value.declaringClass.name, value.name,
                value.parameterTypes.map { it.name }, value.returnType.name, Modifier.isStatic(value.modifiers))
            is Field -> InterfaceDescriptor("field", value.declaringClass.name, value.name,
                emptyList(), value.type.name, Modifier.isStatic(value.modifiers))
            is Constructor<*> -> InterfaceDescriptor("constructor", value.declaringClass.name, "<init>",
                value.parameterTypes.map { it.name }, "void", false)
            else -> error("Only reflection descriptors can be cached")
        }

        fun fromJson(json: JSONObject) = InterfaceDescriptor(json.getString("kind"), json.getString("owner"),
            json.getString("name"), json.getJSONArray("parameters").let { array ->
                List(array.length()) { array.getString(it) }
            }, json.getString("result"), json.getBoolean("static"))

        private fun loadType(name: String, loader: ClassLoader): Class<*> = when (name) {
            "void" -> Void.TYPE
            "boolean" -> java.lang.Boolean.TYPE
            "byte" -> java.lang.Byte.TYPE
            "short" -> java.lang.Short.TYPE
            "char" -> java.lang.Character.TYPE
            "int" -> java.lang.Integer.TYPE
            "long" -> java.lang.Long.TYPE
            "float" -> java.lang.Float.TYPE
            "double" -> java.lang.Double.TYPE
            else -> Class.forName(name, false, loader)
        }
    }
}
