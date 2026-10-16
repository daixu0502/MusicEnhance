package com.jaco.musicenhance.adapter.discovery

import java.util.zip.ZipFile

/** Operand checks for erased host fields. Used only after DexKit narrows the semantic method.
 * No host code is executed; field roles come from casts, constructor parameters and labels.
 * Byte arrays belong to a single background search, never the descriptor cache.
 */
internal class DexCode(private val apkPaths: List<String>) {
    private val files = mutableListOf<Dex>()
    private var loaded = false

    fun fieldsReadAs(method: String, typeName: String): List<InterfaceDescriptor> = inspect(method) { dex, code ->
        buildList {
            for ((index, instruction) in code.withIndex()) {
                val field = dex.readField(instruction) ?: continue
                var receiver = instruction.a
                var value = -1
                for (next in code.drop(index + 1).take(8)) {
                    if (next.isMove) {
                        if (next.b == receiver) receiver = next.a
                        if (next.b == value) value = next.a
                    } else if (next.isInvoke) {
                        if (next.registers.singleOrNull() != receiver || !dex.method(next.reference).endsWith("->getValue()Ljava/lang/Object;")) break
                    } else if (next.opcode == 0x0c) value = next.a
                    else if (next.opcode == 0x1f) {
                        if (next.a == value && dex.type(next.reference) == typeName) add(field)
                        break
                    } else break
                }
            }
        }
    }

    fun fieldsUpdatedWith(method: String, typeName: String): List<InterfaceDescriptor> = inspect(method) { dex, code ->
        buildList {
            for ((index, instruction) in code.withIndex()) {
                val field = dex.readField(instruction) ?: continue
                val fields = mutableMapOf(instruction.a to field)
                val types = mutableMapOf<Int, String>()
                for (next in code.drop(index + 1).take(24)) {
                    when {
                        next.isMove -> {
                            val sourceField = fields[next.b]
                            val sourceType = types[next.b]
                            fields.remove(next.a); types.remove(next.a)
                            sourceField?.let { fields[next.a] = it }
                            sourceType?.let { types[next.a] = it }
                        }
                        next.opcode == 0x22 -> { fields.remove(next.a); types[next.a] = dex.type(next.reference) }
                        next.opcode == 0x1f -> types[next.a] = dex.type(next.reference)
                        dex.readField(next) != null -> { fields[next.a] = requireNotNull(dex.readField(next)); types.remove(next.a) }
                        next.isInvoke -> {
                            val invoked = dex.method(next.reference)
                            val registers = next.registers
                            if (invoked.endsWith("(Ljava/lang/Object;Ljava/lang/Object;)Z") &&
                                fields[registers.firstOrNull()] == field && types[registers.lastOrNull()] == typeName) add(field)
                        }
                        next.opcode in 0x0a..0x0d || next.opcode in 0x12..0x1c -> {
                            fields.remove(next.a); types.remove(next.a)
                        }
                        next.opcode in 0x0e..0x11 || next.opcode in 0x28..0x3d -> break
                        next.opcode == 0x00 -> Unit
                        else -> break // Unknown register writes cannot preserve proven provenance.
                    }
                }
            }
        }
    }

    fun fieldsAssignedFromParameter(method: String, index: Int): List<InterfaceDescriptor> = inspect(method) { dex, code ->
        // These callers validate an instance constructor whose parameters are all references.
        val register = dex.registerCount - dex.incomingWords + 1 + index
        code.filter { it.opcode == 0x5b && it.a == register }.map { dex.field(it.reference, false) }
    }

    fun fieldsFollowingString(method: String, label: String, typeName: String): List<InterfaceDescriptor> = inspect(method) { dex, code ->
        // R8 can merge multiple append calls into a helper and load their arguments in a
        // different order. Follow the builder's actual argument order through those helpers.
        val tokens = try { format(dex, code, emptyList(), 0)?.tokens.orEmpty() }
            catch (_: UnknownFormat) { emptyList() }
        tokens.indices.filter { (tokens[it] as? Literal)?.text == label }.mapNotNull { index ->
            (tokens.getOrNull(index + 1) as? Member)?.field?.takeIf { it.result == typeName }
        }
    }

    private sealed interface Formatted
    private data class Literal(val text: String) : Formatted
    private data class Member(val field: InterfaceDescriptor) : Formatted
    private class Builder(val tokens: MutableList<Formatted> = mutableListOf()) : Formatted
    private class UnknownFormat : Exception()

