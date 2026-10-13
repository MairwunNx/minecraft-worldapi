package com.mairwunnx.worldapi.stats

import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Reads `XpTotal` from vanilla playerdata files. Paper offers no API for an offline player's
 * experience, and the server bundles no NBT library a plugin may use, so this walks the gzipped
 * NBT itself and skips every tag it doesn't need.
 */
object PlayerData {
    private const val END = 0
    private const val INT = 3
    private const val COMPOUND = 10
    private const val MAX_DEPTH = 512

    /** Where vanilla keeps playerdata: `<level>/players/data` since 26.1, `<level>/playerdata` before. */
    fun folder(level: File): File? =
        generateSequence(level) { it.parentFile }.take(4)
            .flatMap { sequenceOf(File(it, "players/data"), File(it, "playerdata")) }
            .firstOrNull { it.isDirectory }

    /** The player's `XpTotal`, or null when the file is missing, broken, or has none. */
    fun xpTotal(file: File): Int? = runCatching { read(file) }.getOrNull()

    private fun read(file: File): Int? {
        DataInputStream(GZIPInputStream(file.inputStream().buffered())).use { input ->
            if (input.readByte().toInt() != COMPOUND) return null
            input.readUTF()
            while (true) {
                val type = input.readByte().toInt()
                if (type == END) return null
                val name = input.readUTF()
                if (name == "XpTotal" && type == INT) return input.readInt()
                skip(input, type, 0)
            }
        }
    }

    private fun skip(input: DataInputStream, type: Int, depth: Int) {
        require(depth < MAX_DEPTH) { "NBT nested too deep" }
        when (type) {
            1 -> input.skipNBytes(1)
            2 -> input.skipNBytes(2)
            3, 5 -> input.skipNBytes(4)
            4, 6 -> input.skipNBytes(8)
            7 -> input.skipNBytes(length(input).toLong())
            8 -> input.skipNBytes(input.readUnsignedShort().toLong())
            9 -> {
                val element = input.readByte().toInt()
                repeat(length(input)) { skip(input, element, depth + 1) }
            }
            COMPOUND -> while (true) {
                val inner = input.readByte().toInt()
                if (inner == END) break
                input.skipNBytes(input.readUnsignedShort().toLong())
                skip(input, inner, depth + 1)
            }
            11 -> input.skipNBytes(length(input) * 4L)
            12 -> input.skipNBytes(length(input) * 8L)
            else -> error("Unknown NBT tag $type")
        }
    }

    private fun length(input: DataInputStream): Int = input.readInt().also { require(it >= 0) { "Negative NBT length" } }
}
