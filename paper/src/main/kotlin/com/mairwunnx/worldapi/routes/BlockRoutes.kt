package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.PaperCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.loadBlockChunk
import com.mairwunnx.worldapi.bukkit.loadChunk
import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.bukkit.views
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.BiomeView
import com.mairwunnx.worldapi.model.BlockSet
import com.mairwunnx.worldapi.model.BlockView
import com.mairwunnx.worldapi.model.ChunkPatch
import com.mairwunnx.worldapi.model.ChunkSummary
import com.mairwunnx.worldapi.model.ChunkView
import com.mairwunnx.worldapi.model.FillRequest
import com.mairwunnx.worldapi.model.FillResult
import com.mairwunnx.worldapi.model.HighestBlock
import com.mairwunnx.worldapi.model.Light
import com.mairwunnx.worldapi.model.TileEntityView
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.Jukebox
import org.bukkit.block.Sign
import org.bukkit.block.Skull
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.Nameable

val blockRoutes = endpoints("Blocks", Scope.SERVER_READ) {
    get(
        "/worlds/{world}/blocks/{x}/{y}/{z}", "A block: type, state, light, biome, and contents",
        description = "A container's items (a double chest's both halves) come in `tileEntity.items`, with players:private or privacy that lets anonymous clients see inventories.",
        also = Scope.PLAYERS_PRIVATE,
    ) {
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        val inventories = seesInventories
        main { blockView(world.getBlockAt(x, y, z), inventories) }
    }

    put<BlockSet, BlockView>("/worlds/{world}/blocks/{x}/{y}/{z}", "Set one block", Scope.WORLD_WRITE) { request ->
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        auditTarget = "${world.name} $x $y $z"
        auditDetail = request.block
        main {
            val block = world.getBlockAt(x, y, z)
            block.setBlockData(blockData(request.block), request.physics)
            blockView(block, true)
        }
    }

    post<FillRequest, FillResult>(
        "/worlds/{world}/blocks/fill", "Fill a box with one block", Scope.WORLD_WRITE,
        description = "The box includes both corners. Its volume can't pass limits.maxFillBlocks.",
    ) { request ->
        val world = world()
        val (from, to) = request.from to request.to
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = maxOf(minOf(from.y, to.y), -2048)..minOf(maxOf(from.y, to.y), 4096)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val volume = xs.count().toLong() * ys.count() * zs.count()
        if (volume > config.limits.maxFillBlocks) badRequest("The box holds $volume blocks; the limit is ${config.limits.maxFillBlocks}", "fill_too_large")
        if (request.mode !in setOf("replace", "keep", "replace_type")) badRequest("mode must be replace, keep, or replace_type")
        if (request.mode == "replace_type" && request.replace == null) badRequest("replace_type needs replace")
        for (cx in (xs.first shr 4)..(xs.last shr 4)) for (cz in (zs.first shr 4)..(zs.last shr 4)) loadChunk(world, cx, cz)
        auditTarget = "${world.name} ${from.x} ${from.y} ${from.z} .. ${to.x} ${to.y} ${to.z}"
        auditDetail = request.block
        main {
            val data = blockData(request.block)
            val replace = request.replace?.let { Material.matchMaterial(it) ?: badRequest("Unknown block \"$it\"") }
            if (ys.first < world.minHeight || ys.last >= world.maxHeight) badRequest("y must stay within ${world.minHeight}..${world.maxHeight - 1}")
            var changed = 0
            for (x in xs) for (y in ys) for (z in zs) {
                val block = world.getBlockAt(x, y, z)
                val take = when (request.mode) {
                    "keep" -> block.type.isAir
                    "replace_type" -> block.type == replace
                    else -> true
                }
                if (take && block.blockData != data) {
                    block.setBlockData(data, request.physics)
                    changed++
                }
            }
            FillResult(changed, volume.toInt())
        }
    }

    get("/worlds/{world}/biomes/{x}/{y}/{z}", "The biome at a block, with its temperature and humidity") {
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main { BiomeView(world.getBiome(x, y, z).key.asString(), world.getTemperature(x, y, z), world.getHumidity(x, y, z)) }
    }

    get(
        "/worlds/{world}/highest/{x}/{z}", "The highest block in a column",
        query = listOf(string("heightmap", "Which surface counts", values = HeightMap.entries.map { it.name.lowercase() })),
    ) {
        val world = world()
        val x = path("x").toIntOrNull() ?: badRequest("x must be an integer")
        val z = path("z").toIntOrNull() ?: badRequest("z must be an integer")
        val map = query("heightmap")?.let { name -> HeightMap.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: badRequest("Unknown heightmap") }
            ?: HeightMap.MOTION_BLOCKING
        loadBlockChunk(world, x, z)
        main {
            val block = world.getHighestBlockAt(x, z, map)
            HighestBlock(block.x, block.y, block.z, block.type.key.asString())
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
            val chunks = world().loadedChunks.map { ChunkSummary(it.x, it.z, it.entities.size, it.tileEntities.size, it.isForceLoaded) }
            chunks.sortedWith(sort.order).page(limit, cursor)
        }
    }

    get("/worlds/{world}/chunks/{chunkX}/{chunkZ}", "One chunk: what lives in it, and how long players stayed") {
        val (cx, cz) = chunkCoordinates()
        main {
            val world = world()
            if (!world.isChunkLoaded(cx, cz)) {
                return@main ChunkView(cx, cz, loaded = false, generated = world.isChunkGenerated(cx, cz), forceLoaded = world.isChunkForceLoaded(cx, cz))
            }
            val chunk = world.getChunkAt(cx, cz)
            ChunkView(
                cx, cz, loaded = true, generated = true, forceLoaded = chunk.isForceLoaded,
                inhabitedTimeTicks = chunk.inhabitedTime,
                slimeChunk = chunk.isSlimeChunk,
                entities = chunk.entities.groupingBy { it.type.key.asString() }.eachCount().toSortedMap(),
                tileEntities = chunk.tileEntities.groupingBy { it.type.key.asString() }.eachCount().toSortedMap(),
            )
        }
    }

    patch<ChunkPatch, ChunkView>("/worlds/{world}/chunks/{chunkX}/{chunkZ}", "Keep a chunk loaded, or let it go", Scope.WORLD_WRITE) { patch ->
        val (cx, cz) = chunkCoordinates()
        auditTarget = "${path("world")} $cx $cz"
        main {
            val world = world()
            world.setChunkForceLoaded(cx, cz, patch.forceLoaded)
            ChunkView(cx, cz, loaded = world.isChunkLoaded(cx, cz), generated = world.isChunkGenerated(cx, cz), forceLoaded = world.isChunkForceLoaded(cx, cz))
        }
    }
}

