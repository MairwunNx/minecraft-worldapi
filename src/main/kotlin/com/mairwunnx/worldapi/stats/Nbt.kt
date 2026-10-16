package com.mairwunnx.worldapi.stats

import java.io.DataInputStream
import java.util.UUID

/**
 * Just enough of the game's NBT format to pick a few tags out of a file and skip the rest. The server
 * bundles no NBT library a plugin may use, so WorldAPI walks the bytes itself.
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
}
