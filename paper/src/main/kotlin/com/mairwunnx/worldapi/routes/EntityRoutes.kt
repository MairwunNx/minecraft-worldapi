package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.PaperCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.number
import com.mairwunnx.worldapi.api.orNotFound
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.hides
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
import com.mairwunnx.worldapi.model.ChunkLoad
import com.mairwunnx.worldapi.model.EntityCensus
import com.mairwunnx.worldapi.model.EntitySummary
import com.mairwunnx.worldapi.model.TradeView
import com.mairwunnx.worldapi.model.VillagerView
import com.mairwunnx.worldapi.model.EntityView
import com.mairwunnx.worldapi.model.RemovedEntities
import com.mairwunnx.worldapi.model.SpawnRequest
import com.mairwunnx.worldapi.model.Spawned
import io.papermc.paper.registry.RegistryKey
import kotlinx.coroutines.future.await
import org.bukkit.Location
import org.bukkit.entity.memory.MemoryKey
import org.bukkit.World
import org.bukkit.block.Hopper
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.Villager
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector

val entityRoutes = endpoints("Entities", Scope.SERVER_READ) {
    get(
        "/worlds/{world}/entities", "Entities in a world, filtered by type, area, name, or chunk",
        description = "Give x, y, z, and radius to search around a point, nearest first. Without a point, it lists loaded entities by UUID. " +
            "Pages hold 25 by default: for a picture of a crowded world, GET /worlds/{world}/entities/summary first.",
        query = pageParams + listOf(
            string("type", "Entity key, such as `minecraft:zombie`; several separated by commas"),
            number("x", "Center X"), number("y", "Center Y"), number("z", "Center Z"),
            number("radius", "Search radius in blocks; the server caps it"),
            string("name", "Only entities whose custom name (or player name) contains this, ignoring case"),
            string("chunk", "Only this chunk, as `chunkX,chunkZ`"),
        ),
    ) {
        val types = query("type")?.split(',')?.map { registryEntry(RegistryKey.ENTITY_TYPE, it.trim(), "entity") }?.toSet()
        val area = area()
        val name = query("name")
        val chunk = query("chunk")?.split(',')?.map { it.trim().toIntOrNull() ?: badRequest("chunk must be chunkX,chunkZ") }
            ?.takeIf { it.size == 2 } ?: query("chunk")?.let { badRequest("chunk must be chunkX,chunkZ") }
        val coordinates = seesCoordinates
        val limit = if (int("limit") == null) minOf(25, limit) else limit
        val cursor = cursor
        main {
            val world = world()
            val origin = area?.let { Location(world, it.x, it.y, it.z) }
            val found: Collection<Entity> = when {
                chunk != null -> if (world.isChunkLoaded(chunk[0], chunk[1])) world.getChunkAt(chunk[0], chunk[1]).entities.toList() else emptyList()
                origin != null -> nearby(world, origin, area.radius)
                else -> world.entities
            }
            found.asSequence()
                .filter { types == null || it.type in types }
                .filter { name == null || (it.customName()?.plain() ?: (it as? Player)?.name)?.contains(name, ignoreCase = true) == true }
                .filter { origin == null || chunk == null || it.location.distanceSquared(origin) <= area.radius * area.radius }
                .filterNot { it is Player && hides(it) }
                .sortedWith(if (origin != null) compareBy { it.location.distanceSquared(origin) } else compareBy { it.uniqueId })
                .map { entitySummary(it, coordinates) }
                .toList()
                .page(limit, cursor)
        }
    }

    get(
        "/worlds/{world}/entities/summary", "How many entities there are, of which types, and where they crowd",
        description = "Counts loaded entities in the whole world, or around x, y, z within radius: per type, dropped items, villagers, named mobs, tamed pets, " +
            "and the ten busiest chunks with their block entities and hoppers. A compact first look before listing entities.",
        query = listOf(
            string("type", "Count only these entity keys, separated by commas"),
            number("x", "Center X"), number("y", "Center Y"), number("z", "Center Z"),
            number("radius", "Radius in blocks around x, y, z; the server caps it"),
        ),
    ) {
        val types = query("type")?.split(',')?.map { registryEntry(RegistryKey.ENTITY_TYPE, it.trim(), "entity") }?.toSet()
        val area = area()
        main {
            val world = world()
            val entities = (area?.let { nearby(world, Location(world, it.x, it.y, it.z), it.radius) } ?: world.entities)
                .filter { types == null || it.type in types }
                .filterNot { it is Player && hides(it) }
            census(world, entities)
        }
    }

    get("/worlds/{world}/entities/{uuid}", "One entity in detail") {
        val coordinates = seesCoordinates
        val inventories = seesInventories
        main { entityView(entity(), coordinates, inventories) }
    }

    patch<EntityPatch, EntityView>("/worlds/{world}/entities/{uuid}", "Change an entity: name, flags, health, position", Scope.WORLD_WRITE) { patch ->
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
            main { entityView(entity, coordinates = true, inventories = true) }
        }
    }

    delete(
        "/worlds/{world}/entities", "Remove many entities by type, optionally around a point",
        description = "Removes loaded entities of the given types without drops, up to `max` at once (limits.maxEntityRemoval at most). " +
            "Players are never removed. Named mobs and tamed pets are skipped unless `includeNamed` is true. " +
            "With `dryRun`, nothing is removed and the answer says what would be.",
        scope = Scope.WORLD_WRITE,
        query = listOf(
            string("type", "Entity keys, separated by commas, such as `minecraft:item,minecraft:arrow`", required = true),
            number("x", "Center X"), number("y", "Center Y"), number("z", "Center Z"),
            number("radius", "Radius in blocks around x, y, z; the server caps it"),
            integer("max", "Remove at most this many, nearest first; the rest stay and are counted in `remaining`"),
            boolean("includeNamed", "Also remove named mobs and tamed pets"),
            boolean("dryRun", "Only count what would be removed"),
        ),
    ) {
        val types = query("type")!!.split(',').map { registryEntry(RegistryKey.ENTITY_TYPE, it.trim(), "entity") }.toSet()
        if (EntityType.PLAYER in types) badRequest("Players can't be removed; kick them instead")
        val center = listOf(double("x"), double("y"), double("z"))
        val radius = double("radius")
        if (center.any { it != null } && center.any { it == null }) badRequest("Give all of x, y, and z, or none")
        if (radius != null && center[0] == null) badRequest("radius needs x, y, and z")
        if (radius != null && (radius <= 0 || radius > config.limits.maxEntityRadius)) badRequest("radius must be between 0 and ${config.limits.maxEntityRadius}")
        val cap = config.limits.maxEntityRemoval
        val max = int("max") ?: cap
        if (max !in 1..cap) badRequest("max must be between 1 and $cap")
        val includeNamed = bool("includeNamed") ?: false
        val dryRun = bool("dryRun") ?: false
        main {
            val world = world()
            auditTarget = "${world.name} ${types.joinToString(",") { it.key.key }}"
            val origin = center[0]?.let { Location(world, it, center[1]!!, center[2]!!) }
            val nearby: Collection<Entity> = if (origin != null) {
                val r = radius ?: 32.0
                world.getNearbyEntities(origin, r, r, r) { it.location.distanceSquared(origin) <= r * r }
            } else {
                world.entities
            }
            val matching = nearby.filter { it.type in types && removable(it, includeNamed) }
                .sortedBy { e -> origin?.let { e.location.distanceSquared(it) } ?: 0.0 }
            val chosen = matching.take(max)
            auditDetail = if (dryRun) "dry run, ${matching.size} matching" else "${chosen.size} removed"
            if (!dryRun) chosen.forEach(Entity::remove)
            val removed = if (dryRun) 0 else chosen.size
            RemovedEntities(
                matched = matching.size,
                removed = removed,
                remaining = matching.size - removed,
                dryRun = dryRun,
                byType = chosen.groupingBy { it.type.key.asString() }.eachCount().toSortedMap(),
            )
        }
    }

    delete("/worlds/{world}/entities/{uuid}", "Remove an entity", Scope.WORLD_WRITE) {
        main {
            val entity = entity()
            if (entity is Player) badRequest("Players can't be removed; kick them instead")
            auditTarget = "${entity.type.key.key} ${entity.uniqueId}"
            entity.remove()
        }
    }

    post<SpawnRequest, Spawned>(
        "/worlds/{world}/entities", "Spawn entities", Scope.WORLD_WRITE,
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

/** Players never go; named mobs and tamed pets only when asked for, since someone cares about them. */
internal fun removable(entity: Entity, includeNamed: Boolean): Boolean = when {
    entity is Player -> false
    includeNamed -> true
    entity.customName() != null -> false
    entity is Tameable && entity.isTamed -> false
    else -> true
}

private fun PaperCall.entity(): Entity {
    val id = path("uuid")
    val uuid = uuidOrNull(id) ?: badRequest("\"$id\" isn't a UUID")
    // A vanished player is as absent here as anywhere else.
    return world().getEntity(uuid)?.takeUnless { it is Player && hides(it) }.orNotFound("entity_not_found") { "No loaded entity $id in ${path("world")}" }
}

private class Area(val x: Double, val y: Double, val z: Double, val radius: Double)

/** x, y, z, and radius from the query, all or none; radius defaults to 32 and stays within limits.maxEntityRadius. */
private fun PaperCall.area(): Area? {
    val center = listOf(double("x"), double("y"), double("z"))
    val radius = double("radius")
    if (center.any { it != null } && center.any { it == null }) badRequest("Give all of x, y, and z, or none")
    if (radius != null && center[0] == null) badRequest("radius needs x, y, and z")
    if (radius != null && (radius <= 0 || radius > config.limits.maxEntityRadius)) badRequest("radius must be between 0 and ${config.limits.maxEntityRadius}")
    return center[0]?.let { Area(it, center[1]!!, center[2]!!, radius ?: 32.0) }
}

private fun nearby(world: World, origin: Location, radius: Double): Collection<Entity> =
    world.getNearbyEntities(origin, radius, radius, radius) { it.location.distanceSquared(origin) <= radius * radius }

private fun census(world: World, entities: List<Entity>): EntityCensus {
    val byType = entities.groupingBy { it.type.key.asString() }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    val items = entities.filterIsInstance<Item>()
    val chunks = entities.groupBy { it.location.blockX shr 4 to (it.location.blockZ shr 4) }.entries.sortedByDescending { it.value.size }.take(10).map { (at, inside) ->
        val (cx, cz) = at
        val tiles = if (world.isChunkLoaded(cx, cz)) world.getChunkAt(cx, cz).getTileEntities(false) else emptyArray()
        ChunkLoad(cx, cz, inside.size, tiles.size, tiles.count { it is Hopper }, topTypes(inside.map { it.type.key.asString() }, 3))
    }
    return EntityCensus(
        world = world.name,
        total = entities.size,
        byType = byType.take(20).associateTo(LinkedHashMap()) { it.key to it.value },
        otherTypes = byType.drop(20).sumOf { it.value },
        droppedItems = items.size,
        droppedItemCount = items.sumOf { it.itemStack.amount },
        villagers = entities.count { it is Villager },
        named = entities.count { it !is Player && it.customName() != null },
        tamed = entities.count { it is Tameable && it.isTamed },
        players = entities.count { it is Player },
        topChunks = chunks,
    )
}

internal fun entitySummary(entity: Entity, coordinates: Boolean) = EntitySummary(
    uuid = entity.uniqueId.toString(),
    type = entity.type.key.asString(),
    name = entity.customName()?.plain() ?: (entity as? Player)?.name,
    location = if (coordinates) entity.location.view() else null,
    health = (entity as? LivingEntity)?.health,
)

/** A player's equipment is part of their inventory, so it shows only with [inventories]. */
fun entityView(entity: Entity, coordinates: Boolean, inventories: Boolean): EntityView {
    val living = entity as? LivingEntity
    val equipment = living?.takeIf { inventories || it !is Player }?.equipment?.let { gear ->
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
        invulnerable = entity.isMarkedInvulnerable,
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
        spawnReason = entity.entitySpawnReason?.name?.lowercase(),
        fromSpawner = entity.fromMobSpawner(),
        removeWhenFarAway = living?.removeWhenFarAway,
        leashHolder = living?.takeIf { it.isLeashed }?.let { runCatching { it.leashHolder.uniqueId.toString() }.getOrNull() },
        villager = (entity as? Villager)?.let { villager ->
            VillagerView(
                profession = villager.profession.key.asString(),
                type = villager.villagerType.key.asString(),
                level = villager.villagerLevel,
                experience = villager.villagerExperience,
                trades = villager.recipes.map { trade ->
                    TradeView(trade.ingredients.mapNotNull { it.view() }, trade.result.view()!!, trade.uses, trade.maxUses, trade.demand, trade.specialPrice)
                },
                home = villager.getMemory(MemoryKey.HOME)?.takeIf { coordinates }?.view(withRotation = false),
                jobSite = villager.getMemory(MemoryKey.JOB_SITE)?.takeIf { coordinates }?.view(withRotation = false),
            )
        },
        item = (entity as? Item)?.itemStack?.view(),
        pickupDelay = (entity as? Item)?.pickupDelay,
        thrower = (entity as? Item)?.thrower?.toString(),
    )
}
