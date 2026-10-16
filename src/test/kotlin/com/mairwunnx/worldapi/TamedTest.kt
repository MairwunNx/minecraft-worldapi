package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.stats.EntityFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TamedTest {
    /** A region file from Paper 26.3 with a wolf and a cat of one owner and a parrot of another, as `/summon` saved them. */
    private val folder = File(javaClass.getResource("/entities/r.0.0.mca")!!.toURI()).parentFile
    private val first = UUID(1L shl 32 or 2, 3L shl 32 or 4)
    private val second = UUID(5L shl 32 or 6, 7L shl 32 or 8)

    @Test
    fun `pets come out of the entity files with their owners`() {
        val owners = EntityFiles.pets(folder).groupingBy { it.owner }.eachCount()
        assertEquals(mapOf(first to 2, second to 1), owners)
        assertEquals(setOf(folder.canonicalFile), EntityFiles.folders(listOf(folder.parentFile)))
    }

    @Test
    fun `the world is counted once, and tamings add up after it`() = TestApi(WorldApiConfig()).use { fake ->
        val tamed = fake.api.tamed
        tamed.seed(mapOf(first to ("Steve" to 2L)))
        tamed.add(first, "Steve")
        tamed.seed(mapOf(first to ("Steve" to 50L)))
        runBlocking { assertEquals(listOf(3L), tamed.totals().map { it.tamed }) }
    }
}
