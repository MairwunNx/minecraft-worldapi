package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class BlockView(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    /** Block key, such as `minecraft:oak_log`. */
    val type: String,
    /** Full block state, such as `minecraft:oak_log[axis=y]`. */
    val data: String,
    val light: Light,
    val biome: String,
    val solid: Boolean,
    val liquid: Boolean,
    val passable: Boolean,
    /** Redstone power reaching the block, 0–15. */
    val power: Int,
    val tileEntity: TileEntityView? = null,
)

@Serializable
data class Light(val sky: Int, val block: Int, val total: Int)

/** Extra state of blocks like chests, signs, and spawners. */
@Serializable
data class TileEntityView(
    /** Paper's name for the block state, such as `Chest` or `Sign`. */
    val kind: String,
    val customName: String? = null,
    /** Container contents; hidden from anonymous clients unless privacy allows inventories. */
    val items: List<ItemView>? = null,
    val size: Int? = null,
    val frontLines: List<String>? = null,
    val backLines: List<String>? = null,
    val spawnedType: String? = null,
    val owner: String? = null,
    val record: String? = null,
)

@Serializable
data class BlockSet(
    /** A block key or a full state: `stone`, `minecraft:oak_stairs[facing=east]`. */
    val block: String,
    /** Update neighbors, so sand falls and water flows. Off by default. */
    val physics: Boolean = false,
)

@Serializable
data class FillRequest(
    val from: BlockPosition,
    val to: BlockPosition,
    val block: String,
    /** `replace` sets every block; `keep` only fills air; `replace_type` only changes blocks of the `replace` type. */
    val mode: String = "replace",
    /** For `replace_type`: the block key to replace. */
    val replace: String? = null,
    val physics: Boolean = false,
)

@Serializable
data class FillResult(val changed: Int, val volume: Int)

@Serializable
data class BiomeView(
    val biome: String,
    val temperature: Double,
    val humidity: Double,
)

@Serializable
data class HighestBlock(val x: Int, val y: Int, val z: Int, val type: String)

@Serializable
data class ChunkSummary(
    val x: Int,
    val z: Int,
    val entities: Int,
    val tileEntities: Int,
    val forceLoaded: Boolean,
)

@Serializable
data class ChunkView(
    val x: Int,
    val z: Int,
    val loaded: Boolean,
    val generated: Boolean,
    val forceLoaded: Boolean,
    /** Ticks players have spent in the chunk; it makes local difficulty rise. */
    val inhabitedTimeTicks: Long? = null,
    val slimeChunk: Boolean? = null,
    /** Entity key to count. */
    val entities: Map<String, Int> = emptyMap(),
    val tileEntities: Map<String, Int> = emptyMap(),
)

@Serializable
data class ChunkPatch(val forceLoaded: Boolean)

@Serializable
data class ContainerSummary(
    /** Block key, such as `minecraft:chest`. */
    val type: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val distance: Double,
    val customName: String? = null,
    val size: Int,
    val usedSlots: Int,
    /** Item key to count: the items searched for, or else the five most plentiful. */
    val items: Map<String, Int>,
    /** Locked with a key item, as /data can set. */
    val locked: Boolean,
)
