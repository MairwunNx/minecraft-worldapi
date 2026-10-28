package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.game.biomeKey
import com.mairwunnx.worldapi.game.blockState
import com.mairwunnx.worldapi.game.blockView
import com.mairwunnx.worldapi.game.chunkLoaded
import com.mairwunnx.worldapi.game.data
import com.mairwunnx.worldapi.game.loadBlockChunk
import com.mairwunnx.worldapi.game.loadChunk
import com.mairwunnx.worldapi.game.loadedChunks
import com.mairwunnx.worldapi.game.typeKey
import com.mairwunnx.worldapi.game.world
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.mixin.BiomeAccess
import com.mairwunnx.worldapi.model.BiomeView
import com.mairwunnx.worldapi.model.BlockSet
import com.mairwunnx.worldapi.model.BlockView
import com.mairwunnx.worldapi.model.ChunkPatch
import com.mairwunnx.worldapi.model.ChunkSummary
import com.mairwunnx.worldapi.model.ChunkView
import com.mairwunnx.worldapi.model.FillRequest
import com.mairwunnx.worldapi.model.FillResult
import com.mairwunnx.worldapi.model.HighestBlock
import kotlinx.coroutines.future.await
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.phys.AABB

/** Bukkit's `HeightMap` names, the game's own in upper case. */
private val HEIGHTMAPS = listOf("motion_blocking", "motion_blocking_no_leaves", "ocean_floor", "ocean_floor_wg", "world_surface", "world_surface_wg")

