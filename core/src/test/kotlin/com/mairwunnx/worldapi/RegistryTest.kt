package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.routes.registryMatches
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryTest {
    @Test
    fun `registry search takes words`() {
        assertTrue(registryMatches("minecraft:stripped_oak_log", "oak log"))
        assertTrue(registryMatches("minecraft:oak_log", "minecraft:oak_log"))
        assertTrue(registryMatches("minecraft:oak_log", "OAK"))
        assertFalse(registryMatches("minecraft:oak_planks", "oak log"))
    }
}
