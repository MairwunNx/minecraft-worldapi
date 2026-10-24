package com.mairwunnx.worldapi.stats

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Tamed animals in the entity files of a world, which hold the mobs of chunks nobody has loaded
 * lately: region files (`entities/r.x.z.mca`), each with up to 1024 chunks of compressed NBT. A
 * chunk the server keeps elsewhere (oversized) or compresses with LZ4 is skipped and counted.
 */
object EntityFiles {
    data class Pet(val entity: UUID, val owner: UUID)

    /** Mobs a player tames, which keep their owner in `Owner`; arrows and items keep one too, so only these count. */
    val TAMEABLE = setOf(
        "wolf", "cat", "parrot", "horse", "donkey", "mule", "llama", "trader_llama", "camel", "camel_husk",
        "skeleton_horse", "zombie_horse", "nautilus", "zombie_nautilus",
    ).mapTo(HashSet()) { "minecraft:$it" }

    /** Every `entities` folder with region files under [roots], each once. */
    fun folders(roots: Collection<File>): Set<File> = roots.flatMap { root ->
        root.walkTopDown().maxDepth(5).filter { it.isDirectory && it.name == "entities" && it.listFiles { f -> f.name.endsWith(".mca") }?.isNotEmpty() == true }.toList()
    }.mapTo(HashSet()) { it.canonicalFile }

    /** The pets in one folder's region files; [skipped] gets the chunks it couldn't read. */
    fun pets(folder: File, skipped: (Int) -> Unit = {}): List<Pet> =
        (folder.listFiles { f -> f.name.endsWith(".mca") } ?: emptyArray()).flatMap { file -> runCatching { region(file, skipped) }.getOrElse { emptyList() } }

    private fun region(file: File, skipped: (Int) -> Unit): List<Pet> {
        val pets = ArrayList<Pet>()
        RandomAccessFile(file, "r").use { region ->
            if (region.length() < SECTOR) return pets
            val header = ByteArray(SECTOR)
            region.readFully(header)
            for (i in 0 until 1024) {
                val offset = (header[i * 4].toInt() and 0xFF shl 16) or (header[i * 4 + 1].toInt() and 0xFF shl 8) or (header[i * 4 + 2].toInt() and 0xFF)
                if (offset == 0 || header[i * 4 + 3].toInt() == 0) continue
                region.seek(offset * SECTOR.toLong())
                val length = region.readInt()
                val type = region.readByte().toInt()
                if (length <= 1 || type and 0x80 != 0 || offset * SECTOR.toLong() + length > region.length()) {
                    skipped(1)
                    continue
                }
                val data = ByteArray(length - 1)
                region.readFully(data)
                val raw: InputStream = ByteArrayInputStream(data)
                val stream = when (type) {
                    1 -> GZIPInputStream(raw)
                    2 -> InflaterInputStream(raw)
                    3 -> raw
                    else -> {
                        skipped(1)
                        continue
                    }
                }
                runCatching { DataInputStream(stream.buffered()).use { chunk(it, pets) } }.onFailure { skipped(1) }
            }
        }
        return pets
    }

    /** A chunk's root compound: its `Entities` list, and every passenger inside them. */
    private fun chunk(input: DataInputStream, pets: MutableList<Pet>) {
        if (input.readByte().toInt() != Nbt.COMPOUND) return
        input.skipNBytes(input.readUnsignedShort().toLong())
        while (true) {
            val type = input.readByte().toInt()
            if (type == Nbt.END) return
            val name = input.readUTF()
            if (name == "Entities" && type == Nbt.LIST) entities(input, pets, 0) else Nbt.skip(input, type)
        }
    }

    private fun entities(input: DataInputStream, pets: MutableList<Pet>, depth: Int) {
        val element = input.readByte().toInt()
        val size = Nbt.length(input)
        if (element != Nbt.COMPOUND) return repeat(size) { Nbt.skip(input, element, depth + 1) }
        repeat(size) { entity(input, pets, depth + 1) }
    }

    private fun entity(input: DataInputStream, pets: MutableList<Pet>, depth: Int) {
        var id: String? = null
        var uuid: UUID? = null
        var owner: UUID? = null
        while (true) {
            val type = input.readByte().toInt()
            if (type == Nbt.END) break
            when (val name = input.readUTF()) {
                "id" -> if (type == Nbt.STRING) id = input.readUTF() else Nbt.skip(input, type, depth)
                "UUID", "Owner" -> if (type == Nbt.INT_ARRAY) Nbt.uuid(input).let { if (name == "UUID") uuid = it else owner = it } else Nbt.skip(input, type, depth)
                // A pet riding something, or something riding a pet, is saved inside it.
                "Passengers" -> if (type == Nbt.LIST) entities(input, pets, depth) else Nbt.skip(input, type, depth)
                else -> Nbt.skip(input, type, depth)
            }
        }
        if (id in TAMEABLE && uuid != null && owner != null) pets += Pet(uuid!!, owner!!)
    }

    private const val SECTOR = 4096
}