    private fun format(dex: Dex, code: List<Instruction>, arguments: List<Formatted?>, depth: Int): Builder? {
        if (depth > 3) throw UnknownFormat()
        val registers = mutableMapOf<Int, Formatted?>()
        val first = dex.registerCount - dex.incomingWords
        arguments.forEachIndexed { index, value -> registers[first + index] = value }
        var result: Formatted? = null
        for (instruction in code) {
            val opcode = instruction.opcode
            when {
                opcode == 0x00 -> Unit
                instruction.isMove -> {
                    registers[instruction.a] = registers[instruction.b]
                    if (opcode in 0x04..0x06) registers[instruction.a + 1] = registers[instruction.b]
                }
                opcode in 0x1a..0x1b -> registers[instruction.a] = Literal(dex.string(instruction.reference))
                opcode == 0x22 -> registers[instruction.a] = if (dex.type(instruction.reference) == "java.lang.StringBuilder") Builder() else null
                opcode in 0x52..0x58 || opcode in 0x60..0x66 -> {
                    registers[instruction.a] = dex.readField(instruction)?.let(::Member)
                    if (opcode == 0x53 || opcode == 0x61) registers[instruction.a + 1] = registers[instruction.a]
                }
                opcode in 0x0a..0x0c -> { registers[instruction.a] = result; result = null }
                instruction.isInvoke -> {
                    val invoked = dex.method(instruction.reference)
                    val values = instruction.registers.map { registers[it] }
                    val builder = values.firstOrNull() as? Builder
                    result = when {
                        invoked.startsWith("Ljava/lang/StringBuilder;-><init>(") -> {
                            builder?.tokens?.addAll(values.drop(1).map { it ?: throw UnknownFormat() }); null
                        }
                        invoked.startsWith("Ljava/lang/StringBuilder;->append(") -> {
                            builder?.tokens?.addAll(values.drop(1).distinct().map { it ?: throw UnknownFormat() }); builder
                        }
                        invoked == "Ljava/lang/StringBuilder;->toString()Ljava/lang/String;" -> builder
                        values.any { it is Builder || it is Member } &&
                            (invoked.endsWith("Ljava/lang/StringBuilder;") || (values.any { it is Builder } && invoked.endsWith(")V"))) -> {
                            format(dex, dex.code(invoked) ?: throw UnknownFormat(), values, depth + 1)
                        }
                        else -> null
                    }
                }
                opcode == 0x11 -> return registers[instruction.a] as? Builder
                opcode == 0x0e -> return null
                opcode in 0x28..0x3d -> throw UnknownFormat() // Never infer a label across unproven branches.
                opcode in 0x12..0x19 -> registers[instruction.a] = null
                else -> throw UnknownFormat()
            }
        }
        return null
    }

