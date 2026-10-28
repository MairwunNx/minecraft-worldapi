package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.QueryParam
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.model.BlockArea
import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.model.PaletteEntry
import com.mairwunnx.worldapi.model.SpawnSpot

/** The area read's query, the same on every platform. */
val areaParams: List<QueryParam> = listOf(
    string("from", "One corner of the box, as `x,y,z`", required = true),
    string("to", "The opposite corner, as `x,y,z`; the box includes both", required = true),
    boolean("blocks", "Send each block's palette index; false sends only the palette with counts. On by default"),
    boolean("spawns", "List where monsters could spawn: dark blocks with a floor they may stand on"),
)

/** A box from the area read's query, before the world's height clips it. */
class Box(val from: BlockPosition, val to: BlockPosition) {
    val xs get() = from.x..to.x
    val zs get() = from.z..to.z

    /** The chunks the box touches, as chunk x and z. */
    val chunks get() = ((from.x shr 4)..(to.x shr 4)).flatMap { cx -> ((from.z shr 4)..(to.z shr 4)).map { cz -> cx to cz } }

    /** Clips y to the world's height, `minY` inclusive and `maxY` exclusive. */
    fun clip(minY: Int, maxY: Int): Box {
        val low = maxOf(from.y, minY)
        val high = minOf(to.y, maxY - 1)
        if (low > high) badRequest("The box lies outside the world's height, $minY..${maxY - 1}")
        return Box(BlockPosition(from.x, low, from.z), BlockPosition(to.x, high, to.z))
    }
}

/** Reads `from` and `to`, orders the corners, and checks the volume against limits.maxReadBlocks. */
fun ApiCall<*>.areaBox(): Box {
    fun corner(name: String): List<Int> {
        val text = query(name) ?: badRequest("$name is required")
        val parts = text.split(',').map { it.trim().toIntOrNull() ?: badRequest("$name must be x,y,z with integers, such as 10,64,-20") }
        if (parts.size != 3) badRequest("$name must be x,y,z with integers, such as 10,64,-20")
        if (parts[0] !in -30_000_000..30_000_000 || parts[2] !in -30_000_000..30_000_000) badRequest("x and z must be within the world")
        if (parts[1] !in -2048..4096) badRequest("y must be within -2048..4096")
        return parts
    }
    val (a, b) = corner("from") to corner("to")
    val box = Box(BlockPosition(minOf(a[0], b[0]), minOf(a[1], b[1]), minOf(a[2], b[2])), BlockPosition(maxOf(a[0], b[0]), maxOf(a[1], b[1]), maxOf(a[2], b[2])))
    val volume = (box.to.x - box.from.x + 1).toLong() * (box.to.y - box.from.y + 1) * (box.to.z - box.from.z + 1)
    val limit = config.limits.maxReadBlocks
    if (volume > limit) badRequest("The box holds $volume blocks; the limit is $limit", "area_too_large")
    return box
}

/** Spots the area read lists at most; `spawnableTotal` counts the rest. */
const val MAX_SPAWN_SPOTS = 1_000

/**
 * Builds a [BlockArea] as a platform walks the box in its order: y, then z, then x. The platform reads the game; this
 * keeps the palette and the spots.
 */
class AreaBuilder(private val world: String, private val box: Box, private val withBlocks: Boolean, private val withSpawns: Boolean) {
    private val indexes = LinkedHashMap<String, Int>()
    private val counts = ArrayList<Int>()
    private val cells = if (withBlocks) IntArray((box.to.x - box.from.x + 1) * (box.to.y - box.from.y + 1) * (box.to.z - box.from.z + 1)) else null
    private var next = 0
    private val spots = ArrayList<SpawnSpot>()
    private var spotTotal = 0

    fun block(state: String) {
        val index = indexes.getOrPut(state) { counts.add(0); counts.size - 1 }
        counts[index]++
        cells?.set(next, index)
        next++
    }

    fun spawnable(x: Int, y: Int, z: Int, skyLight: Int) {
        spotTotal++
        if (spots.size < MAX_SPAWN_SPOTS) spots += SpawnSpot(x, y, z, skyLight)
    }

    fun build(): BlockArea {
        // The most common state first, so the indexes a client sees most are the small ones.
        val states = indexes.keys.toList()
        val order = states.indices.sortedWith(compareByDescending<Int> { counts[it] }.thenBy { states[it] })
        val renumber = IntArray(order.size).also { map -> order.forEachIndexed { new, old -> map[old] = new } }
        return BlockArea(
            world = world,
            from = box.from, to = box.to,
            sizeX = box.to.x - box.from.x + 1, sizeY = box.to.y - box.from.y + 1, sizeZ = box.to.z - box.from.z + 1,
            palette = order.map { PaletteEntry(states[it], counts[it]) },
            blocks = cells?.map { renumber[it] },
            spawnable = if (withSpawns) spots else null,
            spawnableTotal = if (withSpawns) spotTotal else null,
        )
    }
}
