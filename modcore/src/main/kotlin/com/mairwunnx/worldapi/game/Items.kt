package com.mairwunnx.worldapi.game

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
import com.mojang.brigadier.StringReader
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.SnbtPrinterTagVisitor
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.Containers
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.SuspiciousStewEffects
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.commands.arguments.item.ItemParser
import java.util.StringJoiner

/** Items a shulker box or bundle lists at most; vanilla ones never hold more. */
const val MAX_CONTENTS = 64

val ItemStack.typeKey: String get() = BuiltInRegistries.ITEM.getKey(item).toString()

/**
 * An item as Paper's `ItemStack.view` shows it, read from the game's components. Must run on the server thread. Items
 * inside a container item come without their own contents.
 */
fun ItemStack?.view(slot: Int? = null, contents: Boolean = true, registries: HolderLookup.Provider? = null): ItemView? {
    if (this == null || isEmpty || count <= 0) return null
    val patch = componentsPatch
    val maxDurability = prototype.getOrDefault(DataComponents.MAX_DAMAGE, 0)
    return ItemView(
        slot = slot,
        type = typeKey,
        amount = count,
        name = get(DataComponents.CUSTOM_NAME)?.plain(),
        lore = get(DataComponents.LORE)?.lines()?.map { it.plain() } ?: emptyList(),
        enchantments = enchantments.keys(),
        damage = damageValue.takeIf { maxDurability > 0 },
        maxDamage = maxDurability.takeIf { it > 0 },
        unbreakable = has(DataComponents.UNBREAKABLE),
        customModelData = get(DataComponents.CUSTOM_MODEL_DATA)?.floats()?.firstOrNull()?.toInt(),
        rarity = patch.get(prototype, DataComponents.RARITY)?.takeIf { patch.split().added.has(DataComponents.RARITY) }?.serializedName,
        storedEnchantments = get(DataComponents.STORED_ENCHANTMENTS)?.takeIf { !it.isEmpty }?.keys(),
        attributes = get(DataComponents.ATTRIBUTE_MODIFIERS)?.views(),
        potion = potionView(get(DataComponents.POTION_CONTENTS), getOrDefault(DataComponents.POTION_DURATION_SCALE, 1f), get(DataComponents.SUSPICIOUS_STEW_EFFECTS)),
        trim = get(DataComponents.TRIM)?.let { TrimView(it.material().key(), it.pattern().key()) },
        dyedColor = get(DataComponents.DYED_COLOR)?.rgb()?.let(::hex),
        repairCost = get(DataComponents.REPAIR_COST)?.takeIf { it > 0 },
        book = get(DataComponents.WRITTEN_BOOK_CONTENT)?.let {
            BookView(it.title().raw(), it.author(), listOf("original", "copy_of_original", "copy_of_copy", "tattered").getOrElse(it.generation()) { "tattered" }, it.pages().size)
        },
        firework = get(DataComponents.FIREWORKS)?.let { fireworks -> FireworkView(fireworks.flightDuration(), fireworks.explosions().map { it.view() }) }
            ?: get(DataComponents.FIREWORK_EXPLOSION)?.let { FireworkView(explosions = listOf(it.view())) },
        contents = if (!contents) null else {
            (get(DataComponents.CONTAINER)?.itemCopies() ?: get(DataComponents.BUNDLE_CONTENTS)?.itemCopies())
                ?.toList()?.asSequence()?.mapIndexedNotNull { index, item -> item.view(index, contents = false, registries = registries) }
                ?.take(MAX_CONTENTS)?.toList()?.takeIf { it.isNotEmpty() }
        },
        components = typeKey + componentString(registries),
    )
}

private fun net.minecraft.core.Holder<*>.key(): String = unwrapKey().map { it.identifier().toString() }.orElse("unknown")

private fun ItemEnchantments.keys(): Map<String, Int> = entrySet().associate { it.key.key() to it.intValue }

/**
 * The item's own components in command syntax, as Paper's `ItemMeta.getAsComponentString` writes them: `[key=snbt,...]`,
 * with `!key` for a component the item drops, and `[]` without any.
 */
fun ItemStack.componentString(registries: HolderLookup.Provider? = null): String {
    val ops = registries?.createSerializationContext(NbtOps.INSTANCE) ?: NbtOps.INSTANCE
    val joiner = StringJoiner(",", "[", "]")
    val split = componentsPatch.split()
    for (typed in split.added()) {
        val type = typed.type()
        if (type.isTransient) continue
        val key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)?.toString() ?: continue
        @Suppress("UNCHECKED_CAST")
        val codec = (type as net.minecraft.core.component.DataComponentType<Any>).codec() ?: continue
        val tag = codec.encodeStart(ops, typed.value()).result().orElse(null) ?: continue
        joiner.add(key + "=" + SnbtPrinterTagVisitor("", 0, ArrayList()).visit(tag))
    }
    for (type in split.removed()) {
        if (type.isTransient) continue
        BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)?.let { joiner.add("!$it") }
    }
    return joiner.toString()
}

