package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.model.InventoryView
import com.mairwunnx.worldapi.model.Performance
import com.mairwunnx.worldapi.model.WorldTime
import com.mairwunnx.worldapi.routes.performance
import com.mairwunnx.worldapi.routes.worldTime
import com.mairwunnx.worldapi.bukkit.view
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Polls state that has no event of its own, or changes too often to stream raw: the clock,
 * performance, positions, health, and inventories. Runs on the server thread every few ticks
 * and only does work for topics someone subscribed to.
 */
class Sampler(private val server: Server, private val bus: EventBus, private val config: () -> WorldApiConfig) {
    private var lastTime = 0L
    private var lastPerformance = 0L
    private var lastMove = 0L
    private var lastHealth = 0L
    private var lastInventory = 0L
    private val clocks = mutableMapOf<String, Long>()
    private val positions = mutableMapOf<UUID, Location>()
    private val vitals = mutableMapOf<UUID, List<Number>>()
    private val inventories = mutableMapOf<UUID, Int>()

    fun tick(now: Long = System.currentTimeMillis()) {
        val settings = config().websocket
        if (now - lastTime >= settings.timeIntervalSeconds * 1000L) {
            lastTime = now
            if (bus.interest(Topic.WORLD_TIME)) server.worlds.forEach(::time)
        }
        if (now - lastPerformance >= settings.performanceIntervalSeconds * 1000L) {
            lastPerformance = now
            if (bus.interest(Topic.SERVER_PERFORMANCE)) bus.publish(ApiEvent(Topic.SERVER_PERFORMANCE, Performance.serializer().json(performance(server)), subject = "server"))
        }
        if (now - lastMove >= settings.moveIntervalMillis) {
            lastMove = now
            if (bus.interest(Topic.PLAYER_MOVE)) server.onlinePlayers.forEach(::move) else positions.clear()
        }
        if (now - lastHealth >= 500) {
            lastHealth = now
            if (bus.interest(Topic.PLAYER_HEALTH)) server.onlinePlayers.forEach(::health) else vitals.clear()
        }
        if (now - lastInventory >= 2_000) {
            lastInventory = now
            if (bus.interest(Topic.PLAYER_INVENTORY)) server.onlinePlayers.forEach(::inventory) else inventories.clear()
        }
        val online = server.onlinePlayers.map { it.uniqueId }.toSet()
        listOf(positions.keys, vitals.keys, inventories.keys).forEach { it.retainAll(online) }
    }

    private fun time(world: org.bukkit.World) {
        val previous = clocks.put(world.name, world.fullTime)
        val expected = config().websocket.timeIntervalSeconds * 20L
        val time = WorldTime.serializer().json(worldTime(world))
        // A jump means a skipped night or a /time command; clients should snap instead of animating.
        val jumped = previous != null && world.fullTime - previous !in (expected - 40)..(expected + 40) && !world.isFixedTime
        bus.publish(ApiEvent(Topic.WORLD_TIME, kotlinx.serialization.json.JsonObject(time + ("jumped" to kotlinx.serialization.json.JsonPrimitive(jumped))), world = world.name, subject = world.name))
    }

    private fun move(player: Player) {
        val location = player.location
        val previous = positions[player.uniqueId]
        if (previous != null && previous.world == location.world && previous.distanceSquared(location) < 0.25 && previous.yaw == location.yaw) return
        positions[player.uniqueId] = location
        bus.publish(player.event(Topic.PLAYER_MOVE, buildJsonObject { put("location", location.json()) }))
    }

    private fun health(player: Player) {
        val now = listOf(player.health, player.foodLevel, player.saturation, player.absorptionAmount)
        if (vitals.put(player.uniqueId, now) == now) return
        bus.publish(player.event(Topic.PLAYER_HEALTH, buildJsonObject {
            put("health", player.health)
            put("maxHealth", player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0)
            put("absorption", player.absorptionAmount)
            put("food", player.foodLevel)
            put("saturation", player.saturation)
        }))
    }

    private fun inventory(player: Player) {
        val hash = player.inventory.contents.contentHashCode()
        if (inventories.put(player.uniqueId, hash) == hash) return
        bus.publish(player.event(Topic.PLAYER_INVENTORY, InventoryView.serializer().json(inventoryView(player))))
    }
}

fun inventoryView(player: Player): InventoryView {
    val inventory = player.inventory
    return InventoryView(
        main = inventory.storageContents.mapIndexedNotNull { slot, item -> item.view(slot) },
        armor = com.mairwunnx.worldapi.model.ArmorView(inventory.helmet.view(39), inventory.chestplate.view(38), inventory.leggings.view(37), inventory.boots.view(36)),
        offhand = inventory.itemInOffHand.view(40),
        heldSlot = inventory.heldItemSlot,
    )
}

