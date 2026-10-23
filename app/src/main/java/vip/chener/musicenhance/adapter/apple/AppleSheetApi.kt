package vip.chener.musicenhance.adapter.apple

import android.view.View
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.reflect.Modifier

/** Scope Material state methods to the audio player's owned sheet, never all bottom sheets. */
internal class AppleSheetApi(private val loader: ClassLoader) {
    val playerType = loader.loadClass("com.apple.android.music.common.activity.PlayerActivity")
    private val sheetType = loader.loadClass("com.google.android.material.bottomsheet.BottomSheetBehavior")
    val sheet = HostInterfaces.field(loader, "apple.sheet.instance.v1", known = { playerType.getField("a1") },
        validate = { !Modifier.isStatic(it.modifiers) && sheetType.isAssignableFrom(it.type) }) {
        playerType.declaredFields.filter { !Modifier.isStatic(it.modifiers) && sheetType.isAssignableFrom(it.type) }
    }.apply { isAccessible = true }
    val changed = HostInterfaces.method(loader, "apple.sheet.state-internal.v1", known = { sheetType.getDeclaredMethod("H", Int::class.javaPrimitiveType) },
        validate = { it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) && it.returnType == Void.TYPE }) {
        methods { matcher { declaredClass = sheetType.name; paramTypes(Int::class.javaPrimitiveType); returnType = "void" } }
            .filter { method -> method.usingFields.any { it.usingType == FieldUsingType.Write && it.field.className == sheetType.name && it.field.typeName == "int" } &&
                method.invokes.any { it.paramTypeNames == listOf("android.view.View", "int") && it.returnTypeName == "void" } }
            .map { it.getMethodInstance(loader) }
    }.apply { isAccessible = true }
    val state = HostInterfaces.field(loader, "apple.sheet.state.v1", known = { sheetType.getField("G") },
        validate = { it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }) {
        methods { matcher { declaredClass = sheetType.name; name = changed.name; paramTypes(Int::class.javaPrimitiveType) } }
            .flatMap { it.usingFields }.filter { it.usingType == FieldUsingType.Write }
            .map { it.field }.filter { it.className == sheetType.name && it.typeName == "int" }
            .map { it.getFieldInstance(loader) }.distinct()
    }.apply { isAccessible = true }
    val request = HostInterfaces.method(loader, "apple.sheet.request.v1", known = { sheetType.getMethod("G", Int::class.javaPrimitiveType) },
        validate = { it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) && it.returnType == Void.TYPE }) {
        methods { matcher {
            declaredClass = sheetType.name; paramTypes(Int::class.javaPrimitiveType); returnType = "void"
            usingEqStrings("STATE_DRAGGING and STATE_SETTLING should not be set externally.")
        } }.map { it.getMethodInstance(loader) }
    }.apply { isAccessible = true }
    val settle = HostInterfaces.method(loader, "apple.sheet.settle.v1", known = {
        sheetType.getMethod("L", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }, validate = { it.parameterTypes.contentEquals(arrayOf(View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) && it.returnType == Void.TYPE }) {
        methods { matcher {
            declaredClass = sheetType.name; paramTypes(View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType); returnType = "void"
        } }.filter { method -> method.invokes.any { it.className == sheetType.name && it.name == changed.name } }
            .map { it.getMethodInstance(loader) }
    }.apply { isAccessible = true }
    val collapse = visibility("collapse", "h1", 4)
    val expand = visibility("expand", "j1", 3)

    fun isExpanded(activity: Any) = sheet.get(activity)?.let { state.getInt(it) == 3 } == true

    private fun visibility(key: String, oldName: String, targetState: Int) = HostInterfaces.method(loader, "apple.sheet.$key.v1",
        known = { playerType.getMethod(oldName, BooleanArray::class.java) },
        validate = { it.parameterTypes.contentEquals(arrayOf(BooleanArray::class.java)) && it.returnType == Void.TYPE }) {
        methods { matcher { declaredClass = playerType.name; paramTypes(BooleanArray::class.java); returnType = "void"; usingNumbers(targetState) } }
            .filter { method -> method.invokes.any { it.className == sheetType.name && it.name == request.name } &&
                method.usingFields.any { it.field.name == sheet.name && it.field.className == playerType.name } }
            .map { it.getMethodInstance(loader) }
    }
}
