package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.model.AttributeModifierView
import com.mairwunnx.worldapi.model.BookView
import com.mairwunnx.worldapi.model.FireworkExplosionView
import com.mairwunnx.worldapi.model.FireworkView
import com.mairwunnx.worldapi.model.ItemInput
import com.mairwunnx.worldapi.model.ItemView
import com.mairwunnx.worldapi.model.PotionEffectView
import com.mairwunnx.worldapi.model.PotionView
import com.mairwunnx.worldapi.model.TrimView
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemArmorTrim
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers
import io.papermc.paper.datacomponent.item.PotionContents
import io.papermc.paper.datacomponent.item.SuspiciousStewEffects
import io.papermc.paper.datacomponent.item.WrittenBookContent
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

/** Items a shulker box or bundle lists at most; vanilla ones never hold more. */
const val MAX_CONTENTS = 64

/** Must run on the server thread. Items inside a container item come without their own contents. */
fun ItemStack?.view(slot: Int? = null, contents: Boolean = true): ItemView? {
    if (this == null || type.isAir || amount <= 0) return null
    val meta = itemMeta
    val damageable = meta as? Damageable
    return ItemView(
        slot = slot,
        type = type.key.asString(),
        amount = amount,
        name = meta?.takeIf { it.hasCustomName() }?.customName()?.plain(),
        lore = meta?.lore()?.map { it.plain() } ?: emptyList(),
        enchantments = enchantments.entries.associate { it.key.key.asString() to it.value },
        damage = damageable?.takeIf { type.maxDurability > 0 }?.damage,
        maxDamage = type.maxDurability.toInt().takeIf { it > 0 },
        unbreakable = meta?.isUnbreakable == true,
        customModelData = meta?.takeIf { it.hasCustomModelData() }?.customModelData,
        rarity = meta?.takeIf { it.hasRarity() }?.rarity?.name?.lowercase(),
        storedEnchantments = getData(DataComponentTypes.STORED_ENCHANTMENTS)?.enchantments()
            ?.takeIf { it.isNotEmpty() }?.entries?.associate { it.key.key.asString() to it.value },
        attributes = getData(DataComponentTypes.ATTRIBUTE_MODIFIERS)?.views(),
        potion = potionView(
            getData(DataComponentTypes.POTION_CONTENTS),
            getData(DataComponentTypes.POTION_DURATION_SCALE) ?: 1f,
            getData(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS),
        ),
        trim = getData(DataComponentTypes.TRIM)?.view(),
        dyedColor = getData(DataComponentTypes.DYED_COLOR)?.color()?.hex(),
        repairCost = getData(DataComponentTypes.REPAIR_COST)?.takeIf { it > 0 },
        book = getData(DataComponentTypes.WRITTEN_BOOK_CONTENT)?.view(),
        firework = getData(DataComponentTypes.FIREWORKS)?.let { fireworks -> FireworkView(fireworks.flightDuration(), fireworks.effects().map { it.view() }) }
            ?: getData(DataComponentTypes.FIREWORK_EXPLOSION)?.let { FireworkView(explosions = listOf(it.view())) },
        contents = if (!contents) null else {
            (getData(DataComponentTypes.CONTAINER)?.contents() ?: getData(DataComponentTypes.BUNDLE_CONTENTS)?.contents())
                ?.asSequence()?.mapIndexedNotNull { index, item -> item.view(index, contents = false) }
                ?.take(MAX_CONTENTS)?.toList()?.takeIf { it.isNotEmpty() }
        },
        components = type.key.asString() + (meta?.asComponentString ?: ""),
    )
}

/** Modifiers that change nothing, like leather armor's zero toughness, are left out. */
fun ItemAttributeModifiers.views(): List<AttributeModifierView>? = modifiers().filter { it.modifier().amount != 0.0 }.map { entry ->
    val modifier = entry.modifier()
    AttributeModifierView(
        attribute = entry.attribute().key.asString(),
        // The game keeps some defaults as floats: round −2.4000000953674316 back to −2.4.
        amount = Math.round(modifier.amount * 1e6) / 1e6,
        operation = when (modifier.operation) {
            AttributeModifier.Operation.ADD_NUMBER -> "add_value"
            AttributeModifier.Operation.ADD_SCALAR -> "add_multiplied_base"
            AttributeModifier.Operation.MULTIPLY_SCALAR_1 -> "add_multiplied_total"
        },
        slot = modifier.slotGroup.toString(),
        id = modifier.key.asString(),
    )
}.takeIf { it.isNotEmpty() }

