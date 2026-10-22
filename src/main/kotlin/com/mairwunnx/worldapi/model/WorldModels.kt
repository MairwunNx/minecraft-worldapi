package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class WorldSummary(
    val name: String,
    /** Dimension key, such as `minecraft:overworld`. */
    val key: String,
    val environment: String,
    val players: Int,
    /** Only for tokens with the admin scope. A string, because JavaScript can't hold a 64-bit number exactly. */
    val seed: String? = null,
)

@Serializable
data class WorldView(
    val name: String,
    val key: String,
    val environment: String,
    /** Only for tokens with the admin scope; a string, like in the world list. */
    val seed: String? = null,
    val time: WorldTime,
    val weather: Weather,
    val difficulty: String,
    val hardcore: Boolean,
    val pvp: Boolean,
    val spawn: LocationView,
    val border: Border,
    val gameRules: Map<String, JsonPrimitive>,
    val players: Int,
    val loadedChunks: Int,
    val entities: Int,
    val tileEntities: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val seaLevel: Int,
    val viewDistance: Int,
    val simulationDistance: Int,
)

@Serializable
data class WorldTime(
    /** Ticks into the current day, 0–23999; 0 is sunrise, 6000 noon, 18000 midnight. */
    val dayTime: Long,
    /** Ticks of daylight since the world began, including skipped nights. */
    val fullTime: Long,
    /** Ticks the world has actually run. */
    val gameTime: Long,
    /** Day number, starting at 1. */
    val day: Long,
    val isDay: Boolean,
    /** Such as `full_moon` or `waxing_crescent`. */
    val moonPhase: String,
    val fixedTime: Boolean,
    /** Ticks until night falls at dayTime 13000; 0 while it's night, absent when the clock stands still. */
    val ticksUntilNight: Long? = null,
    /** Whether a bed works right now: at night by the vanilla clock, earlier in rain, any time in a thunderstorm. */
    val canSleep: Boolean = false,
)

@Serializable
data class Weather(
    /** `clear`, `rain`, or `thunder`. */
    val state: String,
    val raining: Boolean,
    val thundering: Boolean,
    /** Ticks until the rain state flips. */
    val weatherDurationTicks: Int,
    val thunderDurationTicks: Int,
    val clearWeatherDurationTicks: Int,
)

@Serializable
data class WorldPatch(
    /** Ticks into the day, 0–23999. */
    val dayTime: Long? = null,
    val fullTime: Long? = null,
    /** Ticks to move the clock forward. */
    val addTime: Long? = null,
    /** `clear`, `rain`, or `thunder`. */
    val weather: String? = null,
    /** How long the new weather lasts; random when left out. */
    val weatherDurationTicks: Int? = null,
    val thunderDurationTicks: Int? = null,
    val clearWeatherDurationTicks: Int? = null,
    /** `peaceful`, `easy`, `normal`, or `hard`. */
    val difficulty: String? = null,
    val pvp: Boolean? = null,
    val spawn: SpawnPatch? = null,
)

@Serializable
data class SpawnPatch(val x: Int, val y: Int, val z: Int, val angle: Float = 0f)

/** Where players appear on their first join and when they respawn without a bed or an anchor. */
@Serializable
data class ServerSpawn(
    /** The world players respawn in: its name, such as `world`. */
    val world: String,
    /** Its dimension key, such as `minecraft:overworld`. */
    val key: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Float,
    val pitch: Float,
    /** The `respawn_radius` game rule of that world: players land at a random spot this many blocks around the point. */
    val respawnRadius: Int,
    /** `spawn-protection` from server.properties: blocks around the point only operators may change. */
    val spawnProtection: Int,
)

/** Every field is optional; `x`, `y`, and `z` come together. A world alone moves the respawn there, to that world's own spawn. */
@Serializable
data class ServerSpawnPatch(
    /** A world name or dimension key; the respawn world now when left out. */
    val world: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val z: Int? = null,
    /** Which way players face, -180–180; 0 is south. */
    val yaw: Float? = null,
    /** -90 (up) to 90 (down). */
    val pitch: Float? = null,
    val respawnRadius: Int? = null,
)

@Serializable
data class GameRuleView(
    /** Rule key, such as `keep_inventory`. */
    val name: String,
    /** `boolean` or `integer`. */
    val type: String,
    val value: JsonPrimitive,
    val default: JsonPrimitive,
)

@Serializable
data class Border(
    val size: Double,
    val center: Position,
    val damageAmount: Double,
    val damageBuffer: Double,
    val warningDistance: Int,
    val warningTimeTicks: Int,
    val maxSize: Double,
)

@Serializable
data class BorderPatch(
    val size: Double? = null,
    /** Grow or shrink to the new size over this many seconds instead of at once. */
    val transitionSeconds: Long? = null,
    val centerX: Double? = null,
    val centerZ: Double? = null,
    val damageAmount: Double? = null,
    val damageBuffer: Double? = null,
    val warningDistance: Int? = null,
    val warningTimeTicks: Int? = null,
)

@Serializable
data class WorldStats(
    val world: String,
    /** Chunks that exist in the region files: everything ever generated. */
    val generatedChunks: Long,
    /** generatedChunks as land area, in square kilometers. */
    val exploredKm2: Double,
    val regionFiles: Int,
    val sizeOnDiskBytes: Long,
    val at: String,
)

@Serializable
data class LocateResult(
    val world: String,
    /** `block`, `biome`, or `structure`. */
    val kind: String,
    /** The center searched from. */
    val x: Int,
    val y: Int? = null,
    val z: Int,
    /** Nearest first. */
    val found: List<Found>,
    /** Blocks only: how many matched in all, of which `found` holds the nearest. */
    val matched: Int? = null,
    /** Blocks only: loaded chunks searched, and chunks in range that weren't loaded and so weren't searched. */
    val scannedChunks: Int? = null,
    val unloadedChunks: Int? = null,
)

@Serializable
data class Found(
    /** Block, biome, or structure key, such as `minecraft:diamond_ore`. */
    val type: String,
    val x: Int,
    val y: Int,
    val z: Int,
    /** Blocks: straight-line distance from the center; biomes and structures: horizontal. */
    val distance: Double,
)
