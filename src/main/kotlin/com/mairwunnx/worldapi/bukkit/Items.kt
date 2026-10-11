package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.model.ItemInput
import com.mairwunnx.worldapi.model.ItemView
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

fun ItemStack?.view(slot: Int? = null): ItemView? {
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
        components = type.key.asString() + (meta?.asComponentString ?: ""),
    )
}

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
