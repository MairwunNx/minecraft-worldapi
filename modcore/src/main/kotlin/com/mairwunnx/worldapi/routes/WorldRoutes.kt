package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.game.blockEntityCount
import com.mairwunnx.worldapi.game.entityList
import com.mairwunnx.worldapi.game.findWorld
import com.mairwunnx.worldapi.game.loadedChunks
import com.mairwunnx.worldapi.game.world
import com.mairwunnx.worldapi.game.worldKey
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.game.worlds
import com.mairwunnx.worldapi.model.Border
import com.mairwunnx.worldapi.model.BorderPatch
import com.mairwunnx.worldapi.model.GameRuleView
import com.mairwunnx.worldapi.model.LocationView
import com.mairwunnx.worldapi.model.Position
import com.mairwunnx.worldapi.model.ServerSpawn
import com.mairwunnx.worldapi.model.ServerSpawnPatch
import com.mairwunnx.worldapi.model.Weather
import com.mairwunnx.worldapi.model.WorldPatch
import com.mairwunnx.worldapi.model.WorldSummary
import com.mairwunnx.worldapi.model.WorldTime
import com.mairwunnx.worldapi.model.WorldView
import com.mairwunnx.worldapi.stats.RegionStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Difficulty
import net.minecraft.world.level.Level
import net.minecraft.world.level.border.WorldBorder
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.gamerules.GameRule
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.storage.LevelResource
import kotlin.math.round

val worldRoutes = endpoints("Worlds", Scope.SERVER_READ) {
    get("/worlds", "Every loaded world") {
        val admin = principal.has(Scope.SERVER_CONTROL)
        main { api.server.worlds.map { WorldSummary(it.worldName, it.worldKey, environment(it), it.players().size, if (admin) it.seed.toString() else null) } }
    }

    get("/worlds/{world}", "Time, weather, moon, border, rules, and load of one world") {
        val admin = principal.has(Scope.SERVER_CONTROL)
        main { worldView(world(), admin) }
    }

    patch<WorldPatch, WorldView>("/worlds/{world}", "Change time, weather, difficulty, spawn, or PvP", Scope.WORLD_WRITE) { patch ->
        val admin = principal.has(Scope.SERVER_CONTROL)
        main {
            val world = world()
            auditTarget = world.worldName
            patch.apply(world)
            worldView(world, admin)
        }
    }

    get("/spawn", "Where players join for the first time and respawn without a bed") {
        main { serverSpawn(api.server) }
    }

    patch<ServerSpawnPatch, ServerSpawn>(
        "/spawn", "Move the spawn point, change its world, or its respawn radius", Scope.WORLD_WRITE,
        description = "`x`, `y`, and `z` come together. `world` alone moves the respawn to that world's own spawn; with coordinates, to that spot in it.",
    ) { patch ->
        main {
            val server = api.server
            val respawn = server.respawnWorld
            val world = patch.world?.let { server.findWorld(it) ?: notFound("world_not_found", "No world \"$it\"") } ?: respawn
            checkSpawn(patch, world.minY, world.maxHeight)
            val now = if (world == respawn) server.respawnData else LevelData.RespawnData.of(world.dimension(), world.levelData.respawnData.pos(), 0f, 0f)
            val pos = BlockPos(patch.x ?: now.pos().x, patch.y ?: now.pos().y, patch.z ?: now.pos().z)
            server.respawnData = LevelData.RespawnData.of(world.dimension(), pos, patch.yaw ?: now.yaw(), patch.pitch ?: now.pitch())
            patch.respawnRadius?.let { world.gameRules.set(GameRules.RESPAWN_RADIUS, it, server) }
            serverSpawn(server).also { auditTarget = "${it.world} ${it.x} ${it.y} ${it.z}" }
        }
    }

    get("/worlds/{world}/gamerules", "Every game rule with its type, value, and default") {
        main { gameRules(world()) }
    }

    patch<Map<String, JsonPrimitive>, List<GameRuleView>>(
        "/worlds/{world}/gamerules", "Set game rules", Scope.WORLD_WRITE,
        description = "The body maps rule names to values: `{ \"keep_inventory\": true, \"players_sleeping_percentage\": 100 }`. All rules are checked before any changes.",
    ) { rules ->
        if (rules.isEmpty()) badRequest("Name at least one rule")
        main {
            val world = world()
            val parsed = rules.map { (name, value) -> rule(world, name) to value }.map { (rule, value) -> rule to typed(rule, value) }
            parsed.forEach { (rule, value) ->
                @Suppress("UNCHECKED_CAST")
                world.gameRules.set(rule as GameRule<Any>, value, api.server)
            }
            auditTarget = "${world.worldName}: " + parsed.joinToString { "${ruleName(it.first)}=${it.second}" }
            gameRules(world)
        }
    }

    get("/worlds/{world}/border", "World border") {
        main { border(world()) }
    }

    patch<BorderPatch, Border>("/worlds/{world}/border", "Resize or move the world border", Scope.WORLD_WRITE) { patch ->
        main {
            val world = world()
            auditTarget = world.worldName
            val border = world.worldBorder
            if (patch.centerX != null || patch.centerZ != null) border.setCenter(patch.centerX ?: border.centerX, patch.centerZ ?: border.centerZ)
            patch.size?.let { size ->
                if (size < 1 || size > WorldBorder.MAX_SIZE) badRequest("size must be between 1 and ${WorldBorder.MAX_SIZE}")
                val seconds = patch.transitionSeconds ?: 0
                if (seconds > 0) border.lerpSizeBetween(border.size, size, seconds * 20, world.gameTime) else border.size = size
            }
            patch.damageAmount?.let { border.damagePerBlock = it }
            patch.damageBuffer?.let { border.safeZone = it }
            patch.warningDistance?.let { border.warningBlocks = it }
            patch.warningTimeTicks?.let { border.warningTime = it }
            border(world)
        }
    }

    get("/worlds/{world}/stats", "Explored area, region files, and size on disk") {
        val (name, region) = main { world().let { it.worldName to it.folder().resolve("region").toFile().takeIf { folder -> folder.isDirectory } } }
        withContext(Dispatchers.IO) { RegionStats.of(name, region) }
    }
}

