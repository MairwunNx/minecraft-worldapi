package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.number
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.game.chunkLoaded
import com.mairwunnx.worldapi.game.effectViews
import com.mairwunnx.worldapi.game.hides
import com.mairwunnx.worldapi.game.loadBlockChunk
import com.mairwunnx.worldapi.game.plain
import com.mairwunnx.worldapi.game.text
import com.mairwunnx.worldapi.game.toTarget
import com.mairwunnx.worldapi.game.uuidOrNull
import com.mairwunnx.worldapi.game.view
import com.mairwunnx.worldapi.game.world
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.mixin.EntityAccess
import com.mairwunnx.worldapi.model.ChunkLoad
import com.mairwunnx.worldapi.model.EntityCensus
import com.mairwunnx.worldapi.model.EntityPatch
import com.mairwunnx.worldapi.model.EntitySummary
import com.mairwunnx.worldapi.model.EntityView
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.model.Position
import com.mairwunnx.worldapi.model.RemovedEntities
import com.mairwunnx.worldapi.model.SpawnRequest
import com.mairwunnx.worldapi.model.Spawned
import com.mairwunnx.worldapi.model.TradeView
import com.mairwunnx.worldapi.model.VillagerView
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.round

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
        val types = query("type")?.split(',')?.map { entityType(it.trim()) }?.toSet()
        val area = area()
        val name = query("name")
        val chunk = query("chunk")?.split(',')?.map { it.trim().toIntOrNull() ?: badRequest("chunk must be chunkX,chunkZ") }
            ?.takeIf { it.size == 2 } ?: query("chunk")?.let { badRequest("chunk must be chunkX,chunkZ") }
        val coordinates = seesCoordinates
        val limit = if (int("limit") == null) minOf(25, limit) else limit
        val cursor = cursor
        main {
            val world = world()
            val origin = area?.let { Vec3(it.x, it.y, it.z) }
            val found: Collection<Entity> = when {
                chunk != null -> if (world.chunkLoaded(chunk[0], chunk[1])) inChunk(world, chunk[0], chunk[1]) else emptyList()
                origin != null -> nearby(world, origin, area.radius)
                else -> world.allEntities.toList()
            }
            found.asSequence()
                .filter { types == null || it.type in types }
                .filter { name == null || (it.customName?.plain() ?: (it as? ServerPlayer)?.gameProfile?.name())?.contains(name, ignoreCase = true) == true }
                .filter { origin == null || chunk == null || it.position().distanceToSqr(origin) <= area.radius * area.radius }
                .filterNot { it is ServerPlayer && hides(it) }
                .sortedWith(if (origin != null) compareBy { it.position().distanceToSqr(origin) } else compareBy { it.uuid })
                .map { summary(it, coordinates) }
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
        val types = query("type")?.split(',')?.map { entityType(it.trim()) }?.toSet()
        val area = area()
        main {
            val world = world()
            val entities = (area?.let { nearby(world, Vec3(it.x, it.y, it.z), it.radius) } ?: world.allEntities.toList())
                .filter { types == null || it.type in types }
                .filterNot { it is ServerPlayer && hides(it) }
            census(world, entities)
        }
    }

    get("/worlds/{world}/entities/{uuid}", "One entity in detail") {
        val coordinates = seesCoordinates
        val inventories = seesInventories
        main { entityView(entity(), coordinates, inventories) }
    }

    patch<EntityPatch, EntityView>("/worlds/{world}/entities/{uuid}", "Change an entity: name, flags, health, position", Scope.WORLD_WRITE) { patch ->
        main {
            val location = patch.location?.toTarget(api.server)
            val entity = entity()
            if (entity is ServerPlayer) badRequest("Use the player endpoints for players")
            auditTarget = "${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path} ${entity.uuid}"
            patch.customName?.let { entity.customName = text(it, patch.format, 256) }
            patch.customNameVisible?.let { entity.isCustomNameVisible = it }
            patch.glowing?.let { entity.setGlowingTag(it) }
            patch.silent?.let { entity.isSilent = it }
            patch.invulnerable?.let { entity.setPermanentlyInvulnerable(it) }
            patch.gravity?.let { entity.isNoGravity = !it }
            patch.persistent?.let { (entity as? Mob)?.let { mob -> (mob as EntityAccess.Persistence).`worldapi$setPersistent`(it) } }
            patch.fireTicks?.let { entity.remainingFireTicks = it }
            patch.velocity?.let { entity.deltaMovement = Vec3(it.x, it.y, it.z); entity.needsSync = true }
            patch.addTags.forEach { entity.addTag(it) }
            patch.removeTags.forEach { entity.removeTag(it) }
            if (entity is LivingEntity) {
                patch.ai?.let { (entity as? Mob)?.isNoAi = !it }
                patch.health?.let { entity.health = it.coerceIn(0.0, entity.getAttributeValue(Attributes.MAX_HEALTH)).toFloat() }
            } else if (patch.ai != null || patch.health != null) {
                badRequest("Only living entities have AI and health")
            }
            location?.let { entity.teleportTo(it.level, it.pos.x, it.pos.y, it.pos.z, emptySet(), it.yaw, it.pitch, true) }
            // A teleport into another world leaves a copy there; read that one.
            val now = location?.level?.getEntity(entity.uuid) ?: entity
            entityView(now, coordinates = true, inventories = true)
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
        val types = query("type")!!.split(',').map { entityType(it.trim()) }.toSet()
        if (EntityTypes.PLAYER in types) badRequest("Players can't be removed; kick them instead")
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
            auditTarget = "${world.worldName} ${types.joinToString(",") { BuiltInRegistries.ENTITY_TYPE.getKey(it).path }}"
            val origin = center[0]?.let { Vec3(it, center[1]!!, center[2]!!) }
            val nearby: Collection<Entity> = if (origin != null) nearby(world, origin, radius ?: 32.0) else world.allEntities.toList()
            val matching = nearby.filter { it.type in types && removable(it, includeNamed) }
                .sortedBy { e -> origin?.let { e.position().distanceToSqr(it) } ?: 0.0 }
            val chosen = matching.take(max)
            auditDetail = if (dryRun) "dry run, ${matching.size} matching" else "${chosen.size} removed"
            if (!dryRun) chosen.forEach(Entity::discard)
            val removed = if (dryRun) 0 else chosen.size
            RemovedEntities(
                matched = matching.size,
                removed = removed,
                remaining = matching.size - removed,
                dryRun = dryRun,
                byType = chosen.groupingBy { BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString() }.eachCount().toSortedMap(),
            )
        }
    }

    delete("/worlds/{world}/entities/{uuid}", "Remove an entity", Scope.WORLD_WRITE) {
        main {
            val entity = entity()
            if (entity is ServerPlayer) badRequest("Players can't be removed; kick them instead")
            auditTarget = "${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path} ${entity.uuid}"
            entity.discard()
        }
    }

    post<SpawnRequest, Spawned>(
        "/worlds/{world}/entities", "Spawn entities", Scope.WORLD_WRITE,
        description = "Spawns up to limits.maxSpawnCount at once.",
        status = io.ktor.http.HttpStatusCode.Created,
    ) { request ->
        if (request.count !in 1..config.limits.maxSpawnCount) badRequest("count must be between 1 and ${config.limits.maxSpawnCount}")
        val target = main { request.location.toTarget(api.server, fallback = world()) }
        loadBlockChunk(target.level, target.block.x, target.block.z)
        auditTarget = "${request.count} × ${request.type}"
        main {
            val type = entityType(request.type)
            if (!type.canSummon() || type == EntityTypes.PLAYER) badRequest("${request.type} can't be spawned")
            val level = target.level
            val uuids = (1..request.count).map {
                val entity = type.create(level, EntitySpawnReason.COMMAND) ?: badRequest("${request.type} can't be spawned")
                entity.snapTo(target.pos.x, target.pos.y, target.pos.z, target.yaw, target.pitch)
                // As Bukkit's spawnEntity: a mob picks its gear and variant as a natural one would.
                (entity as? Mob)?.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(target.pos)), EntitySpawnReason.COMMAND, null)
                request.customName?.let { entity.customName = text(it, request.format, 256) }
                request.glowing?.let { entity.setGlowingTag(it) }
                request.persistent?.let { persistent -> (entity as? Mob)?.let { (it as EntityAccess.Persistence).`worldapi$setPersistent`(persistent) } }
                request.ai?.let { (entity as? Mob)?.isNoAi = !it }
                level.addFreshEntityWithPassengers(entity)
                entity.uuid.toString()
            }
            Spawned(uuids)
        }
    }
}

