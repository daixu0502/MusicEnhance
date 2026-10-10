package com.jaco.musicenhance.adapter.salt

/** Resolves the sheet's declared types instead of depending on Compose's obfuscated interfaces. */
internal class SaltSheetApi(loader: ClassLoader, val contract: SaltApi = SaltApi.resolve(loader)) {
    val sheetType: Class<*> = loader.loadClass("androidx.media3.${contract.sheetClass}")
    private val sheetState = sheetType.getField("\u037f")
    val stateType: Class<*> = sheetState.type
    private val currentValue = stateType.getField("\u052e")
    private val offsetValue = stateType.getField("\u0588")
    private val setCurrent = stateType.getMethod("\u052d", Any::class.java)
    private val anchors = stateType.getMethod("\u052a")
    private val anchorPosition = anchors.returnType.getMethod("\u052b", Any::class.java)
    private val readValue = currentValue.type.getMethod("getValue")
    private val setOffset = offsetValue.type.getMethod("setValue", Any::class.java)
    private val values = sheetType.declaredConstructors.single().parameterTypes.first().enumConstants
        ?: error("Salt sheet value is not an enum")
    val expanded: Any = values.single { (it as Enum<*>).name == "Expanded" }
    val collapsed: Any = values.single { (it as Enum<*>).name == "Collapsed" }
    val animate = loader.loadClass("androidx.media3.${contract.animateClass}").declaredMethods.single {
        it.name == contract.animateMethod && it.parameterTypes.size == 4 &&
            it.parameterTypes[0] == stateType && it.parameterTypes[1] == Any::class.java &&
            it.parameterTypes[2] == Float::class.javaPrimitiveType && it.returnType == Any::class.java
    }
    val unit: Any = requireNotNull(loader.loadClass("androidx.media3.${contract.unitClass}").getField("\u037f").get(null))

    fun state(sheet: Any): Any = requireNotNull(sheetState.get(sheet))
    fun isExpanded(sheet: Any) = readValue.invoke(currentValue.get(state(sheet))) === expanded
    fun snap(sheet: Any, value: Any) {
        val state = state(sheet)
        val position = anchorPosition.invoke(anchors.invoke(state), value) as Float
        if (position.isFinite()) setOffset.invoke(offsetValue.get(state), position)
        setCurrent.invoke(state, value)
    }
}