/** A dimension's folder in the save: `dimensions/minecraft/overworld` and the like since 26.1. */
internal fun ServerLevel.folder() = DimensionType.getStorageFolder(dimension(), server.getWorldPath(LevelResource.ROOT))

/** Paper's names for a world's environment. */
internal fun environment(level: ServerLevel): String = when (level.dimension()) {
    Level.OVERWORLD -> "normal"
    Level.NETHER -> "nether"
    Level.END -> "the_end"
    else -> "custom"
}

/** The top of the world, exclusive, as Paper counts it. */
internal val ServerLevel.maxHeight: Int get() = minY + height

/** The world players respawn in, as the server's respawn data names it. */
private val MinecraftServer.respawnWorld: ServerLevel get() = getLevel(respawnData.dimension()) ?: getLevel(Level.OVERWORLD) ?: allLevels.first()

private fun serverSpawn(server: MinecraftServer): ServerSpawn {
    val world = server.respawnWorld
    val spawn = server.respawnData
    return ServerSpawn(
        world = world.worldName,
        key = world.worldKey,
        x = spawn.pos().x, y = spawn.pos().y, z = spawn.pos().z,
        yaw = spawn.yaw(), pitch = spawn.pitch(),
        respawnRadius = world.gameRules.get(GameRules.RESPAWN_RADIUS),
        spawnProtection = (server as? DedicatedServer)?.spawnProtectionRadius() ?: 0,
    )
}

