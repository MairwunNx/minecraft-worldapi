package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.routes.AreaBuilder
import com.mairwunnx.worldapi.routes.Box
import com.mairwunnx.worldapi.routes.MAX_SPAWN_SPOTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AreaTest {
    private val box = Box(BlockPosition(-1, 10, 5), BlockPosition(0, 11, 6))

    /** Walks [box] in the platforms' order, y then z then x, naming each block by [name]. */
    private fun read(box: Box, blocks: Boolean = true, spawns: Boolean = false, name: (Int, Int, Int) -> String): AreaBuilder {
        val area = AreaBuilder("world", box, blocks, spawns)
        for (y in box.from.y..box.to.y) for (z in box.zs) for (x in box.xs) area.block(name(x, y, z))
        return area
    }

    @Test
    fun `the most common state comes first, and every index finds its block`() {
        val name = { x: Int, y: Int, z: Int -> if (y == 10) "minecraft:stone" else if (x == 0 && z == 6) "minecraft:torch" else "minecraft:air" }
        val area = read(box, name = name).build()
        assertEquals(listOf("minecraft:stone" to 4, "minecraft:air" to 3, "minecraft:torch" to 1), area.palette.map { it.state to it.count })
        assertEquals(listOf(2, 2, 2), listOf(area.sizeX, area.sizeY, area.sizeZ))
        for (y in 10..11) for (z in 5..6) for (x in -1..0) {
            val index = ((y - area.from.y) * area.sizeZ + (z - area.from.z)) * area.sizeX + (x - area.from.x)
            assertEquals(name(x, y, z), area.palette[area.blocks!![index]].state)
        }
    }

    @Test
    fun `without blocks only the palette comes back`() {
        val area = read(box, blocks = false) { _, _, _ -> "minecraft:air" }.build()
        assertNull(area.blocks)
        assertNull(area.spawnable)
        assertEquals(8, area.palette.single().count)
    }

    @Test
    fun `spawn spots stop at the cap, and the total counts on`() {
        val area = read(box, spawns = true) { _, _, _ -> "minecraft:air" }
        repeat(MAX_SPAWN_SPOTS + 5) { area.spawnable(it, 64, 0, 0) }
        val built = area.build()
        assertEquals(MAX_SPAWN_SPOTS, built.spawnable!!.size)
        assertEquals(MAX_SPAWN_SPOTS + 5, built.spawnableTotal)
    }

    @Test
    fun `a box finds its chunks on both sides of zero`() {
        assertEquals(listOf(-1 to 0, 0 to 0), box.chunks)
        assertEquals(4, Box(BlockPosition(-17, 0, 15), BlockPosition(-16, 0, 16)).chunks.size)
    }

    @Test
    fun `the world's height clips a box, and a box above it is refused`() {
        val clipped = Box(BlockPosition(0, -100, 0), BlockPosition(0, 400, 0)).clip(-64, 320)
        assertEquals(-64 to 319, clipped.from.y to clipped.to.y)
        assertFailsWith<ApiException> { Box(BlockPosition(0, 330, 0), BlockPosition(0, 340, 0)).clip(-64, 320) }
    }
}