val blockRoutes = endpoints("Blocks", Scope.SERVER_READ) {
    get(
        "/worlds/{world}/blocks/{x}/{y}/{z}", "A block: type, state, light, biome, and contents",
        description = "A container's items (a double chest's both halves) come in `tileEntity.items`, with players:private or privacy that lets anonymous clients see inventories.",
        also = Scope.PLAYERS_PRIVATE,
    ) {
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        val inventories = seesInventories
        main { blockView(world, BlockPos(x, y, z), inventories) }
    }

    get(
        "/worlds/{world}/blocks", "Every block in a box, as a palette of states and an index for each block",
        description = "The box includes both corners; its volume can't pass limits.maxReadBlocks, and y is clipped to the world's height. " +
            "`spawns=true` also lists where monsters could spawn, as the game decides for a zombie: no block light (in the Nether, any light), " +
            "a floor with a full top that isn't glass, leaves, ice, magma, or a light source, and room to stand.",
        query = areaParams,
    ) {
        val world = main { world() }
        val box = areaBox()
        val (withBlocks, withSpawns) = (bool("blocks") ?: true) to (bool("spawns") ?: false)
        box.chunks.forEach { (cx, cz) -> loadChunk(world, cx, cz) }
        main {
            val clipped = box.clip(world.minY, world.maxHeight)
            val area = AreaBuilder(world.worldName, clipped, withBlocks, withSpawns)
            val lightLimit = world.dimensionType().monsterSpawnBlockLightLimit()
            val pos = BlockPos.MutableBlockPos()
            for (y in clipped.from.y..clipped.to.y) for (z in clipped.zs) for (x in clipped.xs) {
                pos.set(x, y, z)
                area.block(world.getBlockState(pos).data)
                if (withSpawns && (lightLimit >= 15 || world.getBrightness(LightLayer.BLOCK, pos) <= lightLimit) && spawnable(world, pos)) {
                    area.spawnable(x, y, z, world.getBrightness(LightLayer.SKY, pos))
                }
            }
            area.build()
        }
    }

    put<BlockSet, BlockView>("/worlds/{world}/blocks/{x}/{y}/{z}", "Set one block", Scope.WORLD_WRITE) { request ->
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        auditTarget = "${world.worldName} $x $y $z"
        auditDetail = request.block
        main {
            val pos = BlockPos(x, y, z)
            world.setBlock(pos, blockState(request.block), flags(request.physics))
            blockView(world, pos, true)
        }
    }

    post<FillRequest, FillResult>(
        "/worlds/{world}/blocks/fill", "Fill a box with one block", Scope.WORLD_WRITE,
        description = "The box includes both corners. Its volume can't pass limits.maxFillBlocks.",
    ) { request ->
        val world = main { world() }
        val (from, to) = request.from to request.to
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = maxOf(minOf(from.y, to.y), -2048)..minOf(maxOf(from.y, to.y), 4096)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val volume = xs.count().toLong() * ys.count() * zs.count()
        if (volume > config.limits.maxFillBlocks) badRequest("The box holds $volume blocks; the limit is ${config.limits.maxFillBlocks}", "fill_too_large")
        if (request.mode !in setOf("replace", "keep", "replace_type")) badRequest("mode must be replace, keep, or replace_type")
        if (request.mode == "replace_type" && request.replace == null) badRequest("replace_type needs replace")
        for (cx in (xs.first shr 4)..(xs.last shr 4)) for (cz in (zs.first shr 4)..(zs.last shr 4)) loadChunk(world, cx, cz)
        auditTarget = "${world.worldName} ${from.x} ${from.y} ${from.z} .. ${to.x} ${to.y} ${to.z}"
        auditDetail = request.block
        main {
            val data = blockState(request.block)
            val replace = request.replace?.let { name -> block(name) ?: badRequest("Unknown block \"$name\"") }
            if (ys.first < world.minY || ys.last >= world.maxHeight) badRequest("y must stay within ${world.minY}..${world.maxHeight - 1}")
            var changed = 0
            val pos = BlockPos.MutableBlockPos()
            for (x in xs) for (y in ys) for (z in zs) {
                pos.set(x, y, z)
                val state = world.getBlockState(pos)
                val take = when (request.mode) {
                    "keep" -> state.isAir
                    "replace_type" -> state.block == replace
                    else -> true
                }
                if (take && state != data) {
                    world.setBlock(pos, data, flags(request.physics))
                    changed++
                }
            }
            FillResult(changed, volume.toInt())
        }
    }

    get("/worlds/{world}/biomes/{x}/{y}/{z}", "The biome at a block, with its temperature and humidity") {
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val pos = BlockPos(x, y, z)
            val biome = world.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)).value()
            BiomeView(world.biomeKey(pos), (biome as BiomeAccess).`worldapi$temperature`(pos, world.seaLevel).toDouble(), downfall(biome))
        }
    }

    get(
        "/worlds/{world}/highest/{x}/{z}", "The highest block in a column",
        query = listOf(string("heightmap", "Which surface counts", values = HEIGHTMAPS)),
    ) {
        val world = main { world() }
        val x = path("x").toIntOrNull() ?: badRequest("x must be an integer")
        val z = path("z").toIntOrNull() ?: badRequest("z must be an integer")
        val map = query("heightmap")?.let { name -> Heightmap.Types.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: badRequest("Unknown heightmap") }
            ?: Heightmap.Types.MOTION_BLOCKING
        loadBlockChunk(world, x, z)
        main {
            // The heightmap names the first free block above the surface; the highest block sits under it.
            val y = world.getHeight(map, x, z) - 1
            HighestBlock(x, y, z, world.getBlockState(BlockPos(x, y, z)).typeKey)
        }
    }

    get(
        "/worlds/{world}/chunks", "Loaded chunks with entity counts",
        description = "`sort=entities` or `sort=tileEntities` puts the busiest chunks first, which is where lag usually comes from.",
        query = pageParams + string("sort", "Order: by coordinates (the default), or most entities or block entities first", values = ChunkSort.ids),
    ) {
        val limit = limit
        val cursor = cursor
        val sort = query("sort")?.let { id -> ChunkSort.entries.firstOrNull { it.id == id } ?: badRequest("sort must be one of ${ChunkSort.ids.joinToString()}") } ?: ChunkSort.COORDS
        main {
            val world = world()
            val forced = world.forceLoadedChunks
            val chunks = world.loadedChunks().map { chunk ->
                val pos = chunk.pos
                ChunkSummary(pos.x, pos.z, entitiesIn(world, pos).size, chunk.blockEntities.size, forced.contains(pos.pack()))
            }
            chunks.sortedWith(sort.order).page(limit, cursor)
        }
    }

    get("/worlds/{world}/chunks/{chunkX}/{chunkZ}", "One chunk: what lives in it, and how long players stayed") {
        val (cx, cz) = chunkCoordinates()
        val world = main { world() }
        val loaded = main { world.chunkLoaded(cx, cz) }
        if (!loaded) {
            val generated = generated(world, cx, cz)
            return@get main { ChunkView(cx, cz, loaded = false, generated = generated, forceLoaded = world.forceLoadedChunks.contains(ChunkPos.pack(cx, cz))) }
        }
        main {
            val chunk = world.getChunk(cx, cz)
            val pos = chunk.pos
            ChunkView(
                cx, cz, loaded = true, generated = true, forceLoaded = world.forceLoadedChunks.contains(pos.pack()),
                inhabitedTimeTicks = chunk.inhabitedTime,
                slimeChunk = WorldgenRandom.seedSlimeChunk(cx, cz, world.seed, 987234911L).nextInt(10) == 0,
                entities = entitiesIn(world, pos).groupingBy { BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString() }.eachCount().toSortedMap(),
                tileEntities = chunk.blockEntities.values.groupingBy { BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(it.type).toString() }.eachCount().toSortedMap(),
            )
        }
    }

    patch<ChunkPatch, ChunkView>("/worlds/{world}/chunks/{chunkX}/{chunkZ}", "Keep a chunk loaded, or let it go", Scope.WORLD_WRITE) { patch ->
        val (cx, cz) = chunkCoordinates()
        auditTarget = "${path("world")} $cx $cz"
        val world = main { world() }
        main { world.setChunkForced(cx, cz, patch.forceLoaded) }
        val generated = generated(world, cx, cz)
        main { ChunkView(cx, cz, loaded = world.chunkLoaded(cx, cz), generated = generated, forceLoaded = world.forceLoadedChunks.contains(ChunkPos.pack(cx, cz))) }
    }
}

