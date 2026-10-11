package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.model.StructureResult
import com.mairwunnx.worldapi.model.WorldStats
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.Location
import org.bukkit.World
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

val worldInfoRoutes = endpoints("Worlds") {
    get(
        "/worlds/{world}/structures/nearest", "Find the nearest structure",
        description = "Searching can take a while and runs on the server thread, so the radius is capped by limits.maxStructureRadius (in chunks). " +
            "`type` takes a structure key such as `minecraft:stronghold`, or a prefix such as `village` that matches every structure starting with it.",
        query = listOf(
            string("type", "Structure key or prefix", required = true),
            integer("x", "Search from this X; defaults to the world spawn"),
            integer("z", "Search from this Z; defaults to the world spawn"),
            integer("radius", "Search radius in chunks"),
            boolean("unexplored", "Only structures nobody has found yet, as treasure maps do"),
        ),
    ) {
        val type = query("type")!!.lowercase().removePrefix("minecraft:")
        val radius = int("radius") ?: config.limits.maxStructureRadius
        if (radius !in 1..config.limits.maxStructureRadius) badRequest("radius must be between 1 and ${config.limits.maxStructureRadius}")
        val x = int("x")
        val z = int("z")
        val unexplored = bool("unexplored") ?: false
        main {
            val world = world()
            val origin = Location(world, (x ?: world.spawnLocation.blockX).toDouble(), 64.0, (z ?: world.spawnLocation.blockZ).toDouble())
            val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE)
            val candidates = registry.stream().filter { it.key.key == type || it.key.key.startsWith(type) }.toList()
            if (candidates.isEmpty()) badRequest("No structure matches \"$type\"", "unknown_structure")
            val found = candidates.mapNotNull { world.locateNearestStructure(origin, it, radius, unexplored) }
                .minByOrNull { it.location.distanceSquared(origin) }
                ?: notFound("structure_not_found", "No $type within $radius chunks")
            val at = found.location
            StructureResult(
                registry.getKey(found.structure)?.asString() ?: type,
                BlockPosition(at.blockX, at.blockY, at.blockZ),
                round(Math.hypot(at.x - origin.x, at.z - origin.z) * 10) / 10,
            )
        }
    }

    get("/worlds/{world}/stats", "Explored area, region files, and size on disk") {
        val folder = main { world().let { it.name to regionFolder(it) } }
        val (name, region) = folder
        statsCache[name]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return@get it.second }
        withContext(Dispatchers.IO) {
            val files = region?.listFiles { file -> file.name.endsWith(".mca") }?.toList() ?: emptyList()
            val chunks = files.sumOf(::chunksIn)
            val size = region?.parentFile?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
            WorldStats(name, chunks, round(chunks * 256 / 1e6 * 1000) / 1000, files.size, size, Instant.now().toString())
                .also { statsCache[name] = System.currentTimeMillis() to it }
        }
    }
}

private val statsCache = ConcurrentHashMap<String, Pair<Long, WorldStats>>()

/** The region folder of a dimension; its place moved between versions. */
private fun regionFolder(world: World): File? {
    val root = world.worldFolder
    return listOf("region", "DIM-1/region", "DIM1/region").map { File(root, it) }.firstOrNull { it.isDirectory }
}

/** A region file starts with 1024 four-byte offsets; a non-zero one means the chunk exists. */
private fun chunksIn(file: File): Long = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 4096) return@use 0L
        val header = ByteArray(4096).also(raf::readFully)
        (0 until 1024).count { i -> header[i * 4].toInt() or header[i * 4 + 1].toInt() or header[i * 4 + 2].toInt() or header[i * 4 + 3].toInt() != 0 }.toLong()
    }
}.getOrDefault(0)
