package com.mairwunnx.worldapi.tokens

import com.mairwunnx.worldapi.api.Groups
import com.mairwunnx.worldapi.api.Scope
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

/** The chest menu behind `/worldapi token create <name>`: pick groups and a lifetime, then create. */
class TokenMenu(
    private val plugin: Plugin,
    private val store: TokenStore,
    /** auth.groups from config.yml, read each time the menu opens. */
    private val groups: () -> Map<String, List<String>>,
    private val logger: Logger,
) : Listener {
    private class Choice(val label: String, val duration: Duration?)

    private class Session(val name: String, val player: Player, val offered: List<String>, val resolver: Groups) : InventoryHolder {
        val groups = mutableSetOf<String>()
        var lifetime = 0
        lateinit var view: Inventory
        override fun getInventory() = view
    }

    private val lifetimes = listOf(
        Choice("Never expires", null),
        Choice("1 day", Duration.ofDays(1)),
        Choice("7 days", Duration.ofDays(7)),
        Choice("30 days", Duration.ofDays(30)),
        Choice("90 days", Duration.ofDays(90)),
    )

    // The first six groups of config.yml; more are for the admin panel.
    private val groupSlots = (10..15).toList()
    private val lifetimeSlot = 16
    private val createSlot = 22
    private val cancelSlot = 26

    fun open(player: Player, name: String) {
        val all = groups()
        val session = Session(name, player, all.keys.take(groupSlots.size), Groups(all))
        session.offered.firstOrNull()?.let(session.groups::add)
        session.view = plugin.server.createInventory(session, 27, Component.text("New token: $name"))
        render(session)
        player.openInventory(session.view)
    }

    private fun render(session: Session) {
        val view = session.view
        view.clear()
        val filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), emptyList())
        (0 until view.size).forEach { view.setItem(it, filler) }
        session.offered.forEachIndexed { index, group -> view.setItem(groupSlots[index], groupItem(session, group, group in session.groups)) }
        val lifetime = lifetimes[session.lifetime]
        view.setItem(lifetimeSlot, item(Material.CLOCK, Component.text("Lifetime: ${lifetime.label}", NamedTextColor.YELLOW), listOf(gray("Click to change"))))
        val ready = session.groups.isNotEmpty()
        view.setItem(
            createSlot,
            item(
                if (ready) Material.LIME_CONCRETE else Material.GRAY_CONCRETE,
                Component.text("Create token", if (ready) NamedTextColor.GREEN else NamedTextColor.GRAY),
                listOf(gray(if (ready) "Groups: ${session.groups.joinToString()}" else "Pick at least one group")),
            ),
        )
        view.setItem(cancelSlot, item(Material.BARRIER, Component.text("Cancel", NamedTextColor.RED), emptyList()))
    }

    private fun groupItem(session: Session, group: String, on: Boolean): ItemStack {
        val scopes = session.resolver.resolve(listOf(group))
        val material = when {
            Scope.CODE in scopes || Scope.FILES_WRITE in scopes -> Material.COMMAND_BLOCK
            Scope.SERVER_CONSOLE in scopes -> Material.NETHER_STAR
            Scope.PLAYERS_MODERATE in scopes -> Material.IRON_SWORD
            scopes.any { it.id.endsWith(":write") } -> Material.GRASS_BLOCK
            else -> Material.BOOK
        }
        // Lore lines stay short: a chest tooltip wraps nothing.
        val description = scopes.sortedBy { it.ordinal }.map { it.id }.chunked(3).map { it.joinToString() }
        val warning = when {
            Scope.CODE in scopes -> listOf("Runs Java on the server.", "Only for people you'd give SSH.")
            Scope.FILES_WRITE in scopes -> listOf("Changes server and plugin configs.")
            else -> emptyList()
        }
        val lore = description.map(::gray) + warning.map { Component.text(it, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false) } + Component.empty() +
            Component.text(if (on) "On, click to turn off" else "Off, click to turn on", if (on) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY)
        return item(material, Component.text(group, if (on) NamedTextColor.GREEN else NamedTextColor.WHITE), lore).apply {
            editMeta { it.setEnchantmentGlintOverride(on) }
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val session = event.inventory.holder as? Session ?: return
        // Nothing in this menu is a real item: no taking, no placing, no shift-clicking.
        event.isCancelled = true
        if (event.clickedInventory != session.view) return
        val player = session.player
        when (val slot = event.rawSlot) {
            in groupSlots.take(session.offered.size) -> {
                val group = session.offered[groupSlots.indexOf(slot)]
                if (!session.groups.remove(group)) session.groups += group
                click(player)
                render(session)
            }
            lifetimeSlot -> {
                session.lifetime = (session.lifetime + 1) % lifetimes.size
                click(player)
                render(session)
            }
            createSlot -> if (session.groups.isNotEmpty()) create(session)
            cancelSlot -> player.closeInventory()
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is Session) event.isCancelled = true
    }

    private fun create(session: Session) {
        val player = session.player
        // Close first: the menu must not linger with the token's details on screen.
        player.closeInventory()
        val expires = lifetimes[session.lifetime].duration?.let { Instant.now().plus(it) }
        val token = try {
            store.issue(session.name, session.groups.toList(), player.name, expires)
        } catch (error: IllegalArgumentException) {
            player.sendMessage(Component.text(error.message ?: "Couldn't create the token", NamedTextColor.RED))
            return
        }
        // The log gets the name and scopes; the token goes only to this player's chat.
        logger.info("${player.name} created token \"${session.name}\" with ${session.groups.joinToString()}" + (expires?.let { ", expires $it" } ?: ""))
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f)
        player.sendMessage(Component.text("Token \"${session.name}\" is ready. It's shown only this once:", NamedTextColor.GREEN))
        player.sendMessage(
            Component.text("[Click to copy]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.copyToClipboard(token))
                .hoverEvent(HoverEvent.showText(Component.text("Copies the token to your clipboard"))),
        )
        player.sendMessage(gray("Send it as \"Authorization: Bearer <token>\". Revoke with /worldapi token revoke ${session.name}."))
    }

    private fun click(player: Player) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.4f, 1.4f)

    private fun gray(text: String) = Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)

    private fun item(material: Material, name: Component, lore: List<Component>) = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
            meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        }
    }
}
