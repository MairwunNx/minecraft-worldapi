package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.number
import com.mairwunnx.worldapi.api.orNotFound
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.effectViews
import com.mairwunnx.worldapi.bukkit.loadBlockChunk
import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.bukkit.plainOrNull
import com.mairwunnx.worldapi.bukkit.position
import com.mairwunnx.worldapi.bukkit.ref
import com.mairwunnx.worldapi.bukkit.registryEntry
import com.mairwunnx.worldapi.bukkit.text
import com.mairwunnx.worldapi.bukkit.toLocation
import com.mairwunnx.worldapi.bukkit.uuidOrNull
import com.mairwunnx.worldapi.bukkit.view
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.EntityPatch
import com.mairwunnx.worldapi.model.EntitySummary
import com.mairwunnx.worldapi.model.EntityView
import com.mairwunnx.worldapi.model.SpawnRequest
import com.mairwunnx.worldapi.model.Spawned
import io.papermc.paper.registry.RegistryKey
import kotlinx.coroutines.future.await
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector

val entityRoutes = endpoints("Entities") {
    get(
        "/worlds/{world}/entities", "Entities in a world, filtered by type and area",
        description = "Give x, y, z, and radius to search around a point. Without a point, it lists every loaded entity.",
        query = pageParams + listOf(
            string("type", "Entity key, such as `minecraft:zombie`; several separated by commas"),
            number("x", "Center X"), number("y", "Center Y"), number("z", "Center Z"),
            number("radius", "Search radius in blocks; the server caps it"),
        ),
    ) {
        val types = query("type")?.split(',')?.map { registryEntry(RegistryKey.ENTITY_TYPE, it.trim(), "entity") }?.toSet()
        val center = listOf(double("x"), double("y"), double("z"))
        val radius = double("radius")
        if (center.any { it != null } && center.any { it == null }) badRequest("Give all of x, y, and z, or none")
        if (radius != null && (radius <= 0 || radius > config.limits.maxEntityRadius)) badRequest("radius must be between 0 and ${config.limits.maxEntityRadius}")
        val coordinates = seesCoordinates
        val (limit, cursor) = limit to cursor
        main {
            val world = world()
            val found: Collection<Entity> = if (center[0] != null) {
                val r = radius ?: 32.0
                world.getNearbyEntities(Location(world, center[0]!!, center[1]!!, center[2]!!), r, r, r) { it.location.distance(Location(world, center[0]!!, center[1]!!, center[2]!!)) <= r }
            } else {
                world.entities
            }
            found.asSequence()
                .filter { types == null || it.type in types }
                .sortedBy { it.uniqueId }
                .map { summary(it, coordinates) }
                .toList()
                .page(limit, cursor)
        }
    }

    get("/worlds/{world}/entities/{uuid}", "One entity in detail") {
        val coordinates = seesCoordinates
        main { entityView(entity(), coordinates) }
    }

    patch<EntityPatch, EntityView>("/worlds/{world}/entities/{uuid}", "Change an entity: name, flags, health, position", Scope.WRITE_WORLD) { patch ->
        val location = patch.location?.toLocation(api.server)
        main {
            val entity = entity()
            if (entity is Player) badRequest("Use the player endpoints for players")
            auditTarget = "${entity.type.key.key} ${entity.uniqueId}"
            patch.customName?.let { entity.customName(text(it, patch.format, 256)) }
            patch.customNameVisible?.let { entity.isCustomNameVisible = it }
            patch.glowing?.let { entity.isGlowing = it }
            patch.silent?.let { entity.isSilent = it }
            patch.invulnerable?.let { entity.isInvulnerable = it }
            patch.gravity?.let { entity.setGravity(it) }
            patch.persistent?.let { entity.isPersistent = it }
            patch.fireTicks?.let { entity.fireTicks = it }
            patch.velocity?.let { entity.velocity = Vector(it.x, it.y, it.z) }
            patch.addTags.forEach { entity.addScoreboardTag(it) }
            patch.removeTags.forEach { entity.removeScoreboardTag(it) }
            if (entity is LivingEntity) {
                patch.ai?.let { entity.setAI(it) }
                patch.health?.let { entity.health = it.coerceIn(0.0, entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: it) }
            } else if (patch.ai != null || patch.health != null) {
                badRequest("Only living entities have AI and health")
            }
            entity to location
        }.let { (entity, location) ->
            location?.let { main { entity.teleportAsync(it) }.await() }
            main { entityView(entity, true) }
        }
    }

    delete("/worlds/{world}/entities/{uuid}", "Remove an entity", Scope.WRITE_WORLD) {
        main {
            val entity = entity()
            if (entity is Player) badRequest("Players can't be removed; kick them instead")
            auditTarget = "${entity.type.key.key} ${entity.uniqueId}"
            entity.remove()
        }
    }

    post<SpawnRequest, Spawned>(
        "/worlds/{world}/entities", "Spawn entities", Scope.WRITE_WORLD,
        description = "Spawns up to limits.maxSpawnCount at once.",
        status = io.ktor.http.HttpStatusCode.Created,
    ) { request ->
        if (request.count !in 1..config.limits.maxSpawnCount) badRequest("count must be between 1 and ${config.limits.maxSpawnCount}")
        val world = world()
        val location = request.location.toLocation(api.server, fallback = world)
        loadBlockChunk(location.world, location.blockX, location.blockZ)
        auditTarget = "${request.count} × ${request.type}"
        main {
            val type = registryEntry(RegistryKey.ENTITY_TYPE, request.type, "entity")
            if (!type.isSpawnable || type == EntityType.PLAYER) badRequest("${request.type} can't be spawned")
            val uuids = (1..request.count).map {
                val entity = location.world.spawnEntity(location, type)
                request.customName?.let { entity.customName(text(it, request.format, 256)) }
                request.glowing?.let { entity.isGlowing = it }
                request.persistent?.let { entity.isPersistent = it }
                request.ai?.let { (entity as? LivingEntity)?.setAI(it) }
                entity.uniqueId.toString()
            }
            Spawned(uuids)
        }
    }
}

