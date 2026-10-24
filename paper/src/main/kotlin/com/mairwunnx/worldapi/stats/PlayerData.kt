package com.mairwunnx.worldapi.stats

import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import org.bukkit.inventory.ItemStack

/**
 * An offline player's inventory and ender chest from their playerdata file. Each item goes through
 * Paper's own item reader, with the file's DataVersion, so old items upgrade as the game would.
 */
object OfflineInventory {
    class Contents(val inventory: Map<Int, ItemStack>, val enderChest: Map<Int, ItemStack>, val selectedSlot: Int)

    // Since 1.21.5 armor and the offhand sit in `equipment`, keyed by slot name.
    private val equipmentSlots = mapOf("feet" to 36, "legs" to 37, "chest" to 38, "head" to 39, "offhand" to 40)

    /** Null when the file is missing or broken. Call on the server thread: items touch registries. */
    @Suppress("UNCHECKED_CAST")
    fun read(file: File): Contents? = runCatching {
        val root = DataInputStream(GZIPInputStream(file.inputStream().buffered())).use(Nbt::readRoot)
        val version = root["DataVersion"] as? Int ?: return null
        fun item(tag: Map<String, Any>): ItemStack? = runCatching {
            ItemStack.deserializeBytes(Nbt.gzip(LinkedHashMap(tag - "Slot") + ("DataVersion" to version)))
        }.getOrNull()?.takeUnless { it.isEmpty }
        fun slots(list: Any?): Map<Int, ItemStack> = (list as? NbtList)?.items.orEmpty().mapNotNull { entry ->
            val tag = entry as Map<String, Any>
            val slot = (tag["Slot"] as? Byte)?.toInt() ?: return@mapNotNull null
            item(tag)?.let { slot to it }
        }.toMap()
        val equipment = (root["equipment"] as? Map<String, Any>).orEmpty().mapNotNull { (name, tag) ->
            equipmentSlots[name]?.let { slot -> item(tag as Map<String, Any>)?.let { slot to it } }
        }.toMap()
        Contents(slots(root["Inventory"]) + equipment, slots(root["EnderItems"]), root["SelectedItemSlot"] as? Int ?: 0)
    }.getOrNull()
}
