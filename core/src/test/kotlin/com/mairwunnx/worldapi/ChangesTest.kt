package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.changes.Changes
import com.mairwunnx.worldapi.model.BlockPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChangesTest {
    private var capacity = 5
    private val changes = Changes<String> { capacity }

    private fun blocks(count: Int, x: Int = 0) = List(count) { Changes.Block(x + it, 64, -it, "minecraft:air", "minecraft:stone") }

    @Test
    fun `the oldest changes drop out once the journal is full`() {
        val first = changes.record("admin", "world", "fill", blocks(2))
        val second = changes.record("admin", "world", "fill", blocks(2))
        val third = changes.record("panel:owner", "world", "set", blocks(2))
        assertNull(changes.get(first!!))
        assertEquals(listOf(third, second), changes.list().map { it.id })
    }

    @Test
    fun `an empty change, or one past the limit alone, isn't kept`() {
        assertNull(changes.record("admin", "world", "fill", emptyList()))
        assertNull(changes.record("admin", "world", "fill", blocks(6)))
        capacity = 0
        assertNull(changes.record("admin", "world", "set", blocks(1)))
        assertEquals(0, changes.list().size)
    }

    @Test
    fun `a change shows the box it touched`() {
        val id = changes.record("admin", "world_nether", "fill minecraft:stone", blocks(3, x = -1), at = 0)
        val view = assertNotNull(changes.get(id!!)).view()
        assertEquals(BlockPosition(-1, 64, -2) to BlockPosition(1, 64, 0), view.from to view.to)
        assertEquals("1970-01-01T00:00:00Z", view.at)
        assertEquals(listOf(-1 to 0, 0 to -1), changes.get(id)!!.chunks)
    }
}
