package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.game.Known
import com.mairwunnx.worldapi.game.PlayerFile
import com.mairwunnx.worldapi.game.effectViews
import com.mairwunnx.worldapi.game.view
import com.mairwunnx.worldapi.game.views
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.model.AdvancementSummary
import com.mairwunnx.worldapi.model.ArmorView
import com.mairwunnx.worldapi.model.ContainerView
import com.mairwunnx.worldapi.model.DeathView
import com.mairwunnx.worldapi.model.Deaths
import com.mairwunnx.worldapi.model.InventoryView
import com.mairwunnx.worldapi.model.LocationView
import com.mairwunnx.worldapi.model.PlayerView
import com.mairwunnx.worldapi.model.SessionView
import com.mairwunnx.worldapi.store.EventQuery
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import java.time.Instant

/** What `GET /players/{player}?include=` may add, in the order the docs list them. */
val PLAYER_PARTS = listOf("inventory", "enderchest", "effects", "deaths", "sessions", "statistics", "advancements", "target")

private const val RECENT = 5

/** Parses `include=a,b`, refusing names outside [allowed] so a typo isn't silently ignored. */
fun ModCall.includes(allowed: List<String>): Set<String> {
    val parts = query("include")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: return emptySet()
    (parts - allowed.toSet()).takeIf { it.isNotEmpty() }?.let { badRequest("include takes ${allowed.joinToString()}; unknown: ${it.joinToString()}") }
    return parts
}

/**
 * Adds the parts asked for to a player's view. [online] is null for an offline (or vanished) player: their inventory
 * and ender chest then come from their playerdata file.
 */
suspend fun ModCall.withParts(view: PlayerView, player: Known, online: ServerPlayer?, parts: Set<String>): PlayerView {
    if (parts.isEmpty()) return view
    if ((parts.contains("inventory") || parts.contains("enderchest")) && !seesInventories) {
        throw ApiException(HttpStatusCode.Forbidden, "private", "Inventories are hidden from anonymous clients")
    }
    val coordinates = seesCoordinates
    val uuid = player.uuid
    val (inventory, enderChest) = if (parts.contains("inventory") || parts.contains("enderchest")) inventories(player, online) else null to null
    val effects = if (parts.contains("effects")) main { online?.effectViews() } else null
    val deaths = if (parts.contains("deaths")) {
        val last = main { lastDeath(player) }?.takeIf { coordinates }
        val recent = api.history.events(EventQuery(types = listOf(Topic.PLAYER_DEATH.id), player = uuid.toString(), limit = RECENT, includeHidden = seesVanished))
            .map { event -> death(event.at, event.data, coordinates) }
        Deaths(last, recent)
    } else {
        null
    }
    val sessions = if (parts.contains("sessions")) {
        val now = System.currentTimeMillis()
        // A vanished player's open session would say they're here.
        val hidden = online == null && main { api.server.playerList.getPlayer(uuid) != null }
        api.history.sessions(uuid.toString(), before = null, limit = RECENT, includeHidden = seesVanished)
            .filterNot { hidden && it.leftAt == null }
            .map { SessionView(Instant.ofEpochMilli(it.joinedAt).toString(), it.leftAt?.let { at -> Instant.ofEpochMilli(at).toString() }, ((it.leftAt ?: now) - it.joinedAt) / 1000, it.closedByRestart) }
    } else {
        null
    }
    val statistics = if (parts.contains("statistics")) api.statistics.of(uuid)["minecraft:custom"].orEmpty() else null
    val advancements = if (parts.contains("advancements")) {
        val list = if (online != null) {
            main { liveAdvancements(api.server, online, recipes = false) }
        } else {
            val text = api.statistics.advancementsFile(uuid) ?: ""
            main { fileAdvancements(api.server, text, recipes = false) }
        }.filterNot { it.key.endsWith("/root") }
        AdvancementSummary(list.count { it.done }, list.size, list.filter { it.done }.sortedByDescending { it.doneAt }.take(RECENT))
    } else {
        null
    }
    val target = if (parts.contains("target")) {
        if (!coordinates) throw ApiException(HttpStatusCode.Forbidden, "private", "Where players look is hidden from anonymous clients")
        val inventories = seesInventories
        main { online?.let { targetView(it, inventories, api.loader::vanished) } }
    } else {
        null
    }
    return view.copy(
        inventory = inventory, enderChest = enderChest, effects = effects, deaths = deaths,
        sessions = sessions, statistics = statistics, advancements = advancements, target = target,
    )
}

