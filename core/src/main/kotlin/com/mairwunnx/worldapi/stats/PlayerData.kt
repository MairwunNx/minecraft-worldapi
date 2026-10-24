package com.mairwunnx.worldapi.stats

import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Reads `XpTotal` from vanilla playerdata files. Neither Paper nor the game offers an API for an
 * offline player's experience, and the server bundles no NBT library a plugin may use, so this walks
 * the gzipped NBT itself and skips every tag it doesn't need.
 */
object PlayerData {
    /** Where vanilla keeps playerdata: `<level>/players/data` since 26.1, `<level>/playerdata` before. */
    fun folder(level: File): File? =
        generateSequence(level) { it.parentFile }.take(4)
            .flatMap { sequenceOf(File(it, "players/data"), File(it, "playerdata")) }
            .firstOrNull { it.isDirectory }

    /** The player's `XpTotal`, or null when the file is missing, broken, or has none. */
    fun xpTotal(file: File): Int? = runCatching { read(file) }.getOrNull()

    private fun read(file: File): Int? {
        DataInputStream(GZIPInputStream(file.inputStream().buffered())).use { input ->
            if (input.readByte().toInt() != Nbt.COMPOUND) return null
            input.readUTF()
            while (true) {
                val type = input.readByte().toInt()
                if (type == Nbt.END) return null
                val name = input.readUTF()
                if (name == "XpTotal" && type == Nbt.INT) return input.readInt()
                Nbt.skip(input, type)
            }
        }
    }
}