/** How wet a biome is, as Bukkit's `World.getHumidity`: the game keeps it private, and the biome's codec says it. */
private fun downfall(biome: net.minecraft.world.level.biome.Biome): Double =
    net.minecraft.world.level.biome.Biome.NETWORK_CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, biome).result().orElse(null)
        ?.asJsonObject?.get("downfall")?.asDouble ?: 0.0

/** Bukkit's `setBlockData` flags: with physics, neighbors react and the client hears; without, only the client. */
private fun flags(physics: Boolean) = if (physics) Block.UPDATE_ALL else Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

private fun block(name: String): Block? = Identifier.tryParse(name.lowercase())?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }

/** Whether a chunk exists, loaded or on disk. */
private suspend fun ModCall.generated(world: ServerLevel, cx: Int, cz: Int): Boolean =
    main { world.chunkLoaded(cx, cz) } || runCatching { world.chunkSource.chunkMap.read(ChunkPos(cx, cz)).await() }.getOrNull()?.isPresent == true

/** The entities in a loaded chunk, as Bukkit's `Chunk.getEntities` lists them. Server thread. */
private fun entitiesIn(world: ServerLevel, pos: ChunkPos) =
    world.getEntities(null as net.minecraft.world.entity.Entity?, AABB(pos.minBlockX.toDouble(), world.minY.toDouble(), pos.minBlockZ.toDouble(), pos.maxBlockX + 1.0, world.maxHeight.toDouble(), pos.maxBlockZ + 1.0)) { true }

internal enum class ChunkSort(val id: String, val order: Comparator<ChunkSummary>) {
    COORDS("coords", compareBy({ it.x }, { it.z })),
    ENTITIES("entities", compareByDescending<ChunkSummary> { it.entities }.thenBy { it.x }.thenBy { it.z }),
    TILE_ENTITIES("tileEntities", compareByDescending<ChunkSummary> { it.tileEntities }.thenBy { it.x }.thenBy { it.z }),
    ;

    companion object {
        val ids = entries.map { it.id }
    }
}

/** Whether a zombie could spawn at [feet], light aside, by the game's own rules for its floor and the room it needs. Server thread. */
private fun spawnable(world: ServerLevel, feet: BlockPos): Boolean {
    fun clear(pos: BlockPos) = world.getBlockState(pos).let { NaturalSpawner.isValidEmptySpawnBlock(world, pos, it, it.fluidState, EntityTypes.ZOMBIE) }
    val floor = feet.below()
    return clear(feet) && clear(feet.above()) && world.getBlockState(floor).isValidSpawn(world, floor, EntityTypes.ZOMBIE)
}

internal fun ModCall.coordinates(): Triple<Int, Int, Int> {
    fun int(name: String) = path(name).toIntOrNull() ?: badRequest("$name must be an integer")
    val x = int("x")
    val y = int("y")
    val z = int("z")
    if (x !in -30_000_000..30_000_000 || z !in -30_000_000..30_000_000) badRequest("x and z must be within the world")
    return Triple(x, y, z)
}

private fun ModCall.chunkCoordinates(): Pair<Int, Int> {
    val x = path("chunkX").toIntOrNull() ?: badRequest("chunkX must be an integer")
    val z = path("chunkZ").toIntOrNull() ?: badRequest("chunkZ must be an integer")
    return x to z
}
