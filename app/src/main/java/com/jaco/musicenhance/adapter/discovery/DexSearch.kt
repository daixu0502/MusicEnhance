package com.jaco.musicenhance.adapter.discovery

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.io.Closeable

/** DEX result objects belong to this background scan and must not escape a discovery callback. */
internal class DexSearch internal constructor(
    val loader: ClassLoader,
    private val bridges: List<DexKitBridge>,
) : Closeable {
    fun methods(query: FindMethod.() -> Unit): List<MethodData> = bridges.flatMap {
        it.findMethod(FindMethod().apply(query).also { request ->
            require(request.matcher != null && !request.findFirst) { "Discovery must match all candidates" }
        })
    }.distinctBy { it.descriptor }.bounded()

    fun classes(query: FindClass.() -> Unit): List<ClassData> = bridges.flatMap {
        it.findClass(FindClass().apply(query).also { request ->
            require(request.matcher != null && !request.findFirst) { "Discovery must match all candidates" }
        })
    }.distinctBy { it.name }.bounded()

    fun fields(query: FindField.() -> Unit): List<FieldData> = bridges.flatMap {
        it.findField(FindField().apply(query).also { request ->
            require(request.matcher != null && !request.findFirst) { "Discovery must match all candidates" }
        })
    }.distinctBy { it.descriptor }.bounded()

    private fun <T> List<T>.bounded(): List<T> = also {
        require(size <= MAX_CANDIDATES) { "Feature query is too broad: $size candidates" }
    }

    override fun close() { bridges.forEach { it.close() } }

    companion object { private const val MAX_CANDIDATES = 512 }
}
