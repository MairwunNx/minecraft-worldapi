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
    /** Enchantments an enchanted book holds for an anvil, key to level. */
    val storedEnchantments: Map<String, Int>? = null,
    /**
     * Attribute modifiers the item applies, as the game uses them: its type's defaults, such as a
     * sword's attack damage and speed or armor's armor points, unless the item replaces them with
     * its own. The tooltip adds the player's base values: attack damage shows 1 more (a diamond
     * sword's 6 reads as 7) and attack speed 4 more (−2.4 reads as 1.6).
     */
    val attributes: List<AttributeModifierView>? = null,
    /** Effects of a potion, tipped arrow, or suspicious stew. */
    val potion: PotionView? = null,
    /** Armor trim. */
    val trim: TrimView? = null,
    /** Dye color of leather armor and the like, as `#rrggbb`. */
    val dyedColor: String? = null,
    /** Extra levels an anvil charges for working this item, from earlier anvil uses. */
    val repairCost: Int? = null,
    /** Title and author of a written book; its pages aren't included. */
    val book: BookView? = null,
    /** Flight and explosions of a firework rocket or star. */
    val firework: FireworkView? = null,
    /**
     * Items inside a shulker box, bundle, or other container item, up to 64. Items in it don't
     * list their own contents.
     */
    val contents: List<ItemView>? = null,
    /** The full item in command syntax, such as `minecraft:diamond_sword[enchantments={sharpness:5}]`. */
    val components: String,
)

@Serializable
data class AttributeModifierView(
    /** Attribute key, such as `minecraft:attack_damage`. */
    val attribute: String,
    val amount: Double,
    /** `add_value`, `add_multiplied_base`, or `add_multiplied_total`, as in commands. */
    val operation: String,
    /** Where the item must be to apply it: `mainhand`, `offhand`, `hand`, `head`, `chest`, `legs`, `feet`, `armor`, `body`, `saddle`, or `any`. */
    val slot: String,
    /** Modifier id, such as `minecraft:base_attack_damage` for a type's default. */
    val id: String,
)

@Serializable
data class PotionView(
    /** Base potion, such as `minecraft:long_swiftness`; absent for suspicious stew and potions with only custom effects. */
    val type: String? = null,
    /** Every effect it gives, the base potion's and custom ones, with durations as the tooltip shows them (shorter on tipped arrows and lingering potions). */
    val effects: List<PotionEffectView> = emptyList(),
    /** Custom color as `#rrggbb`. */
    val color: String? = null,
)

@Serializable
data class PotionEffectView(
    /** Effect key, such as `minecraft:speed`. */
    val type: String,
    /** 0 is level I. */
    val amplifier: Int,
    /** Ticks; −1 lasts forever. */
    val duration: Int,
)

@Serializable
data class TrimView(
    /** Such as `minecraft:diamond`. */
    val material: String,
    /** Such as `minecraft:sentry`. */
    val pattern: String,
)

@Serializable
data class BookView(
    val title: String,
    val author: String,
    /** `original`, `copy_of_original`, `copy_of_copy`, or `tattered`. */
    val generation: String,
    val pages: Int,
)

@Serializable
data class FireworkView(
    /** Flight duration of a rocket, 1–3 as in crafting; absent on a firework star. */
    val flight: Int? = null,
    val explosions: List<FireworkExplosionView> = emptyList(),
)

@Serializable
data class FireworkExplosionView(
    /** `small_ball`, `large_ball`, `star`, `creeper`, or `burst`. */
    val shape: String,
    /** `#rrggbb` each. */
    val colors: List<String>,
    val fadeColors: List<String>? = null,
    val trail: Boolean? = null,
    val twinkle: Boolean? = null,
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
data class TakeRequest(
    /** How many to take from the slot; more than it holds empties it. */
    val amount: Int,
)

@Serializable
data class TakeResult(
    val taken: Int,
    /** What's left in the slot; absent when it emptied. */
    val item: ItemView? = null,
)

@Serializable
data class GiveResult(
    val given: Int,
    /** Items that didn't fit and were dropped at the player's feet. */
    val dropped: Int,
)
