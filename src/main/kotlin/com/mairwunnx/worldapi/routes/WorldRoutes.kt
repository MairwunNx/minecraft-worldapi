package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.bukkit.view
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.Border
import com.mairwunnx.worldapi.model.BorderPatch
import com.mairwunnx.worldapi.model.GameRuleView
import com.mairwunnx.worldapi.model.Position
import com.mairwunnx.worldapi.model.Weather
import com.mairwunnx.worldapi.model.WorldPatch
import com.mairwunnx.worldapi.model.WorldSummary
import com.mairwunnx.worldapi.model.WorldTime
import com.mairwunnx.worldapi.model.WorldView
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.World

val worldRoutes = endpoints("Worlds") {
    get("/worlds", "Every loaded world") {
        val admin = principal.has(Scope.ADMIN)
        main { api.server.worlds.map { WorldSummary(it.name, it.key.asString(), it.environment.name.lowercase(), it.playerCount, if (admin) it.seed.toString() else null) } }
    }

    get("/worlds/{world}", "Time, weather, moon, border, rules, and load of one world") {
        val admin = principal.has(Scope.ADMIN)
        main { worldView(world(), admin) }
    }

    patch<WorldPatch, WorldView>("/worlds/{world}", "Change time, weather, difficulty, spawn, or PvP", Scope.WRITE_WORLD) { patch ->
        val admin = principal.has(Scope.ADMIN)
        main {
            val world = world()
            auditTarget = world.name
            patch.apply(world)
            worldView(world, admin)
        }
    }

    get("/worlds/{world}/gamerules", "Every game rule with its type, value, and default") {
        main { gameRules(world()) }
    }

    patch<Map<String, JsonPrimitive>, List<GameRuleView>>(
        "/worlds/{world}/gamerules", "Set game rules", Scope.WRITE_WORLD,
        description = "The body maps rule names to values: `{ \"keep_inventory\": true, \"players_sleeping_percentage\": 100 }`. All rules are checked before any changes.",
    ) { rules ->
        if (rules.isEmpty()) badRequest("Name at least one rule")
        main {
            val world = world()
            val parsed = rules.map { (name, value) -> rule(name) to value }.map { (rule, value) -> rule to typed(rule, value) }
            parsed.forEach { (rule, value) ->
                @Suppress("UNCHECKED_CAST")
                world.setGameRule(rule as GameRule<Any>, value)
            }
            auditTarget = "${world.name}: " + parsed.joinToString { "${it.first.key.key}=${it.second}" }
            gameRules(world)
        }
    }

    get("/worlds/{world}/border", "World border") {
        main { border(world()) }
    }

    patch<BorderPatch, Border>("/worlds/{world}/border", "Resize or move the world border", Scope.WRITE_WORLD) { patch ->
        main {
            val world = world()
            auditTarget = world.name
            val border = world.worldBorder
            if (patch.centerX != null || patch.centerZ != null) border.setCenter(patch.centerX ?: border.center.x, patch.centerZ ?: border.center.z)
            patch.size?.let { size ->
                if (size < 1 || size > border.maxSize) badRequest("size must be between 1 and ${border.maxSize}")
                val seconds = patch.transitionSeconds ?: 0
                if (seconds > 0) border.changeSize(size, seconds * 20) else border.size = size
            }
            patch.damageAmount?.let { border.damageAmount = it }
            patch.damageBuffer?.let { border.damageBuffer = it }
            patch.warningDistance?.let { border.warningDistance = it }
            patch.warningTimeTicks?.let { border.warningTimeTicks = it }
            border(world)
        }
    }
}

private val moonPhases = listOf("full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous")

fun worldTime(world: World): WorldTime = WorldTime(
    dayTime = world.time,
    fullTime = world.fullTime,
    gameTime = world.gameTime,
    day = world.fullTime / 24_000 + 1,
    // Bukkit refreshes isDayTime on the next tick, so right after a time change it would be stale.
    isDay = world.time % 24_000 !in 13_000 until 23_000,
    moonPhase = moonPhases.getOrElse(world.moonPhase.ordinal) { world.moonPhase.name.lowercase() },
    fixedTime = world.isFixedTime,
)

fun weather(world: World): Weather = Weather(
    state = when {
        // The thunder flag can outlive the rain; without rain there is no storm.
        world.hasStorm() && world.isThundering -> "thunder"
        world.hasStorm() -> "rain"
        else -> "clear"
    },
    raining = world.hasStorm(),
    thundering = world.hasStorm() && world.isThundering,
    weatherDurationTicks = world.weatherDuration,
    thunderDurationTicks = world.thunderDuration,
    clearWeatherDurationTicks = world.clearWeatherDuration,
)

