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
data class StructureResult(
    /** Structure key, such as `minecraft:stronghold`. */
    val structure: String,
    val location: BlockPosition,
    val distance: Double,
)