/** Durations get the item's scale, as the tooltip shows them: tipped arrows give an eighth, lingering potions a quarter. */
fun potionView(contents: PotionContents?, durationScale: Float, stew: SuspiciousStewEffects?): PotionView? {
    if (contents != null) {
        val effects = contents.allEffects().map { effect ->
            val duration = if (effect.isInfinite) -1 else (effect.duration * durationScale).toInt()
            PotionEffectView(effect.type.key.asString(), effect.amplifier, duration)
        }
        val type = contents.potion()?.key?.asString()
        val color = contents.customColor()?.hex()
        if (type != null || effects.isNotEmpty() || color != null) return PotionView(type, effects, color)
    }
    val stewEffects = stew?.effects()?.takeIf { it.isNotEmpty() } ?: return null
    return PotionView(effects = stewEffects.map { PotionEffectView(it.effect().key.asString(), 0, it.duration()) })
}

fun ItemArmorTrim.view() = armorTrim().let { TrimView(it.material.key().asString(), it.pattern.key().asString()) }

fun WrittenBookContent.view() = BookView(
    title = title().raw(),
    author = author(),
    generation = listOf("original", "copy_of_original", "copy_of_copy", "tattered").getOrElse(generation()) { "tattered" },
    pages = pages().size,
)

fun FireworkEffect.view() = FireworkExplosionView(
    shape = when (type) {
        FireworkEffect.Type.BALL -> "small_ball"
        FireworkEffect.Type.BALL_LARGE -> "large_ball"
        else -> type.name.lowercase()
    },
    colors = colors.map { it.hex() },
    fadeColors = fadeColors.map { it.hex() }.takeIf { it.isNotEmpty() },
    trail = hasTrail().takeIf { it },
    twinkle = hasFlicker().takeIf { it },
)

fun Color.hex() = "#%06x".format(asRGB())

fun Inventory.views(): List<ItemView> = contents.mapIndexedNotNull { slot, item -> item.view(slot) }

/** Builds an item from a request. Must run on the server thread: item factories touch registries. */
fun ItemInput.toItemStack(): ItemStack {
    if (amount !in 1..6400) badRequest("amount must be between 1 and 6400")
    val command = item
    val typeName = type
    val stack = when {
        command != null -> runCatching { Bukkit.getItemFactory().createItemStack(command) }.getOrElse { badRequest("Can't parse item \"$command\": ${it.message}", "invalid_item") }
        typeName != null -> {
            val material = Material.matchMaterial(typeName)?.takeIf { it.isItem && !it.isAir } ?: badRequest("Unknown item \"$typeName\"", "unknown_item")
            ItemStack(material)
        }
        else -> badRequest("Give either type or item")
    }
    stack.amount = amount
    if (name != null || lore.isNotEmpty() || enchantments.isNotEmpty() || damage != null || unbreakable != null) {
        stack.editMeta { meta ->
            name?.let { meta.customName(text(it, format, 256)) }
            if (lore.isNotEmpty()) meta.lore(lore.map { text(it, format, 256) })
            unbreakable?.let { meta.isUnbreakable = it }
            damage?.let { (meta as? Damageable)?.damage = it }
        }
        enchantments.forEach { (key, level) ->
            if (level !in 1..255) badRequest("Enchantment levels go from 1 to 255")
            stack.addUnsafeEnchantment(registryEntry(RegistryKey.ENCHANTMENT, key, "enchantment"), level)
        }
    }
    return stack
}

/** Gives a stack; whatever doesn't fit gets dropped at the player's feet. Returns how many dropped. */
fun org.bukkit.entity.Player.giveOrDrop(stack: ItemStack): Int {
    val left = inventory.addItem(stack)
    left.values.forEach { world.dropItemNaturally(location, it) }
    return left.values.sumOf { it.amount }
}