private fun worldView(world: World, admin: Boolean) = WorldView(
    name = world.name,
    key = world.key.asString(),
    environment = world.environment.name.lowercase(),
    seed = if (admin) world.seed.toString() else null,
    time = worldTime(world),
    weather = weather(world),
    difficulty = world.difficulty.name.lowercase(),
    hardcore = world.isHardcore,
    pvp = world.pvp,
    spawn = world.spawnLocation.view(),
    border = border(world),
    gameRules = gameRules(world).associate { it.name to it.value },
    players = world.playerCount,
    loadedChunks = world.chunkCount,
    entities = world.entityCount,
    tileEntities = world.tileEntityCount,
    minHeight = world.minHeight,
    maxHeight = world.maxHeight,
    seaLevel = world.seaLevel,
    viewDistance = world.viewDistance,
    simulationDistance = world.simulationDistance,
)

private fun WorldPatch.apply(world: World) {
    // Validate everything first, so a bad field doesn't leave half the patch applied.
    val difficulty = difficulty?.let { name -> Difficulty.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: badRequest("difficulty must be peaceful, easy, normal, or hard") }
    if (weather != null && weather !in setOf("clear", "rain", "thunder")) badRequest("weather must be clear, rain, or thunder")
    if (dayTime != null && dayTime !in 0..23_999) badRequest("dayTime must be 0–23999; use fullTime or addTime for more")
    listOfNotNull(weatherDurationTicks, thunderDurationTicks, clearWeatherDurationTicks).forEach { if (it < 0) badRequest("Durations can't be negative") }

    dayTime?.let { world.time = it }
    fullTime?.let { world.fullTime = it }
    addTime?.let { world.fullTime = world.fullTime + it }
    when (weather) {
        "clear" -> {
            // The clear timer alone stops the rain only on the next tick; stop it now so the answer is current.
            world.setStorm(false)
            world.isThundering = false
            world.clearWeatherDuration = clearWeatherDurationTicks ?: weatherDurationTicks ?: (12_000..180_000).random()
        }
        "rain" -> {
            world.setStorm(true)
            world.isThundering = false
            world.weatherDuration = weatherDurationTicks ?: (12_000..24_000).random()
        }
        "thunder" -> {
            world.setStorm(true)
            world.isThundering = true
            world.weatherDuration = weatherDurationTicks ?: (3_600..15_600).random()
            world.thunderDuration = thunderDurationTicks ?: world.weatherDuration
        }
        null -> {
            weatherDurationTicks?.let { world.weatherDuration = it }
            thunderDurationTicks?.let { world.thunderDuration = it }
            clearWeatherDurationTicks?.let { world.clearWeatherDuration = it }
        }
    }
    difficulty?.let { world.difficulty = it }
    pvp?.let { world.pvp = it }
    spawn?.let { world.setSpawnLocation(it.x, it.y, it.z, it.angle) }
}

private fun rule(name: String): GameRule<*> =
    GameRule.values().firstOrNull { it.key.key.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
        ?: badRequest("Unknown game rule \"$name\"", "unknown_gamerule")

private fun typed(rule: GameRule<*>, value: JsonPrimitive): Any = when (rule.type) {
    java.lang.Boolean::class.java -> value.booleanOrNull ?: badRequest("${rule.key.key} takes true or false")
    java.lang.Integer::class.java -> value.intOrNull?.takeIf { !value.isString } ?: badRequest("${rule.key.key} takes a whole number")
    else -> badRequest("${rule.key.key} has a type WorldAPI can't set")
}

private fun json(value: Any?): JsonPrimitive = when (value) {
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value?.toString())
}

private fun gameRules(world: World): List<GameRuleView> = GameRule.values()
    .filter { world.isEnabled(it) }
    .map { rule ->
        GameRuleView(
            name = rule.key.key,
            type = if (rule.type == java.lang.Boolean::class.java) "boolean" else "integer",
            value = json(world.getGameRuleValue(rule)),
            default = json(world.getGameRuleDefault(rule)),
        )
    }
    .sortedBy { it.name }

private fun border(world: World) = world.worldBorder.let {
    Border(it.size, Position(it.center.x, 0.0, it.center.z), it.damageAmount, it.damageBuffer, it.warningDistance, it.warningTimeTicks, it.maxSize)
}

