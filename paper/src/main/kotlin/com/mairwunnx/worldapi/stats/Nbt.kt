package com.mairwunnx.worldapi.stats

import java.io.DataInputStream
import java.util.UUID

/**
 * The game's NBT format: skip tags, pick a few out of a file, or read and write whole ones. The
 * server bundles no NBT library a plugin may use, so WorldAPI walks the bytes itself.
 */
object Nbt {
    const val END = 0
    const val INT = 3
    const val STRING = 8
    const val LIST = 9
    const val COMPOUND = 10
    const val INT_ARRAY = 11
    private const val MAX_DEPTH = 512

    fun skip(input: DataInputStream, type: Int, depth: Int = 0) {
        require(depth < MAX_DEPTH) { "NBT nested too deep" }
        when (type) {
            1 -> input.skipNBytes(1)
            2 -> input.skipNBytes(2)
            3, 5 -> input.skipNBytes(4)
            4, 6 -> input.skipNBytes(8)
            7 -> input.skipNBytes(length(input).toLong())
            STRING -> input.skipNBytes(input.readUnsignedShort().toLong())
            LIST -> {
                val element = input.readByte().toInt()
                repeat(length(input)) { skip(input, element, depth + 1) }
            }
            COMPOUND -> while (true) {
                val inner = input.readByte().toInt()
                if (inner == END) break
                input.skipNBytes(input.readUnsignedShort().toLong())
                skip(input, inner, depth + 1)
            }
            INT_ARRAY -> input.skipNBytes(length(input) * 4L)
            12 -> input.skipNBytes(length(input) * 8L)
            else -> error("Unknown NBT tag $type")
        }
    }

    fun length(input: DataInputStream): Int = input.readInt().also { require(it >= 0) { "Negative NBT length" } }

    /** An int array of four, as the game stores UUIDs; null for any other length, which it then skips. */
    fun uuid(input: DataInputStream): UUID? {
        val size = length(input)
        if (size != 4) {
            input.skipNBytes(size * 4L)
            return null
        }
        val (a, b, c, d) = IntArray(4) { input.readInt() }
        return UUID(a.toLong() shl 32 or (b.toLong() and 0xFFFFFFFFL), c.toLong() shl 32 or (d.toLong() and 0xFFFFFFFFL))
    }

    /**
     * Reads a whole tag into plain values: numbers, strings, `ByteArray`, `IntArray`, `LongArray`,
     * [NbtList] for lists (they keep their element type), and ordered maps for compounds.
     */
    fun read(input: DataInputStream, type: Int, depth: Int = 0): Any {
        require(depth < MAX_DEPTH) { "NBT nested too deep" }
        return when (type) {
            1 -> input.readByte()
            2 -> input.readShort()
            INT -> input.readInt()
            4 -> input.readLong()
            5 -> input.readFloat()
            6 -> input.readDouble()
            7 -> ByteArray(length(input)).also(input::readFully)
            STRING -> input.readUTF()
            LIST -> {
                val element = input.readByte().toInt()
                NbtList(element, List(length(input)) { read(input, element, depth + 1) })
            }
            COMPOUND -> linkedMapOf<String, Any>().also { map ->
                while (true) {
                    val inner = input.readByte().toInt()
                    if (inner == END) break
                    map[input.readUTF()] = read(input, inner, depth + 1)
                }
            }
            INT_ARRAY -> IntArray(length(input)) { input.readInt() }
            12 -> LongArray(length(input)) { input.readLong() }
            else -> error("Unknown NBT tag $type")
        }
    }

    /** A root compound from a gzipped file, as playerdata is stored. */
    @Suppress("UNCHECKED_CAST")
    fun readRoot(input: DataInputStream): Map<String, Any> {
        require(input.readByte().toInt() == COMPOUND) { "The file doesn't start with a compound" }
        input.readUTF()
        return read(input, COMPOUND) as Map<String, Any>
    }

    /** Writes [root] as a gzipped, unnamed root compound: what Paper's `ItemStack.deserializeBytes` reads. */
    fun gzip(root: Map<String, Any>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(java.util.zip.GZIPOutputStream(bytes)).use { output ->
            output.writeByte(COMPOUND)
            output.writeUTF("")
            write(output, root)
        }
        return bytes.toByteArray()
    }

    private fun typeOf(value: Any): Int = when (value) {
        is Byte -> 1
        is Short -> 2
        is Int -> INT
        is Long -> 4
        is Float -> 5
        is Double -> 6
        is ByteArray -> 7
        is String -> STRING
        is NbtList -> LIST
        is Map<*, *> -> COMPOUND
        is IntArray -> INT_ARRAY
        is LongArray -> 12
        else -> error("Not an NBT value: ${value::class}")
    }

    private fun write(output: java.io.DataOutputStream, value: Any) {
        when (value) {
            is Byte -> output.writeByte(value.toInt())
            is Short -> output.writeShort(value.toInt())
            is Int -> output.writeInt(value)
            is Long -> output.writeLong(value)
            is Float -> output.writeFloat(value)
            is Double -> output.writeDouble(value)
            is ByteArray -> { output.writeInt(value.size); output.write(value) }
            is String -> output.writeUTF(value)
            is NbtList -> {
                output.writeByte(if (value.items.isEmpty()) END else value.type)
                output.writeInt(value.items.size)
                value.items.forEach { write(output, it) }
            }
            is Map<*, *> -> {
                value.forEach { (key, inner) ->
                    output.writeByte(typeOf(inner!!))
                    output.writeUTF(key as String)
                    write(output, inner)
                }
                output.writeByte(END)
            }
            is IntArray -> { output.writeInt(value.size); value.forEach(output::writeInt) }
            is LongArray -> { output.writeInt(value.size); value.forEach(output::writeLong) }
            else -> error("Not an NBT value: ${value::class}")
        }
    }
}

/** An NBT list keeps its element type, so an empty one writes back the same. */
data class NbtList(val type: Int, val items: List<Any>)
