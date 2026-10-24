package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.game.chunkLoaded
import com.mairwunnx.worldapi.game.world
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.model.Found
import com.mairwunnx.worldapi.model.LocateResult
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import java.util.PriorityQueue
import kotlin.math.round
import kotlin.math.sqrt

val searchRoutes = endpoints("Worlds", Scope.SERVER_READ) {
    get(
        "/worlds/{world}/locate", "Find the nearest blocks, biomes, or structures",
        description = "`kind=block` looks through chunks that are already loaded and never loads more (`unloadedChunks` says how many it skipped), " +
            "reading copies of them off the server thread within limits.maxBlockSearchRadius. It needs a token whatever the anonymous settings say: " +
            "it would show ores through walls. `kind=biome` asks the world generator, so it reaches unexplored land, within limits.maxBiomeSearchRadius blocks. " +
            "`kind=structure` takes keys or prefixes (`village` matches every village) within limits.maxStructureRadius chunks. " +
            "Biomes and structures give the nearest of each target, nearest first. Without x and z, biomes and structures search from the world spawn. " +
            "Anonymous clients may locate only when they may see coordinates.",
        query = listOf(
            string("kind", "What to find", required = true, values = LOCATE_KINDS),
            string("target", "Comma-separated keys: blocks or `#tags` (`minecraft:diamond_ore,#minecraft:logs`), biomes, or structures and prefixes", required = true),
            integer("x", "Center X; required for blocks"),
            integer("y", "Center Y; required for blocks, 64 by default for biomes"),
            integer("z", "Center Z; required for blocks"),
            integer("radius", "In blocks (32 by default) for blocks and biomes (1024 by default), in chunks for structures (limits.maxStructureRadius by default)"),
            boolean("unexplored", "Structures only: only ones nobody has found yet, as treasure maps do"),
            integer("limit", "How many results, nearest first (10 by default, at most 100)"),
        ),
        also = Scope.PLAYERS_PRIVATE,
    ) {
        val kind = query("kind")!!.lowercase()
        if (kind !in LOCATE_KINDS) badRequest("kind must be one of ${LOCATE_KINDS.joinToString()}")
        val target = query("target")!!
        if (principal.anonymous && kind == "block") {
            throw ApiException(HttpStatusCode.Unauthorized, "token_required", "Block search needs a token: it would show ores through walls")
        }
        if (!seesCoordinates) throw ApiException(HttpStatusCode.Forbidden, "private", "Coordinates are hidden from anonymous clients")
        val limits = config.limits
        val limit = int("limit") ?: 10
        if (limit !in 1..100) badRequest("limit must be between 1 and 100")
        listOfNotNull(int("x"), int("z")).forEach { if (it !in -30_000_000..30_000_000) badRequest("x and z must be within the world") }
        val world = main { world() }
        val spawn = main { if (world.dimension() == api.server.respawnData.dimension()) api.server.respawnData.pos() else world.levelData.respawnData.pos() }
        val x = int("x") ?: if (kind == "block") badRequest("Block search needs x, y, and z") else spawn.x
        val z = int("z") ?: if (kind == "block") badRequest("Block search needs x, y, and z") else spawn.z
        val y = int("y") ?: if (kind == "block") badRequest("Block search needs x, y, and z") else 64
        when (kind) {
            "block" -> {
                val radius = int("radius") ?: minOf(32, limits.maxBlockSearchRadius)
                if (radius !in 1..limits.maxBlockSearchRadius) badRequest("radius must be between 1 and ${limits.maxBlockSearchRadius} for blocks")
                val center = BlockPosition(x, y, z)
                val (snapshots, skipped, heights) = main {
                    val types = blocks(target)
                    val loaded = mutableListOf<Snapshot>()
                    var missing = 0
                    for (cx in ((x - radius) shr 4)..((x + radius) shr 4)) for (cz in ((z - radius) shr 4)..((z + radius) shr 4)) {
                        if (!chunkInRange(cx, cz, x, z, radius)) continue
                        if (world.chunkLoaded(cx, cz)) loaded += Snapshot(cx, cz, world.minY, world.getChunk(cx, cz).sections.map { it.states.copy() }) else missing++
                    }
                    Triple(loaded to types, missing, world.minY until world.maxHeight)
                }
                val (found, matched) = withContext(Dispatchers.Default) { scan(snapshots.first, snapshots.second, center, radius, heights, limit) }
                LocateResult(world.worldName, kind, x, y, z, found, matched, snapshots.first.size, skipped)
            }
            "biome" -> {
                val radius = int("radius") ?: minOf(1024, limits.maxBiomeSearchRadius)
                if (radius !in 1..limits.maxBiomeSearchRadius) badRequest("radius must be between 1 and ${limits.maxBiomeSearchRadius} for biomes")
                val found = main {
                    val lookup = world.registryAccess().lookupOrThrow(Registries.BIOME)
                    target.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
                        val id = Identifier.tryParse(name.lowercase()) ?: badRequest("\"$name\" isn't a valid biome")
                        lookup.get(ResourceKey.create(Registries.BIOME, id)).orElse(null) ?: badRequest("Unknown biome \"$name\"", "unknown_biome")
                    }.distinct().mapNotNull { biome ->
                        world.findClosestBiome3d({ it.`is`(biome.key()) }, BlockPos(x, y, z), radius, BIOME_STEP, 64)?.first?.let { at ->
                            Found(biome.key().identifier().toString(), at.x, at.y, at.z, distance(at.x - x, 0, at.z - z))
                        }
                    }
                }
                LocateResult(world.worldName, kind, x, y, z, found.sortedBy { it.distance }.take(limit))
            }
            else -> {
                val radius = int("radius") ?: limits.maxStructureRadius
                if (radius !in 1..limits.maxStructureRadius) badRequest("radius must be between 1 and ${limits.maxStructureRadius} chunks for structures")
                val unexplored = bool("unexplored") ?: false
                val found = main {
                    val registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                    val names = target.split(',').map { it.trim().lowercase().removePrefix("minecraft:") }.filter { it.isNotEmpty() }
                    val candidates = registry.listElements().toList().filter { holder -> names.any { holder.key().identifier().path == it || holder.key().identifier().path.startsWith(it) } }
                    if (candidates.isEmpty()) badRequest("No structure matches \"$target\"", "unknown_structure")
                    candidates.mapNotNull { structure ->
                        world.chunkSource.generator.findNearestMapStructure(world, HolderSet.direct(structure), BlockPos(x, 64, z), radius, unexplored)?.first?.let { at ->
                            Found(structure.key().identifier().toString(), at.x, at.y, at.z, distance(at.x - x, 0, at.z - z))
                        }
                    }
                }
                LocateResult(world.worldName, kind, x, null, z, found.sortedBy { it.distance }.take(limit))
            }
        }
    }
}