private fun ApiCall.entity(): Entity {
    val id = path("uuid")
    val uuid = uuidOrNull(id) ?: badRequest("\"$id\" isn't a UUID")
    return world().getEntity(uuid).orNotFound("entity_not_found") { "No loaded entity $id in ${path("world")}" }
}

private fun summary(entity: Entity, coordinates: Boolean) = EntitySummary(
    uuid = entity.uniqueId.toString(),
    type = entity.type.key.asString(),
    name = entity.customName()?.plain() ?: (entity as? Player)?.name,
    location = if (coordinates) entity.location.view() else null,
    health = (entity as? LivingEntity)?.health,
)

fun entityView(entity: Entity, coordinates: Boolean): EntityView {
    val living = entity as? LivingEntity
    val equipment = living?.equipment?.let { gear ->
        EquipmentSlot.entries.mapNotNull { slot ->
            runCatching { gear.getItem(slot) }.getOrNull()?.view()?.let { slot.name.lowercase() to it }
        }.toMap()
    }
    return EntityView(
        uuid = entity.uniqueId.toString(),
        type = entity.type.key.asString(),
        name = entity.name,
        customName = entity.customName().plainOrNull(),
        customNameVisible = entity.isCustomNameVisible,
        location = if (coordinates) entity.location.view() else null,
        velocity = Location(entity.world, entity.velocity.x, entity.velocity.y, entity.velocity.z).position(),
        onGround = entity.isOnGround,
        ticksLived = entity.ticksLived,
        fireTicks = entity.fireTicks,
        glowing = entity.isGlowing,
        silent = entity.isSilent,
        invulnerable = entity.isInvulnerable,
        gravity = entity.hasGravity(),
        persistent = entity.isPersistent,
        tags = entity.scoreboardTags.sorted(),
        passengers = entity.passengers.map { it.uniqueId.toString() },
        vehicle = entity.vehicle?.uniqueId?.toString(),
        health = living?.health,
        maxHealth = living?.getAttribute(Attribute.MAX_HEALTH)?.value,
        ai = living?.hasAI(),
        target = (entity as? Mob)?.target?.uniqueId?.toString(),
        adult = (entity as? Ageable)?.isAdult,
        tamed = (entity as? Tameable)?.isTamed,
        owner = (entity as? Tameable)?.owner?.let { entity.server.getOfflinePlayer(it.uniqueId).ref() },
        equipment = equipment?.takeIf { it.isNotEmpty() },
        effects = living?.effectViews(),
    )
}
