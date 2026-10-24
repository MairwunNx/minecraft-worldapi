package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.model.LocationView
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * What an offline player's playerdata file holds, as Paper's `OfflinePlayer` reads it: where they stand, their
 * respawn point, their items, and when they first and last played. The game's data fixer brings an old file up to
 * date first, so old items read as the game would load them.
 */
class PlayerFile(private val server: MinecraftServer, val tag: CompoundTag, private val path: Path) {
    private val ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE)

    fun location(): LocationView? {
        val pos = tag.getListOrEmpty("Pos").takeIf { it.size == 3 } ?: return null
        val rotation = tag.getListOrEmpty("Rotation")
        val level = dimension(tag.getStringOr("Dimension", "minecraft:overworld")) ?: return null
        return LocationView(level, pos.getDoubleOr(0, 0.0).short(), pos.getDoubleOr(1, 0.0).short(), pos.getDoubleOr(2, 0.0).short(), rotation.getFloatOr(0, 0f), rotation.getFloatOr(1, 0f))
    }

    /** The bed or anchor the player set, as `respawn` saves it. */
    fun respawn(): LocationView? {
        val respawn = tag.getCompound("respawn").orElse(null) ?: return null
        val pos = respawn.getIntArray("pos").orElse(null)?.takeIf { it.size == 3 } ?: return null
        val level = dimension(respawn.getStringOr("dimension", "minecraft:overworld")) ?: return null
        return LocationView(level, pos[0].toDouble(), pos[1].toDouble(), pos[2].toDouble())
    }

    private fun dimension(id: String): String? {
        val key = ResourceKey.create(Registries.DIMENSION, Identifier.tryParse(id) ?: return null)
        return server.getLevel(key)?.worldName
    }

    private fun Double.short() = Math.round(this * 100) / 100.0

    /** Slots as Paper's offline inventory numbers them: 0–35 the main inventory, 36–39 armor, 40 the offhand. */
    fun inventory(): Map<Int, ItemStack> = slots("Inventory") + equipment()

    fun enderChest(): Map<Int, ItemStack> = slots("EnderItems")

    val selectedSlot: Int get() = tag.getIntOr("SelectedItemSlot", 0)

    private fun slots(name: String): Map<Int, ItemStack> = tag.getListOrEmpty(name).mapNotNull { entry ->
        val item = entry as? CompoundTag ?: return@mapNotNull null
        val slot = item.getInt("Slot").orElse(null)?.let { it and 0xFF } ?: return@mapNotNull null
        stack(item)?.let { slot to it }
    }.toMap()

    // Since 1.21.5 armor and the offhand sit in `equipment`, keyed by slot name.
    private fun equipment(): Map<Int, ItemStack> {
        val equipment = tag.getCompoundOrEmpty("equipment")
        return mapOf("feet" to 36, "legs" to 37, "chest" to 38, "head" to 39, "offhand" to 40).mapNotNull { (name, slot) ->
            equipment.getCompound(name).orElse(null)?.let(::stack)?.let { slot to it }
        }.toMap()
    }

    private fun stack(tag: CompoundTag): ItemStack? = ItemStack.CODEC.parse(ops, tag).result().orElse(null)?.takeUnless { it.isEmpty }

    private val attributes by lazy { runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull() }

    /** When the file appeared: the game keeps no first-join time of its own. */
    val firstPlayed: Long get() = attributes?.creationTime()?.toMillis() ?: 0

    /** When the game last saved the player, which it does as they leave. */
    val lastSaved: Long get() = attributes?.lastModifiedTime()?.toMillis() ?: 0

    companion object {
        fun path(server: MinecraftServer, uuid: UUID): Path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("$uuid.dat")

        /** Off or on the server thread; null when there is no file or it doesn't read. */
        fun read(server: MinecraftServer, uuid: UUID): PlayerFile? {
            val path = path(server, uuid)
            if (!Files.isRegularFile(path)) return null
            return runCatching {
                val raw = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
                val fixed = DataFixTypes.PLAYER.updateToCurrentVersion(server.fixerUpper, raw, NbtUtils.getDataVersion(raw, -1))
                PlayerFile(server, fixed, path)
            }.getOrNull()
        }
    }
}