/** Modifiers that change nothing, like leather armor's zero toughness, are left out. */
fun ItemAttributeModifiers.views(): List<AttributeModifierView>? = modifiers().filter { it.modifier().amount() != 0.0 }.map { entry ->
    val modifier = entry.modifier()
    AttributeModifierView(
        attribute = entry.attribute().key(),
        // The game keeps some defaults as floats: round −2.4000000953674316 back to −2.4.
        amount = Math.round(modifier.amount() * 1e6) / 1e6,
        operation = modifier.operation().serializedName,
        slot = entry.slot().serializedName,
        id = modifier.id().toString(),
    )
}.takeIf { it.isNotEmpty() }

/** Durations get the item's scale, as the tooltip shows them: tipped arrows give an eighth, lingering potions a quarter. */
fun potionView(contents: PotionContents?, durationScale: Float, stew: SuspiciousStewEffects?): PotionView? {
    if (contents != null) {
        val effects = contents.allEffects.map { effect ->
            val duration = if (effect.isInfiniteDuration) -1 else (effect.duration * durationScale).toInt()
            PotionEffectView(effect.effect.key(), effect.amplifier, duration)
        }
        val type = contents.potion().map { it.key() }.orElse(null)
        val color = contents.customColor().map { hex(it) }.orElse(null)
        if (type != null || effects.isNotEmpty() || color != null) return PotionView(type, effects, color)
    }
    val stewEffects = stew?.effects()?.takeIf { it.isNotEmpty() } ?: return null
    return PotionView(effects = stewEffects.map { PotionEffectView(it.effect().key(), 0, it.duration()) })
}

fun FireworkExplosion.view() = FireworkExplosionView(
    shape = shape().serializedName,
    colors = colors().map { hex(it) },
    fadeColors = fadeColors().map { hex(it) }.takeIf { it.isNotEmpty() },
    trail = hasTrail().takeIf { it },
    twinkle = hasTwinkle().takeIf { it },
)

fun hex(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)

fun Container.views(registries: HolderLookup.Provider? = null): List<ItemView> = (0 until containerSize).mapNotNull { slot -> getItem(slot).view(slot, registries = registries) }

/** Builds an item from a request, as Paper does. Must run on the server thread: items touch registries. */
fun ItemInput.toItemStack(server: MinecraftServer): ItemStack {
    if (amount !in 1..6400) badRequest("amount must be between 1 and 6400")
    val command = item
    val typeName = type
    val registries = server.registryAccess()
    val stack = when {
        command != null -> runCatching { ItemParser(registries).parse(StringReader(command)).createItemStack(1) }
            .getOrElse { badRequest("Can't parse item \"$command\": ${it.message}", "invalid_item") }
        typeName != null -> {
            val id = Identifier.tryParse(typeName.lowercase()) ?: badRequest("Unknown item \"$typeName\"", "unknown_item")
            val item = BuiltInRegistries.ITEM.getOptional(id).orElse(null)?.takeIf { it != Items.AIR } ?: badRequest("Unknown item \"$typeName\"", "unknown_item")
            ItemStack(item)
        }
        else -> badRequest("Give either type or item")
    }
    stack.count = amount
    name?.let { stack.set(DataComponents.CUSTOM_NAME, text(it, format, 256)) }
    if (lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(lore.map { text(it, format, 256) }))
    unbreakable?.let { if (it) stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE) else stack.remove(DataComponents.UNBREAKABLE) }
    damage?.let { if (stack.isDamageableItem) stack.damageValue = it }
    if (enchantments.isNotEmpty()) {
        val lookup = registries.lookupOrThrow(Registries.ENCHANTMENT)
        val holders = enchantments.map { (key, level) ->
            if (level !in 1..255) badRequest("Enchantment levels go from 1 to 255")
            val id = Identifier.tryParse(key.lowercase()) ?: badRequest("\"$key\" isn't a valid enchantment")
            lookup.get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, id)).orElse(null) ?: badRequest("Unknown enchantment \"$key\"", "unknown_enchantment")
        }.zip(enchantments.values)
        // As Bukkit's unsafe enchanting: the level is set as asked, past the usual cap and conflicts alike.
        EnchantmentHelper.updateEnchantments(stack) { mutable -> holders.forEach { (holder, level) -> mutable.set(holder, level) } }
    }
    return stack
}

/** Gives a stack; whatever doesn't fit gets dropped at the player's feet. Returns how many dropped. */
fun ServerPlayer.giveOrDrop(stack: ItemStack): Int {
    val left = stack.copy()
    inventory.add(left)
    if (left.isEmpty) return 0
    val dropped = left.count
    Containers.dropItemStack(level(), x, y, z, left)
    return dropped
}
