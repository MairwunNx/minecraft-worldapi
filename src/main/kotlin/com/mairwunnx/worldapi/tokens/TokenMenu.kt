package com.mairwunnx.worldapi.tokens

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

/** The chest menu behind `/worldapi token create <name>`: pick scopes and a lifetime, then create. */
class TokenMenu(
    private val plugin: Plugin,
    private val store: TokenStore,
    private val scriptsEnabled: () -> Boolean,
    private val logger: Logger,
) : Listener {
    private class Choice(val label: String, val duration: Duration?)

    private class Session(val name: String, val player: Player) : InventoryHolder {
        val scopes = mutableSetOf(Scope.READ)
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

    private val scopeSlots = mapOf(10 to Scope.READ, 11 to Scope.WRITE_WORLD, 12 to Scope.WRITE_PLAYERS, 13 to Scope.ADMIN, 14 to Scope.CODE)
    private val lifetimeSlot = 16
    private val createSlot = 22
    private val cancelSlot = 26

    fun open(player: Player, name: String) {
        val session = Session(name, player)
        session.view = plugin.server.createInventory(session, 27, Component.text("New token: $name"))
        render(session)
        player.openInventory(session.view)
    }

    private fun render(session: Session) {
        val view = session.view
        view.clear()
        val filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), emptyList())
        (0 until view.size).forEach { view.setItem(it, filler) }
        scopeSlots.forEach { (slot, scope) -> view.setItem(slot, scopeItem(scope, scope in session.scopes)) }
        val lifetime = lifetimes[session.lifetime]
        view.setItem(lifetimeSlot, item(Material.CLOCK, Component.text("Lifetime: ${lifetime.label}", NamedTextColor.YELLOW), listOf(gray("Click to change"))))
        val ready = session.scopes.isNotEmpty()
        view.setItem(
            createSlot,
            item(
                if (ready) Material.LIME_CONCRETE else Material.GRAY_CONCRETE,
                Component.text("Create token", if (ready) NamedTextColor.GREEN else NamedTextColor.GRAY),
                listOf(gray(if (ready) "Scopes: ${session.scopes.sortedBy { it.ordinal }.joinToString { it.id }}" else "Pick at least one scope")),
            ),
        )
        view.setItem(cancelSlot, item(Material.BARRIER, Component.text("Cancel", NamedTextColor.RED), emptyList()))
    }

    private fun scopeItem(scope: Scope, on: Boolean): ItemStack {
        val (material, description) = when (scope) {
            Scope.READ -> Material.BOOK to listOf("Every GET and the WebSocket")
            Scope.WRITE_WORLD -> Material.GRASS_BLOCK to listOf("Time, weather, rules, blocks,", "entities, explosions")
            Scope.WRITE_PLAYERS -> Material.PLAYER_HEAD to listOf("Player state, teleports,", "inventories, effects")
            Scope.ADMIN -> Material.NETHER_STAR to listOf("Everything above, plus bans,", "ops, and console commands")
            Scope.CODE -> Material.COMMAND_BLOCK to listOf("Run Java on the server.", "Only for people you'd give SSH.") +
                if (scriptsEnabled()) emptyList() else listOf("Scripts are off in config.yml")
        }
        val lore = description.map(::gray) + Component.empty() +
            Component.text(if (on) "On, click to turn off" else "Off, click to turn on", if (on) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY)
        return item(material, Component.text(scope.id, if (on) NamedTextColor.GREEN else NamedTextColor.WHITE), lore).apply {
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
            in scopeSlots -> {
                val scope = scopeSlots.getValue(slot)
                if (!session.scopes.remove(scope)) session.scopes += scope
                click(player)
                render(session)
            }
            lifetimeSlot -> {
                session.lifetime = (session.lifetime + 1) % lifetimes.size
                click(player)
                render(session)
            }
            createSlot -> if (session.scopes.isNotEmpty()) create(session)
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
            store.issue(session.name, session.scopes, player.name, expires)
        } catch (error: IllegalArgumentException) {
            player.sendMessage(Component.text(error.message ?: "Couldn't create the token", NamedTextColor.RED))
            return
        }
        // The log gets the name and scopes; the token goes only to this player's chat.
        logger.info("${player.name} created token \"${session.name}\" with ${session.scopes.joinToString { it.id }}" + (expires?.let { ", expires $it" } ?: ""))
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
