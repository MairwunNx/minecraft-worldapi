package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.core.ModApi
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
import com.mairwunnx.worldapi.ws.Filter
import com.mairwunnx.worldapi.ws.SnapshotSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The current state of a topic, so a new subscriber doesn't wait for the next change, as Paper's `Snapshots`. */
class ModSnapshots(private val api: ModApi) : SnapshotSource {
    override suspend fun snapshot(topic: Topic, filter: Filter?): List<ApiEvent> = api.main.call {
        val server = api.server
        val worlds = server.worlds.filter { filter?.world == null || it.worldName.equals(filter.world, ignoreCase = true) }
        val players = server.playerList.players.toList()
        val vanished = players.filter(api.loader::vanished).mapTo(HashSet()) { it.uuid.toString() }
        when (topic) {
            Topic.WORLD_TIME -> worlds.map { ApiEvent(topic, WorldTime.serializer().json(worldTime(it)), world = it.worldName, subject = it.worldName) }
            Topic.WORLD_WEATHER -> worlds.map { ApiEvent(topic, Weather.serializer().json(weather(it)), world = it.worldName, subject = it.worldName) }
            Topic.SERVER_PERFORMANCE -> listOf(ApiEvent(topic, Performance.serializer().json(performance(api)), subject = "server"))
            Topic.SERVER_STATUS -> listOf(ApiEvent(topic, buildJsonObject {
                put("status", "running")
                put("online", players.size - vanished.size)
            }))
            // For join and leave, the snapshot is who is online right now.
            Topic.PLAYER_JOIN, Topic.PLAYER_LEAVE -> players.map { it.event(Topic.PLAYER_JOIN, buildJsonObject { put("online", true) }) }
            Topic.PLAYER_MOVE -> players.map { it.event(topic, buildJsonObject { put("location", it.locationJson()) }) }
            Topic.PLAYER_HEALTH -> players.map { it.event(topic, vitalsJson(it)) }
            Topic.PLAYER_GAMEMODE -> players.map { it.event(topic, buildJsonObject { put("to", it.gameMode().serializedName) }) }
            Topic.PLAYER_INVENTORY -> players.map { it.event(topic, InventoryView.serializer().json(inventoryView(it))) }
            else -> emptyList()
        }.map { if (it.player?.uuid in vanished) it.copy(hidden = true) else it }
    }
}
