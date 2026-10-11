package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class ItemView(
    /** Slot number inside the inventory it came from. */
    val slot: Int? = null,
    /** Item key, such as `minecraft:diamond_sword`. */
    val type: String,
    val amount: Int,
    /** The custom name, if someone renamed the item. */
    val name: String? = null,
    val lore: List<String> = emptyList(),
    /** Enchantment key to level. */
    val enchantments: Map<String, Int> = emptyMap(),
    val damage: Int? = null,
    val maxDamage: Int? = null,
    val unbreakable: Boolean = false,
    val customModelData: Int? = null,
    val rarity: String? = null,
    /** The full item in command syntax, such as `minecraft:diamond_sword[enchantments={sharpness:5}]`. */
    val components: String,
)

/**
 * An item to give or put in a slot. Either name a type and optional extras, or pass a full
 * item in command syntax as `item`; `amount` applies to both.
 */
@Serializable
data class ItemInput(
    val type: String? = null,
    /** Command syntax, such as `minecraft:diamond_sword[enchantments={sharpness:5}]`. */
    val item: String? = null,
    val amount: Int = 1,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val enchantments: Map<String, Int> = emptyMap(),
    val damage: Int? = null,
    val unbreakable: Boolean? = null,
    val format: TextFormat = TextFormat.PLAIN,
)

@Serializable
data class InventoryView(
    /** Slots 0–8 are the hotbar, 9–35 the rest of the main inventory. */
    val main: List<ItemView>,
    val armor: ArmorView,
    val offhand: ItemView? = null,
    /** The hotbar slot in hand, 0–8. */
    val heldSlot: Int,
)

/** Armor slots: 36 boots, 37 leggings, 38 chestplate, 39 helmet; 40 is the offhand. */
@Serializable
data class ArmorView(val head: ItemView? = null, val chest: ItemView? = null, val legs: ItemView? = null, val feet: ItemView? = null)

@Serializable
data class ContainerView(val size: Int, val items: List<ItemView>)

@Serializable
data class GiveRequest(val item: ItemInput)

@Serializable
data class GiveResult(
    val given: Int,
    /** Items that didn't fit and were dropped at the player's feet. */
    val dropped: Int,
)
