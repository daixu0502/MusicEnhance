package com.jaco.musicenhance.adapter.salt

import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import java.lang.reflect.Modifier
import org.luckypray.dexkit.query.enums.StringMatchType

/** Resolves the sheet's declared types instead of depending on Compose's obfuscated interfaces. */
internal class SaltSheetApi(loader: ClassLoader) {
    val sheetType: Class<*> = HostInterfaces.type(loader, "salt.sheet.type.v1", validate = { type -> type.declaredConstructors.size == 1 && type.declaredConstructors[0].parameterTypes.firstOrNull()?.isEnum == true }) {
        val enums = methods { matcher { name = "<clinit>"; usingEqStrings("Expanded", "Collapsed") } }
            .map { it.className }.distinct()
        val playerTypes = methods { matcher {
            declaredClass("com.salt.music.ui.MainActivity"); usingEqStrings("auto_open_playback_screen")
        } }.flatMap { it.invokes }.map { it.returnTypeName }.toSet()
        enums.flatMap { enum -> methods { matcher { name = "<init>"; paramTypes(enum, null, null, null) } } }
            .filter { it.paramTypeNames.firstOrNull() in enums && it.className in playerTypes }
            .map { it.getConstructorInstance(loader).declaringClass }.distinct()
    }
    private val sheetState = HostInterfaces.field(loader, "salt.sheet.state.v1",
        validate = { !Modifier.isStatic(it.modifiers) }) {
        val constructor = methods { matcher { declaredClass = sheetType.name; name = "<init>" } }.single()
        val stateTypes = constructor.invokes.filter { it.isConstructor && it.paramTypeNames.firstOrNull() == constructor.paramTypeNames.firstOrNull() }
            .map { it.className }
        sheetType.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type.name in stateTypes }
    }
    val stateType: Class<*> = sheetState.type
    private val setCurrent = HostInterfaces.method(loader, "salt.sheet.set-current.v1",
        validate = { it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && it.returnType == Void.TYPE }) {
        methods { matcher { declaredClass = stateType.name; paramTypes(Any::class.java); returnType = "void" } }
            .filter { it.invokes.any { called -> called.name == "setValue" && called.paramTypeNames == listOf("java.lang.Object") } }
            .map { it.getMethodInstance(loader) }
    }
    private val currentValue = HostInterfaces.field(loader, "salt.sheet.current.v1") {
        methods { matcher { declaredClass = stateType.name; name = setCurrent.name; paramTypes(Any::class.java) } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.className == stateType.name }
            .map { it.getFieldInstance(loader) }.distinct()
    }
    private val offsetValue = HostInterfaces.field(loader, "salt.sheet.offset.v1") {
        methods { matcher {
            declaredClass = stateType.name; paramCount = 0; returnType = "float"
            usingEqStrings("The offset was read before being initialized. Did you access the offset in a phase before layout, like effects or composition?")
        } }.flatMap { it.usingFields }.map { it.field }.filter { it.className == stateType.name }
            .map { it.getFieldInstance(loader) }.distinct()
    }
    private val anchors = HostInterfaces.method(loader, "salt.sheet.anchors.v1",
        validate = { it.parameterCount == 0 && it.returnType != Void.TYPE }) {
        val anchorTypes = methods { matcher { name = "toString"; usingStrings(listOf("MapDraggableAnchors("), StringMatchType.Contains) } }.map { it.className }
        methods { matcher { declaredClass = stateType.name; paramCount = 0 } }
            .filter { it.returnTypeName in anchorTypes }.map { it.getMethodInstance(loader) }
    }
    private val anchorPosition = HostInterfaces.method(loader, "salt.sheet.anchor-position.v1",
        validate = { it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && it.returnType == Float::class.javaPrimitiveType }) {
        methods { matcher { declaredClass = anchors.returnType.name; paramTypes(Any::class.java); returnType = "float" } }
            .filter { it.invokes.any { called -> called.className == "java.util.Map" && called.name == "get" } }
            .map { it.getMethodInstance(loader) }
    }
    private val readValue = currentValue.type.getMethod("getValue")
    private val setOffset = offsetValue.type.getMethod("setValue", Any::class.java)
    private val values = sheetType.declaredConstructors.single().parameterTypes.first().enumConstants
        ?: error("Salt sheet value is not an enum")
    val expanded: Any = values.single { (it as Enum<*>).name == "Expanded" }
    val collapsed: Any = values.single { (it as Enum<*>).name == "Collapsed" }
    val animate = HostInterfaces.method(loader, "salt.sheet.animate.v1", validate = { Modifier.isStatic(it.modifiers) && it.parameterCount == 4 && it.parameterTypes[0] == stateType &&
        it.parameterTypes[1] == Any::class.java && it.parameterTypes[2] == Float::class.javaPrimitiveType && it.returnType == Any::class.java }) {
        methods { matcher { paramTypes(stateType.name, "java.lang.Object", "float", null); returnType = "java.lang.Object" } }
            .filter { method -> method.callers.any { it.className == sheetType.name } }
            .map { it.getMethodInstance(loader) }
    }
    private val unitType = HostInterfaces.type(loader, "salt.kotlin.unit.v1") {
        methods { matcher { name = "toString"; paramCount = 0; usingEqStrings("kotlin.Unit") } }
            .map { it.getMethodInstance(loader).declaringClass }.distinct()
    }
    val unit: Any = requireNotNull(unitType.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == unitType }.apply { isAccessible = true }.get(null))

    fun state(sheet: Any): Any = requireNotNull(sheetState.get(sheet))
    fun isExpanded(sheet: Any) = readValue.invoke(currentValue.get(state(sheet))) === expanded
    fun snap(sheet: Any, value: Any) {
        val state = state(sheet)
        val position = anchorPosition.invoke(anchors.invoke(state), value) as Float
        if (position.isFinite()) setOffset.invoke(offsetValue.get(state), position)
        setCurrent.invoke(state, value)
    }
}