/** Where a world's spawn stands: the respawn point in the respawn world, the level's own elsewhere. */
private fun spawnOf(world: ServerLevel): LocationView {
    val data = if (world.dimension() == world.server.respawnData.dimension()) world.server.respawnData else world.levelData.respawnData
    val pos = data.pos()
    return LocationView(world.worldName, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), data.yaw(), data.pitch())
}

/** The world's clock, as Paper's `getFullTime` reads it: its default clock's total ticks. */
internal val ServerLevel.fullTime: Long get() = defaultClockTime

fun worldTime(world: ServerLevel): WorldTime {
    val full = world.fullTime
    val time = Math.floorMod(full, 24_000L)
    val fixed = world.dimensionType().hasFixedTime()
    val raining = world.weatherData.isRaining
    val thundering = raining && world.weatherData.isThundering
    return WorldTime(
        dayTime = time,
        fullTime = full,
        gameTime = world.gameTime,
        day = full / 24_000 + 1,
        isDay = time !in 13_000 until 23_000,
        moonPhase = moonPhases[Math.floorMod(full / 24_000, 8L).toInt()],
        fixedTime = fixed,
        ticksUntilNight = if (fixed) null else ticksUntilNight(time),
        canSleep = world.dimension() == Level.OVERWORLD && canSleep(time, raining, thundering),
    )
}

fun weather(world: ServerLevel): Weather {
    val data = world.weatherData
    return Weather(
        state = when {
            // The thunder flag can outlive the rain; without rain there is no storm.
            data.isRaining && data.isThundering -> "thunder"
            data.isRaining -> "rain"
            else -> "clear"
        },
        raining = data.isRaining,
        thundering = data.isRaining && data.isThundering,
        weatherDurationTicks = data.rainTime,
        thunderDurationTicks = data.thunderTime,
        clearWeatherDurationTicks = data.clearWeatherTime,
    )
}

private fun worldView(world: ServerLevel, admin: Boolean) = WorldView(
    name = world.worldName,
    key = world.worldKey,
    environment = environment(world),
    seed = if (admin) world.seed.toString() else null,
    time = worldTime(world),
    weather = weather(world),
    difficulty = world.difficulty.serializedName,
    hardcore = world.levelData.isHardcore,
    pvp = world.isPvpAllowed,
    spawn = spawnOf(world),
    border = border(world),
    gameRules = gameRules(world).associate { it.name to it.value },
    players = world.players().size,
    loadedChunks = world.loadedChunks().size,
    entities = world.entityList().size,
    tileEntities = world.blockEntityCount,
    minHeight = world.minY,
    maxHeight = world.maxHeight,
    seaLevel = world.seaLevel,
    viewDistance = world.server.playerList.viewDistance,
    simulationDistance = world.server.playerList.simulationDistance,
)

/** The clock of a world, which the game moves for every world that shares it. */
private fun ServerLevel.setFullTime(time: Long) {
    val clock = dimensionType().defaultClock().orElse(null) ?: throw IllegalArgumentException("Cannot set time in world without world clock")
    clockManager().setTotalTicks(clock, time)
}

