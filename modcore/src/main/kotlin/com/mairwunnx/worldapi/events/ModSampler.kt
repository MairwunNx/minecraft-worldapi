package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.core.ModApi
import com.mairwunnx.worldapi.game.effectViews
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.game.worlds
import com.mairwunnx.worldapi.model.InventoryView
import com.mairwunnx.worldapi.model.Performance
import com.mairwunnx.worldapi.model.Weather
import com.mairwunnx.worldapi.model.WorldTime
import com.mairwunnx.worldapi.routes.inventoryView
import com.mairwunnx.worldapi.routes.performance
import com.mairwunnx.worldapi.routes.weather
import com.mairwunnx.worldapi.routes.worldTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Polls state that has no event of its own, or changes too often to stream raw, as Paper's `Sampler` does: the clock,
 * performance, positions, health, and inventories. The game also has no event for a new level, the weather, or a night
 * slept away, which Bukkit has, so those are noticed here too. Runs on the server thread every tick and only does work
 * for topics someone subscribed to.
 */
class ModSampler(private val api: ModApi, private val events: ModEvents, private val config: () -> WorldApiConfig) {
    private val bus = api.bus
    private val server = api.server
    private var lastTime = 0L
    private var lastPerformance = 0L
    private var lastMove = 0L
    private var lastHealth = 0L
    private var lastInventory = 0L
    private val clocks = mutableMapOf<String, Long>()
    private val positions = mutableMapOf<UUID, Pair<String, Vec3>>()
    private val yaws = mutableMapOf<UUID, Float>()
    private val vitals = mutableMapOf<UUID, List<Any>>()
    private val inventories = mutableMapOf<UUID, Int>()
    private val levels = mutableMapOf<UUID, Int>()
    private val weathers = mutableMapOf<String, Weather>()
    private var overworldClock: Long? = null
    private var sleepers = emptyList<ServerPlayer>()

    /** Where each player stood a tick ago, so a trip to another world knows where it began. */
    val lastPositions = mutableMapOf<UUID, Pair<ServerLevel, Vec3>>()

    fun tick(now: Long = System.currentTimeMillis()) {
        val players = server.playerList.players
        for (player in players) {
            lastPositions[player.uuid] = player.level() to player.position()
            val level = player.experienceLevel
            val before = levels.put(player.uuid, level)
            if (before != null && before != level) events.levelChanged(player, before, level)
        }
        nights()
        weathers()
        if (players.size < levels.size) {
            val online = players.map { it.uuid }.toSet()
            listOf(levels.keys, lastPositions.keys).forEach { it.retainAll(online) }
        }
        val settings = config().websocket
        if (now - lastTime >= settings.timeIntervalSeconds * 1000L) {
            lastTime = now
            // The nether and the end have a fixed clock; the snapshot covers them, ticking them is noise.
            if (bus.interest(Topic.WORLD_TIME)) server.worlds.filterNot { it.dimensionType().hasFixedTime() }.forEach(::time)
        }
        if (now - lastPerformance >= settings.performanceIntervalSeconds * 1000L) {
            lastPerformance = now
            if (bus.interest(Topic.SERVER_PERFORMANCE)) bus.publish(ApiEvent(Topic.SERVER_PERFORMANCE, Performance.serializer().json(performance(api)), subject = "server"))
        }
        if (now - lastMove >= settings.moveIntervalMillis) {
            lastMove = now
            if (bus.interest(Topic.PLAYER_MOVE)) players.forEach(::move) else positions.clear()
        }
        if (now - lastHealth >= 500) {
            lastHealth = now
            if (bus.interest(Topic.PLAYER_HEALTH)) players.forEach(::health) else vitals.clear()
        }
        if (now - lastInventory >= 2_000) {
            lastInventory = now
            if (bus.interest(Topic.PLAYER_INVENTORY)) players.forEach(::inventory) else inventories.clear()
        }
        val online = players.map { it.uuid }.toSet()
        listOf(positions.keys, yaws.keys, vitals.keys, inventories.keys).forEach { it.retainAll(online) }
    }

