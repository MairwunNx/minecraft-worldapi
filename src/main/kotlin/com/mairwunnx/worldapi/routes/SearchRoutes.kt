package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.key
import com.mairwunnx.worldapi.bukkit.registryEntry
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.model.FoundBiome
import com.mairwunnx.worldapi.model.FoundBlock
import com.mairwunnx.worldapi.model.SearchResult
import io.ktor.http.HttpStatusCode
import io.papermc.paper.registry.RegistryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Biome
import java.util.PriorityQueue
import kotlin.math.round
import kotlin.math.sqrt

private const val BIOME_STEP = 32

val searchRoutes = endpoints("Worlds") {
    get(
        "/worlds/{world}/search", "Find the nearest blocks of some types, or the nearest biome",
        description = "Block search looks through chunks that are already loaded and never loads more; `unloadedChunks` says how many it skipped. " +
            "It reads copies of those chunks off the server thread, within limits.maxBlockSearchRadius. " +
            "It needs a token whatever the anonymous settings say, because it would show ores and ancient debris through walls. " +
            "Biome search asks the world generator, so it works in unexplored land too; it runs on the server thread, within limits.maxBiomeSearchRadius. " +
            "Anonymous clients may search biomes only when they may see coordinates.",
        query = listOf(
            string("blocks", "Block keys or tags, separated by commas: `minecraft:diamond_ore,#minecraft:logs`"),
            string("biome", "Biome keys, separated by commas; the nearest of any counts: `minecraft:cherry_grove`"),
            integer("x", "Center X", required = true),
            integer("y", "Center Y; required for blocks, 64 by default for biomes"),
            integer("z", "Center Z", required = true),
            integer("radius", "Block search radius in blocks (32 by default)"),
            integer("biomeRadius", "Biome search radius in blocks (1024 by default)"),
            integer("limit", "How many blocks to return, nearest first (10 by default, at most 100)"),
        ),
    ) {
        val blocksQuery = query("blocks")
        val biomeQuery = query("biome")
        if (blocksQuery == null && biomeQuery == null) badRequest("Give blocks, biome, or both")
        if (principal.anonymous && blocksQuery != null) {
            throw ApiException(HttpStatusCode.Unauthorized, "token_required", "Block search needs a token: it would show ores through walls")
        }
        if (!seesCoordinates) throw ApiException(HttpStatusCode.Forbidden, "private", "Coordinates are hidden from anonymous clients")
        val x = requireInt("x")
        val z = requireInt("z")
        if (x !in -30_000_000..30_000_000 || z !in -30_000_000..30_000_000) badRequest("x and z must be within the world")
        val y = int("y")
        val limits = config.limits
        val radius = int("radius") ?: minOf(32, limits.maxBlockSearchRadius)
        if (radius !in 1..limits.maxBlockSearchRadius) badRequest("radius must be between 1 and ${limits.maxBlockSearchRadius}")
        val biomeRadius = int("biomeRadius") ?: minOf(1024, limits.maxBiomeSearchRadius)
        if (biomeRadius !in 1..limits.maxBiomeSearchRadius) badRequest("biomeRadius must be between 1 and ${limits.maxBiomeSearchRadius}")
        val limit = int("limit") ?: 10
        if (limit !in 1..100) badRequest("limit must be between 1 and 100")
        if (blocksQuery != null && y == null) badRequest("Block search needs y")

        val world = world()
        var blocks: List<FoundBlock>? = null
        var matched: Int? = null
        var scanned: Int? = null
        var unloaded: Int? = null
        if (blocksQuery != null) {
            val center = BlockPosition(x, y!!, z)
            val (snapshots, skipped, heights) = main {
                val types = materials(blocksQuery)
                val loaded = mutableListOf<ChunkSnapshot>()
                var missing = 0
                for (cx in ((x - radius) shr 4)..((x + radius) shr 4)) for (cz in ((z - radius) shr 4)..((z + radius) shr 4)) {
                    if (!chunkInRange(cx, cz, x, z, radius)) continue
                    if (world.isChunkLoaded(cx, cz)) loaded += world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false) else missing++
                }
                Triple(loaded to types, missing, world.minHeight until world.maxHeight)
            }
            val result = withContext(Dispatchers.Default) { scan(snapshots.first, snapshots.second, center, radius, heights, limit) }
            blocks = result.first
            matched = result.second
            scanned = snapshots.first.size
            unloaded = skipped
        }
        val biome = biomeQuery?.let { names ->
            main {
                val wanted = names.split(',').map { registryEntry(RegistryKey.BIOME, it.trim(), "biome") }.toTypedArray<Biome>()
                val origin = Location(world, x.toDouble(), (y ?: 64).toDouble(), z.toDouble())
                world.locateNearestBiome(origin, biomeRadius, BIOME_STEP, 64, *wanted)?.let { found ->
                    val at = found.location
                    FoundBiome(found.biome.key.asString(), at.blockX, at.blockY, at.blockZ, distance(at.blockX - x, 0, at.blockZ - z))
                }
            }
        }
        SearchResult(world.name, x, y, z, blocks, matched, scanned, unloaded, biome)
    }
}

/** Block keys and `#tags`, as one set of block types. Air can't be searched for. */
private fun com.mairwunnx.worldapi.api.ApiCall.materials(text: String): Set<Material> {
    val types = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { name ->
        if (name.startsWith("#")) {
            api.server.getTag(Tag.REGISTRY_BLOCKS, key(name.removePrefix("#"), "tag"), Material::class.java)?.values
                ?: badRequest("Unknown block tag \"$name\"", "unknown_tag")
        } else {
            listOf(Material.matchMaterial(name)?.takeIf { it.isBlock } ?: badRequest("Unknown block \"$name\"", "unknown_block"))
        }
    }.filterNot { it.isAir }.toSet()
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
 * Looks through chunk copies for blocks of [types] within [radius] of [center] and returns the
 * [limit] nearest, plus how many matched in all. Runs off the server thread.
 */
internal fun scan(snapshots: List<ChunkSnapshot>, types: Set<Material>, center: BlockPosition, radius: Int, heights: IntRange, limit: Int): Pair<List<FoundBlock>, Int> {
    val r2 = radius.toLong() * radius
    // The farthest of the nearest stays on top, so a nearer find can push it out.
    val nearest = PriorityQueue<Pair<Long, FoundBlock>>(compareByDescending { it.first })
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
                val type = snapshot.getBlockType(lx, y, lz)
                if (type !in types) continue
                matched++
                if (nearest.size < limit || d2 < nearest.peek().first) {
                    nearest += d2 to FoundBlock(type.key.asString(), baseX + lx, y, baseZ + lz, round(sqrt(d2.toDouble()) * 10) / 10)
                    if (nearest.size > limit) nearest.poll()
                }
            }
        }
    }
    return nearest.sortedBy { it.first }.map { it.second } to matched
}

private fun distance(dx: Int, dy: Int, dz: Int) = round(sqrt(dx.toDouble() * dx + dy.toDouble() * dy + dz.toDouble() * dz) * 10) / 10