private fun WorldPatch.apply(world: ServerLevel) {
    // Validate everything first, so a bad field doesn't leave half the patch applied.
    val difficulty = difficulty?.let { name -> Difficulty.entries.firstOrNull { it.serializedName.equals(name, ignoreCase = true) } ?: badRequest("difficulty must be peaceful, easy, normal, or hard") }
    if (weather != null && weather !in setOf("clear", "rain", "thunder")) badRequest("weather must be clear, rain, or thunder")
    if (dayTime != null && dayTime!! !in 0..23_999) badRequest("dayTime must be 0–23999; use fullTime or addTime for more")
    listOfNotNull(weatherDurationTicks, thunderDurationTicks, clearWeatherDurationTicks).forEach { if (it < 0) badRequest("Durations can't be negative") }

    dayTime?.let { world.setFullTime(world.fullTime - Math.floorMod(world.fullTime, 24_000L) + it) }
    fullTime?.let { world.setFullTime(it) }
    addTime?.let { world.setFullTime(world.fullTime + it) }
    val data = world.weatherData
    when (weather) {
        "clear" -> {
            // As Bukkit's setters do: each flag change resets its own timer, then the clear timer runs.
            data.isRaining = false
            data.rainTime = 0
            data.isThundering = false
            data.thunderTime = 0
            data.clearWeatherTime = clearWeatherDurationTicks ?: weatherDurationTicks ?: (12_000..180_000).random()
        }
        "rain" -> {
            data.isRaining = true
            data.clearWeatherTime = 0
            data.isThundering = false
            data.thunderTime = 0
            data.rainTime = weatherDurationTicks ?: (12_000..24_000).random()
        }
        "thunder" -> {
            data.isRaining = true
            data.clearWeatherTime = 0
            data.isThundering = true
            data.rainTime = weatherDurationTicks ?: (3_600..15_600).random()
            data.thunderTime = thunderDurationTicks ?: data.rainTime
        }
        null -> {
            weatherDurationTicks?.let { data.rainTime = it }
            thunderDurationTicks?.let { data.thunderTime = it }
            clearWeatherDurationTicks?.let { data.clearWeatherTime = it }
        }
    }
    if (weather != null) data.setDirty()
    difficulty?.let { world.server.setDifficulty(it, true) }
    pvp?.let { world.gameRules.set(GameRules.PVP, it, world.server) }
    spawn?.let {
        val server = world.server
        // The four-number setter keeps the pitch the point has.
        val pitch = if (world.dimension() == server.respawnData.dimension()) server.respawnData.pitch() else 0f
        server.respawnData = LevelData.RespawnData.of(world.dimension(), BlockPos(it.x, it.y, it.z), it.angle, pitch)
    }
}

/** A rule's name as Paper gives it: its id's path, such as `keep_inventory`. */
private fun ruleName(rule: GameRule<*>): String = rule.identifier.path

private fun rule(world: ServerLevel, name: String): GameRule<*> =
    world.gameRules.availableRules().toList().firstOrNull { ruleName(it).equals(name, ignoreCase = true) || it.id().equals(name, ignoreCase = true) }
        ?: badRequest("Unknown game rule \"$name\"", "unknown_gamerule")

private fun typed(rule: GameRule<*>, value: JsonPrimitive): Any = when (rule.valueClass()) {
    java.lang.Boolean::class.java, Boolean::class.java -> value.booleanOrNull ?: badRequest("${ruleName(rule)} takes true or false")
    java.lang.Integer::class.java, Int::class.java -> value.intOrNull?.takeIf { !value.isString } ?: badRequest("${ruleName(rule)} takes a whole number")
    else -> badRequest("${ruleName(rule)} has a type WorldAPI can't set")
}

private fun json(value: Any?): JsonPrimitive = when (value) {
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value?.toString())
}

private fun gameRules(world: ServerLevel): List<GameRuleView> = world.gameRules.availableRules().toList()
    .filter { it.valueClass() == java.lang.Boolean::class.java || it.valueClass() == java.lang.Integer::class.java }
    .map { rule ->
        @Suppress("UNCHECKED_CAST")
        val typed = rule as GameRule<Any>
        GameRuleView(
            name = ruleName(rule),
            type = if (rule.valueClass() == java.lang.Boolean::class.java) "boolean" else "integer",
            value = json(world.gameRules.get(typed)),
            default = json(rule.defaultValue()),
        )
    }
    .sortedBy { it.name }

private fun Double.short() = round(this * 100) / 100

private fun border(world: ServerLevel) = world.worldBorder.let {
    Border(it.size, Position(it.centerX, 0.0, it.centerZ), it.damagePerBlock, it.safeZone, it.warningBlocks, it.warningTime, WorldBorder.MAX_SIZE)
}