private const val BIOME_STEP = 32

private val LOCATE_KINDS = listOf("block", "biome", "structure")

/** A copy of a loaded chunk's blocks, safe to read off the server thread. */
internal class Snapshot(val x: Int, val z: Int, private val minY: Int, private val sections: List<PalettedContainer<BlockState>>) {
    fun block(lx: Int, y: Int, lz: Int): Block = sections.getOrNull((y - minY) shr 4)?.get(lx, (y - minY) and 15, lz)?.block ?: Blocks.AIR
}

/** Block keys and `#tags`, as one set of block types. Air can't be searched for. */
private fun ModCall.blocks(text: String): Set<Block> {
    val types = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { name ->
        if (name.startsWith("#")) {
            val id = Identifier.tryParse(name.removePrefix("#").lowercase()) ?: badRequest("\"$name\" isn't a valid tag")
            BuiltInRegistries.BLOCK.get(TagKey.create(Registries.BLOCK, id)).map { set -> set.map { it.value() } }.orElse(null)
                ?: badRequest("Unknown block tag \"$name\"", "unknown_tag")
        } else {
            listOf(Identifier.tryParse(name.lowercase())?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) } ?: badRequest("Unknown block \"$name\"", "unknown_block"))
        }
    }.filterNot { it.defaultBlockState().isAir }.toSet()
    if (types.isEmpty()) badRequest("Name at least one block that isn't air")
    return types
}

/** Whether any block of a chunk lies within [radius] of x, z. */
internal fun chunkInRange(cx: Int, cz: Int, x: Int, z: Int, radius: Int): Boolean {
    val nearestX = x.coerceIn(cx shl 4, (cx shl 4) + 15)
    val nearestZ = z.coerceIn(cz shl 4, (cz shl 4) + 15)
    val dx = (nearestX - x).toLong()
    val dz = (nearestZ - z).toLong()
    return dx * dx + dz * dz <= radius.toLong() * radius
}

/**
 * Looks through chunk copies for blocks of [types] within [radius] of [center] and returns the [limit] nearest, plus
 * how many matched in all. Runs off the server thread.
 */
internal fun scan(snapshots: List<Snapshot>, types: Set<Block>, center: BlockPosition, radius: Int, heights: IntRange, limit: Int): Pair<List<Found>, Int> {
    val r2 = radius.toLong() * radius
    // The farthest of the nearest stays on top, so a nearer find can push it out.
    val nearest = PriorityQueue<Pair<Long, Found>>(compareByDescending { it.first })
    var matched = 0
    val ys = maxOf(heights.first, center.y - radius)..minOf(heights.last, center.y + radius)
    for (snapshot in snapshots) {
        val baseX = snapshot.x shl 4
        val baseZ = snapshot.z shl 4
        for (lx in 0..15) for (lz in 0..15) {
            val dx = (baseX + lx - center.x).toLong()
            val dz = (baseZ + lz - center.z).toLong()
            val column = dx * dx + dz * dz
            if (column > r2) continue
            for (y in ys) {
                val dy = (y - center.y).toLong()
                val d2 = column + dy * dy
                if (d2 > r2) continue
                val type = snapshot.block(lx, y, lz)
                if (type !in types) continue
                matched++
                if (nearest.size < limit || d2 < nearest.peek().first) {
                    nearest += d2 to Found(BuiltInRegistries.BLOCK.getKey(type).toString(), baseX + lx, y, baseZ + lz, round(sqrt(d2.toDouble()) * 10) / 10)
                    if (nearest.size > limit) nearest.poll()
                }
            }
        }
    }
    return nearest.sortedBy { it.first }.map { it.second } to matched
}

private fun distance(dx: Int, dy: Int, dz: Int) = round(sqrt(dx.toDouble() * dx + dy.toDouble() * dy + dz.toDouble() * dz) * 10) / 10
