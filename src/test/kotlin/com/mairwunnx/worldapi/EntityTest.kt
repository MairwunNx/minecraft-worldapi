package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.routes.removable
import net.kyori.adventure.text.Component
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.entity.Zombie
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityTest {
    private inline fun <reified T : Entity> entity(name: String? = null, tamed: Boolean = false): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.name) {
                "customName" -> name?.let(Component::text)
                "isTamed" -> tamed
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as T

    @Test
    fun `bulk removal spares players, named mobs, and pets`() {
        assertTrue(removable(entity<Zombie>(), includeNamed = false))
        assertFalse(removable(entity<Zombie>(name = "Gerald"), includeNamed = false))
        assertFalse(removable(entity<Wolf>(tamed = true), includeNamed = false))
        assertTrue(removable(entity<Wolf>(), includeNamed = false))
        assertTrue(removable(entity<Wolf>(tamed = true), includeNamed = true))
        assertFalse(removable(entity<Player>(), includeNamed = true))
    }
}