/** An entity type by `zombie` or `minecraft:zombie`, or 400 `unknown_entity`. */
internal fun entityType(value: String): EntityType<*> {
    val id = Identifier.tryParse(value.lowercase()) ?: badRequest("\"$value\" isn't a valid entity")
    return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: badRequest("Unknown entity \"$value\"", "unknown_entity")
}

/** Tamed as Bukkit's `Tameable` reads it: wolves, cats, parrots, and the like, and horses and their kin. */
internal fun tamed(entity: Entity) = (entity as? TamableAnimal)?.isTame == true || (entity as? AbstractHorse)?.isTamed == true

/** Players never go; named mobs and tamed pets only when asked for, since someone cares about them. */
internal fun removable(entity: Entity, includeNamed: Boolean): Boolean = when {
    entity is ServerPlayer -> false
    includeNamed -> true
    entity.customName != null -> false
    tamed(entity) -> false
    else -> true
}

private fun ModCall.entity(): Entity {
    val id = path("uuid")
    val uuid = uuidOrNull(id) ?: badRequest("\"$id\" isn't a UUID")
    // A vanished player is as absent here as anywhere else.
    return world().getEntity(uuid)?.takeUnless { it is ServerPlayer && hides(it) } ?: notFound("entity_not_found", "No loaded entity $id in ${path("world")}")
}

