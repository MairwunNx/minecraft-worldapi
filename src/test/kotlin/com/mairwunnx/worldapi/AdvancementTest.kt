package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.routes.view
import org.bukkit.NamespacedKey
import org.bukkit.advancement.Advancement
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdvancementTest {
    private fun advancement(key: String, parent: Advancement? = null): Advancement =
        Proxy.newProxyInstance(Advancement::class.java.classLoader, arrayOf(Advancement::class.java)) { _, method, _ ->
            when (method.name) {
                "getKey" -> NamespacedKey.fromString(key)
                "getParent" -> parent
                else -> null
            }
        } as Advancement

    @Test
    fun `advancements name their parent`() {
        val root = advancement("minecraft:story/root")
        val diamonds = advancement("minecraft:story/mine_diamond", root)
        assertEquals("minecraft:story/root", view(diamonds, false, null, 0, 1).parent)
        assertNull(view(root, true, null, 1, 1).parent)
    }
}