    private fun inspect(method: String, read: (Dex, List<Instruction>) -> List<InterfaceDescriptor>): List<InterfaceDescriptor> {
        if (!loaded) {
            apkPaths.forEach { path -> ZipFile(path).use { zip ->
                zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) && it.size in 112..MAX_DEX_BYTES }
                    .forEach { entry -> files += Dex(zip.getInputStream(entry).use { it.readBytes() }) }
            } }
            loaded = true
        }
        return files.flatMap { dex -> dex.code(method)?.let { read(dex, it) }.orEmpty() }.distinct()
    }

    private data class Instruction(val opcode: Int, val a: Int, val b: Int, val reference: Int, val registers: List<Int>) {
        val isMove get() = opcode in 0x01..0x09
        val isInvoke get() = opcode in 0x6e..0x72 || opcode in 0x74..0x78
    }

    private class Dex(private val bytes: ByteArray) {
        private val strings = int(0x3c)
        private val types = int(0x44)
        private val protos = int(0x4c)
        private val fields = int(0x54)
        private val methods = int(0x5c)
        var registerCount = 0; private set
        var incomingWords = 0; private set
        init { require(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) }

        private fun short(offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        private fun int(offset: Int): Int = short(offset) or (short(offset + 2) shl 16)
        private fun uleb(cursor: IntArray): Int {
            var value = 0
            for (shift in 0..28 step 7) {
                val part = bytes[cursor[0]++].toInt() and 255
                value = value or ((part and 127) shl shift)
                if (part < 128) return value
            }
            error("Invalid DEX ULEB128")
        }
        fun string(index: Int): String {
            val cursor = intArrayOf(int(strings + index * 4))
            val chars = CharArray(uleb(cursor))
            for (position in chars.indices) {
                val first = bytes[cursor[0]++].toInt() and 255
                chars[position] = when {
                    first in 1..127 -> first
                    first in 0xc0..0xdf -> ((first and 31) shl 6) or (bytes[cursor[0]++].toInt() and 63)
                    first in 0xe0..0xef -> ((first and 15) shl 12) or
                        ((bytes[cursor[0]++].toInt() and 63) shl 6) or (bytes[cursor[0]++].toInt() and 63)
                    else -> error("Invalid DEX modified UTF-8")
                }.toChar()
            }
            require(bytes[cursor[0]].toInt() == 0)
            return String(chars)
        }
        private fun descriptor(index: Int) = string(int(types + index * 4))
        fun type(index: Int): String = descriptor(index).let { descriptor -> when {
            descriptor.startsWith("L") -> descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            descriptor.startsWith("[") -> descriptor.replace('/', '.')
            else -> mapOf("Z" to "boolean", "B" to "byte", "S" to "short", "C" to "char", "I" to "int", "J" to "long", "F" to "float", "D" to "double", "V" to "void").getValue(descriptor)
        } }
        fun field(index: Int, static: Boolean): InterfaceDescriptor {
            val offset = fields + index * 8
            return InterfaceDescriptor("field", type(short(offset)), string(int(offset + 4)), emptyList(), type(short(offset + 2)), static)
        }
        fun readField(instruction: Instruction): InterfaceDescriptor? = when (instruction.opcode) {
            in 0x52..0x58 -> field(instruction.reference, false)
            in 0x60..0x66 -> field(instruction.reference, true)
            else -> null
        }
        fun method(index: Int): String {
            val offset = methods + index * 8
            val proto = protos + short(offset + 2) * 12
            val parameters = int(proto + 8)
            return buildString {
                append(descriptor(short(offset))); append("->"); append(string(int(offset + 4))); append('(')
                if (parameters != 0) repeat(int(parameters)) { append(descriptor(short(parameters + 4 + it * 2))) }
                append(')'); append(descriptor(int(proto + 4)))
            }
        }
        fun code(target: String): List<Instruction>? {
            val owner = target.substringBefore("->")
            val ownerType = (0 until int(0x40)).singleOrNull { descriptor(it) == owner } ?: return null
            val method = (0 until int(0x58)).firstOrNull { short(methods + it * 8) == ownerType && method(it) == target } ?: return null
            val definitions = int(0x64)
            val definition = (0 until int(0x60)).firstOrNull { int(definitions + it * 32) == ownerType } ?: return null
            val cursor = intArrayOf(int(definitions + definition * 32 + 24))
            if (cursor[0] == 0) return null
            val staticFields = uleb(cursor); val instanceFields = uleb(cursor)
            val directMethods = uleb(cursor); val virtualMethods = uleb(cursor)
            repeat(staticFields + instanceFields) { uleb(cursor); uleb(cursor) }
            repeat(2) { group ->
                var current = 0
                repeat(if (group == 0) directMethods else virtualMethods) {
                    current += uleb(cursor); uleb(cursor)
                    val codeOffset = uleb(cursor)
                    if (current == method && codeOffset != 0) return instructions(codeOffset)
                }
            }
            return null
        }
        private fun instructions(offset: Int): List<Instruction> = buildList {
            registerCount = short(offset); incomingWords = short(offset + 2)
            var position = offset + 16
            val end = position + int(offset + 12) * 2
            while (position < end) {
                val unit = short(position)
                val opcode = unit and 255
                val next = if (position + 2 < end) short(position + 2) else 0
                val third = if (position + 4 < end) short(position + 4) else 0
                val width = when (opcode) {
                    0x00 -> when (unit) { 0x100 -> 4 + next * 2; 0x200 -> 2 + next * 4; 0x300 -> 4 + ((next.toLong() * int(position + 4) + 1) / 2).toInt(); else -> 1 }
                    0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c, in 0x6e..0x72, in 0x74..0x78, 0xfc, 0xfd -> 3
                    0x18 -> 5
                    0xfa, 0xfb -> 4
                    0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29, in 0x2d..0x3d, in 0x44..0x6d, in 0x90..0xaf, in 0xd0..0xe2, 0xfe, 0xff -> 2
                    else -> 1
                }
                require(width > 0 && position + width * 2 <= end) { "Invalid DEX instruction" }
                val smallRegisters = opcode in setOf(0x01, 0x04, 0x07, 0x12) || opcode in 0x52..0x5f
                val a = if (opcode in setOf(0x03, 0x06, 0x09)) next else if (smallRegisters) (unit shr 8) and 15 else unit shr 8
                val b = if (opcode in setOf(0x03, 0x06, 0x09)) third else if (smallRegisters) unit shr 12 else next
                val registers = when (opcode) {
                    in 0x6e..0x72 -> listOf(third and 15, (third shr 4) and 15, (third shr 8) and 15, third shr 12, (unit shr 8) and 15).take(unit shr 12)
                    in 0x74..0x78 -> (third until third + (unit shr 8)).toList()
                    else -> emptyList()
                }
                add(Instruction(opcode, a, b, if (opcode == 0x1b) int(position + 2) else next, registers))
                position += width * 2
            }
        }
    }

    private companion object { const val MAX_DEX_BYTES = 64L * 1024 * 1024 }
}
