package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.model.ChunkSummary
import com.mairwunnx.worldapi.model.RecipeIngredient
import com.mairwunnx.worldapi.model.RecipeView
import com.mairwunnx.worldapi.routes.ChunkSort
import com.mairwunnx.worldapi.routes.canSleep
import com.mairwunnx.worldapi.routes.matches
import com.mairwunnx.worldapi.routes.ticksUntilNight
import com.mairwunnx.worldapi.routes.topTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldTest {
    @Test
    fun `night is counted down from any time of day`() {
        assertEquals(13_000, ticksUntilNight(0))
        assertEquals(7_000, ticksUntilNight(6_000))
        assertEquals(0, ticksUntilNight(13_000))
        assertEquals(0, ticksUntilNight(22_999))
        assertEquals(14_000, ticksUntilNight(23_000))
    }

    @Test
    fun `beds work at night, earlier in rain, and always in a thunderstorm`() {
        assertFalse(canSleep(12_000, raining = false, thundering = false))
        assertTrue(canSleep(12_542, raining = false, thundering = false))
        assertFalse(canSleep(23_460, raining = false, thundering = false))
        assertTrue(canSleep(12_010, raining = true, thundering = false))
        assertTrue(canSleep(6_000, raining = true, thundering = true))
    }

    @Test
    fun `busy chunks sort first`() {
        val chunks = listOf(ChunkSummary(0, 0, 3, 9, false), ChunkSummary(-1, 2, 40, 0, false), ChunkSummary(-1, 1, 40, 1, false))
        assertEquals(listOf(-1 to 1, -1 to 2, 0 to 0), chunks.sortedWith(ChunkSort.ENTITIES.order).map { it.x to it.z })
        assertEquals(listOf(0 to 0, -1 to 1, -1 to 2), chunks.sortedWith(ChunkSort.TILE_ENTITIES.order).map { it.x to it.z })
        assertEquals(listOf(-1 to 1, -1 to 2, 0 to 0), chunks.sortedWith(ChunkSort.COORDS.order).map { it.x to it.z })
    }

    @Test
    fun `entity types are counted, most common first`() {
        val keys = List(5) { "minecraft:item" } + List(2) { "minecraft:cow" } + List(2) { "minecraft:bee" } + "minecraft:pig"
        assertEquals(listOf("minecraft:item" to 5, "minecraft:bee" to 2, "minecraft:cow" to 2), topTypes(keys, top = 3).toList())
    }

    @Test
    fun `recipes match what they make and what they take`() {
        val torch = RecipeView("minecraft:torch", "crafting_shaped", "minecraft:torch", 4, listOf("C", "S"),
            listOf(RecipeIngredient("C", listOf("minecraft:coal", "minecraft:charcoal")), RecipeIngredient("S", listOf("minecraft:stick"))))
        assertTrue(matches(torch, "minecraft:torch", null))
        assertTrue(matches(torch, null, "minecraft:charcoal"))
        assertTrue(matches(torch, "minecraft:torch", "minecraft:stick"))
        assertFalse(matches(torch, "minecraft:stick", null))
        assertFalse(matches(torch, "minecraft:torch", "minecraft:diamond"))
    }
}
