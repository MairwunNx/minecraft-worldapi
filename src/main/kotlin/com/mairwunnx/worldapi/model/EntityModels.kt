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
    val equipment: Map<String, ItemView>? = null,
    val effects: List<EffectView>? = null,
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