private const val REACH = 64.0

private fun Double.short() = Math.round(this * 100) / 100.0

/** The block and the entity in a player's line of sight, as Paper reads them; an entity behind the block doesn't count. Server thread. */
fun targetView(player: ServerPlayer, inventories: Boolean, vanished: (ServerPlayer) -> Boolean): com.mairwunnx.worldapi.model.TargetView {
    val level = player.level()
    val eye = player.eyePosition
    val look = player.getViewVector(1f)
    val hit = level.clip(net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(REACH)), net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player))
    val blockHit = hit.takeIf { it.type == net.minecraft.world.phys.HitResult.Type.BLOCK }
    val reach = blockHit?.location?.distanceTo(eye) ?: REACH
    val end = eye.add(look.scale(reach))
    val entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
        player, eye, end, player.boundingBox.expandTowards(look.scale(reach)).inflate(1.0),
        { it != player && !it.isSpectator && !(it is ServerPlayer && vanished(it)) }, reach * reach,
    )
    return com.mairwunnx.worldapi.model.TargetView(
        eye = LocationView(level.worldName, eye.x.short(), eye.y.short(), eye.z.short(), player.yRot, player.xRot),
        block = blockHit?.let { com.mairwunnx.worldapi.game.blockView(level, it.blockPos, inventories) },
        face = blockHit?.direction?.serializedName,
        blockDistance = blockHit?.location?.distanceTo(eye)?.short(),
        entity = entityHit?.entity?.let { entitySummary(it, coordinates = true) },
        entityDistance = entityHit?.location?.distanceTo(eye)?.short(),
    )
}

/** Where the player last died, from the live player or the playerdata file. Server thread. */
private fun ModCall.lastDeath(player: Known): LocationView? {
    val live = api.server.playerList.getPlayer(player.uuid)
    val pos = live?.lastDeathLocation?.orElse(null) ?: PlayerFile.read(api.server, player.uuid)?.let { file ->
        file.tag.getCompound("LastDeathLocation").orElse(null)?.let { tag ->
            net.minecraft.core.GlobalPos.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElse(null)
        }
    }
    return pos?.view(api.server)
}

/** A player's inventory as Paper's sampler reads it: storage slots, armor, and the offhand. */
fun inventoryView(player: ServerPlayer): InventoryView {
    val inventory = player.inventory
    val registries = player.level().server.registryAccess()
    return InventoryView(
        main = (0 until 36).mapNotNull { slot -> inventory.getItem(slot).view(slot, registries = registries) },
        armor = ArmorView(
            player.getItemBySlot(EquipmentSlot.HEAD).view(39, registries = registries), player.getItemBySlot(EquipmentSlot.CHEST).view(38, registries = registries),
            player.getItemBySlot(EquipmentSlot.LEGS).view(37, registries = registries), player.getItemBySlot(EquipmentSlot.FEET).view(36, registries = registries),
        ),
        offhand = player.getItemBySlot(EquipmentSlot.OFFHAND).view(40, registries = registries),
        heldSlot = inventory.selectedSlot,
    )
}

/**
 * A player's inventory and ender chest: live while [online] isn't null, otherwise from their playerdata file, or nulls
 * when there is none.
 */
suspend fun ModCall.inventories(player: Known, online: ServerPlayer?): Pair<InventoryView?, ContainerView?> = main {
    val registries = api.server.registryAccess()
    if (online != null) {
        inventoryView(online) to online.enderChestInventory.let { ContainerView(it.containerSize, it.views(registries)) }
    } else {
        val file = PlayerFile.read(api.server, player.uuid)
        val items = file?.inventory()
        val inventory = items?.let {
            InventoryView(
                main = it.filterKeys { slot -> slot in 0..35 }.toSortedMap().mapNotNull { (slot, item) -> item.view(slot, registries = registries) },
                armor = ArmorView(it[39].view(39, registries = registries), it[38].view(38, registries = registries), it[37].view(37, registries = registries), it[36].view(36, registries = registries)),
                offhand = it[40].view(40, registries = registries),
                heldSlot = file.selectedSlot,
            )
        }
        inventory to file?.let { ContainerView(27, it.enderChest().toSortedMap().mapNotNull { (slot, item) -> item.view(slot, registries = registries) }) }
    }
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