private class Area(val x: Double, val y: Double, val z: Double, val radius: Double)

/** x, y, z, and radius from the query, all or none; radius defaults to 32 and stays within limits.maxEntityRadius. */
private fun ModCall.area(): Area? {
    val center = listOf(double("x"), double("y"), double("z"))
    val radius = double("radius")
    if (center.any { it != null } && center.any { it == null }) badRequest("Give all of x, y, and z, or none")
    if (radius != null && center[0] == null) badRequest("radius needs x, y, and z")
    if (radius != null && (radius <= 0 || radius > config.limits.maxEntityRadius)) badRequest("radius must be between 0 and ${config.limits.maxEntityRadius}")
    return center[0]?.let { Area(it, center[1]!!, center[2]!!, radius ?: 32.0) }
}

private fun nearby(world: ServerLevel, origin: Vec3, radius: Double): List<Entity> =
    world.getEntities(null as Entity?, AABB.ofSize(origin, radius * 2, radius * 2, radius * 2)) { it.position().distanceToSqr(origin) <= radius * radius }

private fun inChunk(world: ServerLevel, cx: Int, cz: Int): List<Entity> =
    world.getEntities(null as Entity?, AABB((cx shl 4).toDouble(), world.minY.toDouble(), (cz shl 4).toDouble(), ((cx shl 4) + 16).toDouble(), world.maxHeight.toDouble(), ((cz shl 4) + 16).toDouble())) { true }

private val Entity.key: String get() = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()

private fun census(world: ServerLevel, entities: List<Entity>): EntityCensus {
    val byType = entities.groupingBy { it.key }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    val items = entities.filterIsInstance<ItemEntity>()
    val chunks = entities.groupBy { it.blockPosition().x shr 4 to (it.blockPosition().z shr 4) }.entries.sortedByDescending { it.value.size }.take(10).map { (at, inside) ->
        val (cx, cz) = at
        val tiles = if (world.chunkLoaded(cx, cz)) world.getChunk(cx, cz).blockEntities.values.toList() else emptyList()
        ChunkLoad(cx, cz, inside.size, tiles.size, tiles.count { it is HopperBlockEntity }, topTypes(inside.map { it.key }, 3))
    }
    return EntityCensus(
        world = world.worldName,
        total = entities.size,
        byType = byType.take(20).associateTo(LinkedHashMap()) { it.key to it.value },
        otherTypes = byType.drop(20).sumOf { it.value },
        droppedItems = items.size,
        droppedItemCount = items.sumOf { it.item.count },
        villagers = entities.count { it is Villager },
        named = entities.count { it !is ServerPlayer && it.customName != null },
        tamed = entities.count(::tamed),
        players = entities.count { it is ServerPlayer },
        topChunks = chunks,
    )
}

private fun summary(entity: Entity, coordinates: Boolean) = EntitySummary(
    uuid = entity.uuid.toString(),
    type = entity.key,
    name = entity.customName?.plain() ?: (entity as? ServerPlayer)?.gameProfile?.name(),
    location = if (coordinates) entity.view() else null,
    health = (entity as? LivingEntity)?.health?.toDouble(),
)

