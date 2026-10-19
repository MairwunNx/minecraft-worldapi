package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class EntitySummary(
    val uuid: String,
    /** Entity key, such as `minecraft:zombie`. */
    val type: String,
    val name: String? = null,
    val location: LocationView? = null,
    val health: Double? = null,
)

@Serializable
data class EntityView(
    val uuid: String,
    val type: String,
    val name: String? = null,
    val customName: String? = null,
    val customNameVisible: Boolean,
    val location: LocationView? = null,
    val velocity: Position,
    val onGround: Boolean,
    val ticksLived: Int,
    val fireTicks: Int,
    val glowing: Boolean,
    val silent: Boolean,
    val invulnerable: Boolean,
    val gravity: Boolean,
    val persistent: Boolean,
    val tags: List<String>,
    val passengers: List<String>,
    val vehicle: String? = null,
    /** Living entities only from here on. */
    val health: Double? = null,
    val maxHealth: Double? = null,
    val ai: Boolean? = null,
    val target: String? = null,
    val adult: Boolean? = null,
    val tamed: Boolean? = null,
    val owner: PlayerRef? = null,
    /** Worn and held items by slot; a player's are hidden from anonymous clients unless privacy allows inventories. */
    val equipment: Map<String, ItemView>? = null,
    val effects: List<EffectView>? = null,
    /** Why it spawned, such as `natural`, `spawner`, or `breeding`. */
    val spawnReason: String? = null,
    val fromSpawner: Boolean? = null,
    /** Whether the game may despawn it when no player is near. */
    val removeWhenFarAway: Boolean? = null,
    /** UUID of what holds its lead. */
    val leashHolder: String? = null,
    val villager: VillagerView? = null,
    /** A dropped item: the stack, how long until it can be picked up, and who threw it. */
    val item: ItemView? = null,
    val pickupDelay: Int? = null,
    val thrower: String? = null,
)

@Serializable
data class VillagerView(
    /** Such as `minecraft:librarian`. */
    val profession: String,
    /** Biome style, such as `minecraft:plains`. */
    val type: String,
    val level: Int,
    val experience: Int,
    val trades: List<TradeView>,
)

@Serializable
data class TradeView(
    val ingredients: List<ItemView>,
    val result: ItemView,
    val uses: Int,
    val maxUses: Int,
    /** The price change from demand and the player's reputation, as the game applies it. */
    val demand: Int,
    val specialPrice: Int,
)

@Serializable
data class EntityCensus(
    val world: String,
    /** Entities counted: in the area asked for, or loaded in the whole world. */
    val total: Int,
    /** Count per type, most first; the rest add up in `otherTypes`. */
    val byType: Map<String, Int>,
    val otherTypes: Int,
    /** Item entities and the items they hold. */
    val droppedItems: Int,
    val droppedItemCount: Int,
    val villagers: Int,
    val named: Int,
    val tamed: Int,
    val players: Int,
    /** The busiest chunks, where lag usually comes from. */
    val topChunks: List<ChunkLoad>,
)

@Serializable
data class ChunkLoad(
    val x: Int,
    val z: Int,
    val entities: Int,
    val tileEntities: Int,
    val hoppers: Int,
    /** The three most common entity types there. */
    val top: Map<String, Int>,
)

@Serializable
data class EntityPatch(
    val customName: String? = null,
    val format: TextFormat = TextFormat.PLAIN,
    val customNameVisible: Boolean? = null,
    val glowing: Boolean? = null,
    val silent: Boolean? = null,
    val invulnerable: Boolean? = null,
    val gravity: Boolean? = null,
    val persistent: Boolean? = null,
    val ai: Boolean? = null,
    val health: Double? = null,
    val fireTicks: Int? = null,
    /** Teleports the entity. */
    val location: LocationView? = null,
    val velocity: Position? = null,
    val addTags: List<String> = emptyList(),
    val removeTags: List<String> = emptyList(),
)

@Serializable
data class SpawnRequest(
    /** Entity key, such as `minecraft:cow`. */
    val type: String,
    val location: LocationView,
    val count: Int = 1,
    val customName: String? = null,
    val format: TextFormat = TextFormat.PLAIN,
    val ai: Boolean? = null,
    val persistent: Boolean? = null,
    val glowing: Boolean? = null,
)

@Serializable
data class Spawned(val uuids: List<String>)

@Serializable
data class RemovedEntities(
    /** Entities that fit the filter, leaving out players, and named mobs and tamed pets unless included. */
    val matched: Int,
    val removed: Int,
    /** Matching entities still there, past `max` or because it was a dry run. */
    val remaining: Int,
    val dryRun: Boolean,
    /** What was removed, or would be on a dry run, by entity key. */
    val byType: Map<String, Int>,
)

@Serializable
data class EffectView(
    /** Effect key, such as `minecraft:speed`. */
    val type: String,
    /** 0 is level I. */
    val amplifier: Int,
    /** -1 for an infinite effect. */
    val durationTicks: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean,
)

@Serializable
data class EffectRequest(
    val type: String,
    /** Leave out for an infinite effect. */
    val durationSeconds: Int? = null,
    val amplifier: Int = 0,
    val ambient: Boolean = false,
    val particles: Boolean = true,
    val icon: Boolean = true,
)

@Serializable
data class ExplosionRequest(
    val location: LocationView,
    /** TNT is 4. */
    val power: Float = 4f,
    val fire: Boolean = false,
    val breakBlocks: Boolean = false,
)

@Serializable
data class LightningRequest(
    val location: LocationView,
    /** Only the flash and the sound, no damage or fire. */
    val effectOnly: Boolean = false,
)

@Serializable
data class SoundRequest(
    /** Sound key, such as `minecraft:entity.ender_dragon.growl`. */
    val sound: String,
    val location: LocationView,
    val volume: Float = 1f,
    val pitch: Float = 1f,
    /** `master`, `music`, `record`, `weather`, `block`, `hostile`, `neutral`, `player`, `ambient`, `voice`. */
    val category: String = "master",
)

@Serializable
data class ParticleRequest(
    /** Particle key, such as `minecraft:heart`. Particles that need extra data aren't supported. */
    val particle: String,
    val location: LocationView,
    val count: Int = 10,
    val offset: Position = Position(0.5, 0.5, 0.5),
    val speed: Double = 0.0,
)
