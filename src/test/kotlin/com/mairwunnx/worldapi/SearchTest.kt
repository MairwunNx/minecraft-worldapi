package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.routes.chunkInRange
import com.mairwunnx.worldapi.routes.scan
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTest {
    /** A chunk of stone with a few blocks set, keyed by world coordinates. */
    private fun chunk(cx: Int, cz: Int, blocks: Map<Triple<Int, Int, Int>, Material>): ChunkSnapshot =
        Proxy.newProxyInstance(ChunkSnapshot::class.java.classLoader, arrayOf(ChunkSnapshot::class.java)) { _, method, args ->
            when (method.name) {
                "getX" -> cx
                "getZ" -> cz
                "getBlockType" -> blocks[Triple((cx shl 4) + args[0] as Int, args[1] as Int, (cz shl 4) + args[2] as Int)] ?: Material.STONE
                else -> null
            }
        } as ChunkSnapshot

    @Test
    fun `the nearest blocks come first, and far ones are counted but not listed`() {
        val ores = mapOf(
            Triple(5, 10, 5) to Material.DIAMOND_ORE,
            Triple(1, 11, 0) to Material.DEEPSLATE_DIAMOND_ORE,
            Triple(-3, 10, -3) to Material.DIAMOND_ORE,
            Triple(40, 10, 0) to Material.DIAMOND_ORE,
        )
        val chunks = listOf(chunk(0, 0, ores), chunk(-1, -1, ores), chunk(2, 0, ores))
        val (found, matched) = scan(chunks, setOf(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE), BlockPosition(0, 10, 0), 16, -64..319, 2)
        assertEquals(3, matched)
        assertEquals(listOf("minecraft:deepslate_diamond_ore" to 1.4, "minecraft:diamond_ore" to 4.2), found.map { it.type to it.distance })
        assertEquals(listOf(-3, 10, -3), found[1].let { listOf(it.x, it.y, it.z) })
    }

    @Test
    fun `the search stays inside the world's height`() {
        val chunks = listOf(chunk(0, 0, mapOf(Triple(0, -65, 0) to Material.BEDROCK, Triple(0, -64, 0) to Material.BEDROCK)))
        val (found, _) = scan(chunks, setOf(Material.BEDROCK), BlockPosition(0, -60, 0), 8, -64..319, 10)
        assertEquals(listOf(-64), found.map { it.y })
    }

    @Test
    fun `only chunks that reach into the radius are read`() {
        assertTrue(chunkInRange(0, 0, 0, 0, 1))
        assertTrue(chunkInRange(1, 0, 0, 0, 16))
        assertFalse(chunkInRange(1, 1, 0, 0, 16))
        assertTrue(chunkInRange(-1, -1, 0, 0, 2))
    }
}
