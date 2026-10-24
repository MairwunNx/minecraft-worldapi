package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.bukkit.potionView
import com.mairwunnx.worldapi.bukkit.view
import com.mairwunnx.worldapi.bukkit.views
import com.mairwunnx.worldapi.model.AttributeModifierView
import com.mairwunnx.worldapi.model.BookView
import com.mairwunnx.worldapi.model.FireworkExplosionView
import com.mairwunnx.worldapi.model.ItemView
import com.mairwunnx.worldapi.model.PotionView
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers
import io.papermc.paper.datacomponent.item.PotionContents
import io.papermc.paper.datacomponent.item.WrittenBookContent
import io.papermc.paper.text.Filtered
import kotlinx.serialization.json.jsonObject
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlotGroup
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemTest {
    /** An interface whose methods answer by name; anything else returns null. */
    private inline fun <reified T> fake(vararg answers: Pair<String, Any?>): T {
        val map = answers.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when {
                method.name in map -> map[method.name]
                method.name == "toString" -> T::class.java.simpleName
                method.name == "hashCode" -> 0
                method.name == "equals" -> false
                else -> null
            }
        } as T
    }

    @Test
    fun `attributes use vanilla names, round float noise, and skip zeros`() {
        val damage = fake<Attribute>("getKey" to NamespacedKey.minecraft("attack_damage"))
        val speed = fake<Attribute>("getKey" to NamespacedKey.minecraft("attack_speed"))
        val modifiers = fake<ItemAttributeModifiers>(
            "modifiers" to listOf(
                fake<ItemAttributeModifiers.Entry>(
                    "attribute" to damage,
                    "modifier" to AttributeModifier(NamespacedKey.minecraft("base_attack_damage"), 6.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND),
                ),
                fake<ItemAttributeModifiers.Entry>(
                    "attribute" to speed,
                    "modifier" to AttributeModifier(NamespacedKey.fromString("test:haste")!!, (-2.4f).toDouble(), AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY),
                ),
                fake<ItemAttributeModifiers.Entry>(
                    "attribute" to speed,
                    "modifier" to AttributeModifier(NamespacedKey.minecraft("nothing"), 0.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY),
                ),
            ),
        )
        assertEquals(
            listOf(
                AttributeModifierView("minecraft:attack_damage", 6.0, "add_value", "mainhand", "minecraft:base_attack_damage"),
                AttributeModifierView("minecraft:attack_speed", -2.4, "add_multiplied_total", "any", "test:haste"),
            ),
            modifiers.views(),
        )
        assertNull(fake<ItemAttributeModifiers>("modifiers" to emptyList<Any>()).views())
    }

    @Test
    fun `a potion shows its custom color, and an empty one is left out`() {
        val tinted = fake<PotionContents>("allEffects" to emptyList<Any>(), "customColor" to Color.fromRGB(0x385dc6))
        assertEquals(PotionView(color = "#385dc6"), potionView(tinted, 1f, null))
        assertNull(potionView(fake<PotionContents>("allEffects" to emptyList<Any>()), 1f, null))
        assertNull(potionView(null, 1f, null))
    }

    @Test
    fun `a book gives its title, author, and generation, not its pages`() {
        val book = fake<WrittenBookContent>(
            "title" to Filtered.of("Rules", null),
            "author" to "Steve",
            "generation" to 1,
            "pages" to listOf(Filtered.of("one", null), Filtered.of("two", null)),
        )
        assertEquals(BookView("Rules", "Steve", "copy_of_original", 2), book.view())
    }

    @Test
    fun `firework explosions use vanilla shape names and skip what's off`() {
        val plain = FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.RED).build()
        assertEquals(FireworkExplosionView("large_ball", listOf("#ff0000")), plain.view())
        val fancy = FireworkEffect.builder().with(FireworkEffect.Type.CREEPER).withColor(Color.LIME).withFade(Color.BLUE).trail(true).flicker(true).build()
        assertEquals(FireworkExplosionView("creeper", listOf("#00ff00"), listOf("#0000ff"), trail = true, twinkle = true), fancy.view())
    }

    @Test
    fun `a plain item carries none of the new fields`() {
        val json = ApiJson.encodeToJsonElement(ItemView.serializer(), ItemView(type = "minecraft:stone", amount = 1, components = "minecraft:stone")).jsonObject
        assertEquals(setOf("type", "amount", "lore", "enchantments", "unbreakable", "components"), json.keys)
    }
}
