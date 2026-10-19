package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.bukkit.effectViews
import com.mairwunnx.worldapi.bukkit.view
import com.mairwunnx.worldapi.bukkit.views
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.inventoryView
import com.mairwunnx.worldapi.model.AdvancementSummary
import com.mairwunnx.worldapi.model.ArmorView
import com.mairwunnx.worldapi.model.ContainerView
import com.mairwunnx.worldapi.model.DeathView
import com.mairwunnx.worldapi.model.Deaths
import com.mairwunnx.worldapi.model.InventoryView
import com.mairwunnx.worldapi.model.LocationView
import com.mairwunnx.worldapi.model.PlayerView
import com.mairwunnx.worldapi.model.SessionView
import com.mairwunnx.worldapi.stats.OfflineInventory
import com.mairwunnx.worldapi.stats.PlayerData
import com.mairwunnx.worldapi.store.EventQuery
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.io.File
import java.time.Instant

/** What `GET /players/{player}?include=` may add, in the order the docs list them. */
val PLAYER_PARTS = listOf("inventory", "enderchest", "effects", "deaths", "sessions", "statistics", "advancements")

private const val RECENT = 5

/** Parses `include=a,b`, refusing names outside [allowed] so a typo isn't silently ignored. */
fun ApiCall.includes(allowed: List<String>): Set<String> {
    val parts = query("include")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: return emptySet()
    (parts - allowed.toSet()).takeIf { it.isNotEmpty() }?.let { badRequest("include takes ${allowed.joinToString()}; unknown: ${it.joinToString()}") }
    return parts
}

/**
 * Adds the parts asked for to a player's view. [online] is null for an offline (or vanished)
 * player: their inventory and ender chest then come from their playerdata file.
 */
suspend fun ApiCall.withParts(view: PlayerView, player: OfflinePlayer, online: Player?, parts: Set<String>): PlayerView {
    if (parts.isEmpty()) return view
    if ((parts.contains("inventory") || parts.contains("enderchest")) && !seesInventories) {
        throw ApiException(HttpStatusCode.Forbidden, "private", "Inventories are hidden from anonymous clients")
    }
    val coordinates = seesCoordinates
    val uuid = player.uniqueId
    val (inventory, enderChest) = if (parts.contains("inventory") || parts.contains("enderchest")) inventories(player, online) else null to null
    val effects = if (parts.contains("effects")) main { online?.effectViews() } else null
    val deaths = if (parts.contains("deaths")) {
        val last = main { player.lastDeathLocation }?.takeIf { coordinates }?.view(withRotation = false)
        val recent = api.history.events(EventQuery(types = listOf(Topic.PLAYER_DEATH.id), player = uuid.toString(), limit = RECENT, includeHidden = seesVanished))
            .map { event -> death(event.at, event.data, coordinates) }
        Deaths(last, recent)
    } else {
        null
    }
    val sessions = if (parts.contains("sessions")) {
        val now = System.currentTimeMillis()
        // A vanished player's open session would say they're here.
        val hidden = online == null && main { player.isOnline }
        api.history.sessions(uuid.toString(), before = null, limit = RECENT, includeHidden = seesVanished)
            .filterNot { hidden && it.leftAt == null }
            .map { SessionView(Instant.ofEpochMilli(it.joinedAt).toString(), it.leftAt?.let { at -> Instant.ofEpochMilli(at).toString() }, ((it.leftAt ?: now) - it.joinedAt) / 1000, it.closedByRestart) }
    } else {
        null
    }
    val statistics = if (parts.contains("statistics")) api.statistics.of(player)["minecraft:custom"].orEmpty() else null
    val advancements = if (parts.contains("advancements")) {
        val list = if (online != null) {
            main { liveAdvancements(online, recipes = false) }
        } else {
            val text = api.statistics.advancementsFile(uuid) ?: ""
            main { fileAdvancements(text, recipes = false) }
        }.filterNot { it.key.endsWith("/root") }
        AdvancementSummary(list.count { it.done }, list.size, list.filter { it.done }.sortedByDescending { it.doneAt }.take(RECENT))
    } else {
        null
    }
    return view.copy(
        inventory = inventory, enderChest = enderChest, effects = effects, deaths = deaths,
        sessions = sessions, statistics = statistics, advancements = advancements,
    )
}

/**
 * A player's inventory and ender chest: live while [online] isn't null, otherwise from their
 * playerdata file, or nulls when there is none.
 */
suspend fun ApiCall.inventories(player: OfflinePlayer, online: Player?): Pair<InventoryView?, ContainerView?> = main {
    if (online != null) {
        inventoryView(online) to online.enderChest.let { ContainerView(it.size, it.views()) }
    } else {
        val file = PlayerData.folder(api.server.worlds.first().worldFolder)?.let { File(it, "${player.uniqueId}.dat") }?.takeIf { it.isFile }
        val contents = file?.let(OfflineInventory::read)
        contents?.let { offlineInventory(it) } to contents?.let { c -> ContainerView(27, c.enderChest.toSortedMap().mapNotNull { (slot, item) -> item.view(slot) }) }
    }
}

private fun offlineInventory(contents: OfflineInventory.Contents): InventoryView {
    val items = contents.inventory
    return InventoryView(
        main = items.filterKeys { it in 0..35 }.toSortedMap().mapNotNull { (slot, item) -> item.view(slot) },
        armor = ArmorView(items[39].view(39), items[38].view(38), items[37].view(37), items[36].view(36)),
        offhand = items[40].view(40),
        heldSlot = contents.selectedSlot,
    )
}

private fun death(at: Long, data: JsonObject, coordinates: Boolean): DeathView {
    fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
    val killer = data["killer"] as? JsonObject
    return DeathView(
        at = Instant.ofEpochMilli(at).toString(),
        message = text("message"),
        cause = text("cause"),
        killer = killer?.let { (it["name"] as? JsonPrimitive)?.contentOrNull ?: (it["type"] as? JsonPrimitive)?.contentOrNull },
        level = (data["level"] as? JsonPrimitive)?.intOrNull,
        location = if (coordinates) data["location"]?.let { runCatching { ApiJson.decodeFromJsonElement(LocationView.serializer(), it) }.getOrNull() } else null,
    )
}