internal enum class ChunkSort(val id: String, val order: Comparator<ChunkSummary>) {
    COORDS("coords", compareBy({ it.x }, { it.z })),
    ENTITIES("entities", compareByDescending<ChunkSummary> { it.entities }.thenBy { it.x }.thenBy { it.z }),
    TILE_ENTITIES("tileEntities", compareByDescending<ChunkSummary> { it.tileEntities }.thenBy { it.x }.thenBy { it.z }),
    ;

    companion object {
        val ids = entries.map { it.id }
    }
}

internal fun PaperCall.coordinates(): Triple<Int, Int, Int> {
    fun int(name: String) = path(name).toIntOrNull() ?: badRequest("$name must be an integer")
    val x = int("x")
    val y = int("y")
    val z = int("z")
    if (x !in -30_000_000..30_000_000 || z !in -30_000_000..30_000_000) badRequest("x and z must be within the world")
    return Triple(x, y, z)
}

private fun PaperCall.chunkCoordinates(): Pair<Int, Int> {
    val x = path("chunkX").toIntOrNull() ?: badRequest("chunkX must be an integer")
    val z = path("chunkZ").toIntOrNull() ?: badRequest("chunkZ must be an integer")
    return x to z
}

fun blockData(text: String): BlockData {
    val data = runCatching { org.bukkit.Bukkit.createBlockData(text.lowercase()) }.getOrElse { badRequest("Can't read block \"$text\": ${it.message}", "invalid_block") }
    if (!data.material.isBlock) badRequest("\"$text\" isn't a block", "invalid_block")
    return data
}

fun blockView(block: Block, inventories: Boolean): BlockView {
    val world: World = block.world
    return BlockView(
        world = world.name,
        x = block.x, y = block.y, z = block.z,
        type = block.type.key.asString(),
        data = block.blockData.asString,
        light = Light(block.lightFromSky.toInt(), block.lightFromBlocks.toInt(), block.lightLevel.toInt()),
        biome = block.biome.key.asString(),
        solid = block.isSolid,
        liquid = block.isLiquid,
        passable = block.isPassable,
        power = block.blockPower,
        tileEntity = tileEntity(block, inventories),
    )
}

private fun tileEntity(block: Block, inventories: Boolean): TileEntityView? {
    val state = block.getState(false)
    if (state !is org.bukkit.block.TileState) return null
    val container = state as? Container
    val sign = state as? Sign
    return TileEntityView(
        kind = state.javaClass.interfaces.firstOrNull { it.packageName == "org.bukkit.block" }?.simpleName ?: state.javaClass.simpleName,
        customName = (state as? Nameable)?.customName()?.plain(),
        items = container?.takeIf { inventories }?.inventory?.views(),
        size = container?.inventory?.size,
        frontLines = sign?.getSide(Side.FRONT)?.lines()?.map { it.plain() },
        backLines = sign?.getSide(Side.BACK)?.lines()?.map { it.plain() },
        spawnedType = (state as? CreatureSpawner)?.spawnedType?.key?.asString(),
        owner = (state as? Skull)?.playerProfile?.name,
        record = (state as? Jukebox)?.record?.takeIf { !it.type.isAir }?.type?.key?.asString(),
    )
}