    /** A night slept away: the overworld's clock jumped forward while players slept, as Bukkit's `NIGHT_SKIP`. */
    private fun nights() {
        val overworld = server.getLevel(Level.OVERWORLD) ?: return
        val clock = overworld.defaultClockTime
        val previous = overworldClock
        overworldClock = clock
        if (previous != null && clock - previous > 20 && sleepers.isNotEmpty()) {
            events.nightSkipped(overworld, clock - previous, sleepers)
            if (bus.wanted(Topic.WORLD_TIME)) time(overworld)
        }
        sleepers = overworld.players().filter { it.isSleeping }
    }

    /** The weather after it changed, which Bukkit announces with its weather and thunder events. */
    private fun weathers() {
        for (level in server.worlds) {
            if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) continue
            val now = weather(level)
            val before = weathers.put(level.worldName, now)
            // Durations count down every tick; only the state counts as a change.
            if (before != null && (before.raining != now.raining || before.thundering != now.thundering) && bus.wanted(Topic.WORLD_WEATHER)) {
                bus.publish(ApiEvent(Topic.WORLD_WEATHER, Weather.serializer().json(now), world = level.worldName, subject = level.worldName))
            }
        }
    }

    private fun time(level: ServerLevel) {
        val full = level.defaultClockTime
        val previous = clocks.put(level.worldName, full)
        val expected = config().websocket.timeIntervalSeconds * 20L
        val time = WorldTime.serializer().json(worldTime(level))
        // A jump means a skipped night or a /time command; clients should snap instead of animating.
        val jumped = previous != null && full - previous !in (expected - 40)..(expected + 40) && !level.dimensionType().hasFixedTime()
        bus.publish(ApiEvent(Topic.WORLD_TIME, JsonObject(time + ("jumped" to JsonPrimitive(jumped))), world = level.worldName, subject = level.worldName))
    }

    private fun move(player: ServerPlayer) {
        val position = player.position()
        val previous = positions[player.uuid]
        val world = player.level().worldName
        if (previous != null && previous.first == world && previous.second.distanceToSqr(position) < 0.25 && yaws[player.uuid] == player.yRot) return
        positions[player.uuid] = world to position
        yaws[player.uuid] = player.yRot
        bus.publish(player.event(Topic.PLAYER_MOVE, buildJsonObject { put("location", player.locationJson()) }))
    }

    private fun health(player: ServerPlayer) {
        val now = listOf(player.health, player.foodData.foodLevel, player.foodData.saturationLevel, player.absorptionAmount, effectIds(player), player.isFullyFrozen)
        if (vitals.put(player.uuid, now) == now) return
        bus.publish(player.event(Topic.PLAYER_HEALTH, vitalsJson(player)))
    }

    private fun inventory(player: ServerPlayer) {
        val inventory = player.inventory
        val hash = (0 until inventory.containerSize).map { ItemStack.hashItemAndComponents(inventory.getItem(it)) * 31 + inventory.getItem(it).count }.hashCode()
        if (inventories.put(player.uuid, hash) == hash) return
        bus.publish(player.event(Topic.PLAYER_INVENTORY, InventoryView.serializer().json(inventoryView(player))))
    }
}

/** Active effects as ids, sorted so an unchanged set compares equal: ["minecraft:poison"]. */
private fun effectIds(player: ServerPlayer): List<String> = player.effectViews().map { it.type }.sorted()

/**
 * What the game's HUD draws for a player: health with its maximum and absorption, food, and what changes how the
 * hearts and food look (effects such as poison, wither, and hunger, and being fully frozen). Shared by the live events
 * and the snapshot.
 */
fun vitalsJson(player: ServerPlayer) = buildJsonObject {
    put("health", player.health.toDouble())
    put("maxHealth", player.getAttributeValue(Attributes.MAX_HEALTH))
    put("absorption", player.absorptionAmount.toDouble())
    put("food", player.foodData.foodLevel)
    put("saturation", player.foodData.saturationLevel)
    put("effects", JsonArray(effectIds(player).map(::JsonPrimitive)))
    put("frozen", player.isFullyFrozen)
}