/** Bukkit's names for equipment slots, in Bukkit's order. */
private val SLOTS = listOf(
    EquipmentSlot.MAINHAND to "hand", EquipmentSlot.OFFHAND to "off_hand", EquipmentSlot.FEET to "feet", EquipmentSlot.LEGS to "legs",
    EquipmentSlot.CHEST to "chest", EquipmentSlot.HEAD to "head", EquipmentSlot.BODY to "body", EquipmentSlot.SADDLE to "saddle",
)

private fun Double.short() = round(this * 100) / 100

/** A player's equipment is part of their inventory, so it shows only with [inventories]. */
fun entityView(entity: Entity, coordinates: Boolean, inventories: Boolean): EntityView {
    val living = entity as? LivingEntity
    val server = entity.level().server!!
    val registries = server.registryAccess()
    val equipment = living?.takeIf { inventories || it !is ServerPlayer }?.let { gear ->
        SLOTS.mapNotNull { (slot, name) -> runCatching { gear.getItemBySlot(slot) }.getOrNull()?.view(registries = registries)?.let { name to it } }.toMap()
    }
    val mob = entity as? Mob
    val motion = entity.deltaMovement
    return EntityView(
        uuid = entity.uuid.toString(),
        type = entity.key,
        name = entity.name.plain(),
        customName = entity.customName?.plain(),
        customNameVisible = entity.isCustomNameVisible,
        location = if (coordinates) entity.view() else null,
        velocity = Position(motion.x.short(), motion.y.short(), motion.z.short()),
        onGround = entity.onGround(),
        ticksLived = entity.tickCount,
        fireTicks = entity.remainingFireTicks,
        glowing = entity.hasGlowingTag(),
        silent = entity.isSilent,
        invulnerable = entity.isPermanentlyInvulnerable,
        gravity = !entity.isNoGravity,
        persistent = mob?.isPersistenceRequired ?: true,
        tags = entity.entityTags().sorted(),
        passengers = entity.passengers.map { it.uuid.toString() },
        vehicle = entity.vehicle?.uuid?.toString(),
        health = living?.health?.toDouble(),
        maxHealth = living?.getAttributeValue(Attributes.MAX_HEALTH),
        ai = mob?.let { !it.isNoAi },
        target = mob?.target?.uuid?.toString(),
        adult = (entity as? AgeableMob)?.let { !it.isBaby },
        tamed = if (entity is TamableAnimal || entity is AbstractHorse) tamed(entity) else null,
        owner = (entity as? OwnableEntity)?.owner?.let { owner -> PlayerRef(owner.uuid.toString(), (owner as? ServerPlayer)?.gameProfile?.name() ?: owner.name.plain()) },
        equipment = equipment?.takeIf { it.isNotEmpty() },
        effects = living?.effectViews(),
        spawnReason = null,
        fromSpawner = null,
        removeWhenFarAway = mob?.let { !it.isPersistenceRequired && !it.requiresCustomPersistence() },
        leashHolder = (entity as? Leashable)?.takeIf { it.isLeashed }?.leashHolder?.uuid?.toString(),
        villager = (entity as? Villager)?.let { villager ->
            val data = villager.villagerData
            VillagerView(
                profession = data.profession().unwrapKey().map { it.identifier().toString() }.orElse("unknown"),
                type = data.type().unwrapKey().map { it.identifier().toString() }.orElse("unknown"),
                level = data.level(),
                experience = villager.villagerXp,
                trades = villager.offers.map { trade ->
                    TradeView(
                        listOfNotNull(trade.baseCostA.view(registries = registries), trade.costB.takeUnless { it.isEmpty }?.view(registries = registries)),
                        trade.result.view(registries = registries)!!, trade.uses, trade.maxUses, trade.demand, trade.specialPriceDiff,
                    )
                },
            )
        },
        item = (entity as? ItemEntity)?.item?.view(registries = registries),
        pickupDelay = (entity as? ItemEntity)?.let { (it as EntityAccess.Dropped).`worldapi$pickupDelay`() },
        thrower = (entity as? ItemEntity)?.let { (it as EntityAccess.Dropped).`worldapi$thrower`()?.uuid?.toString() },
    )
}
