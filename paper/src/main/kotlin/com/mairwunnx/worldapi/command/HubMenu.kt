package com.mairwunnx.worldapi.command

import com.mairwunnx.worldapi.bukkit.clickSound
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the menu shows and does; the plugin supplies it. */
interface HubActions {
    fun status(): List<String>
    fun tokens(): List<TokenLine>
    fun accounts(): List<AccountLine>
    fun groups(): Map<String, List<String>>
    fun reload(): Result<String>
    fun adminCode(player: Player)
    /** Opens the token menu for a new token, or says why it can't. */
    fun createToken(player: Player, name: String): String?
    fun revokeToken(name: String): Result<String>
}

class TokenLine(val name: String, val scopes: List<String>, val detail: String, val issued: Boolean)
class AccountLine(val name: String, val groups: List<String>, val detail: String)

/**
 * `/worldapi` without arguments: WorldAPI's state and everything the subcommands do, in chest
 * windows. Nothing in them is a real item; clicks become actions. A new token's name is asked in
 * chat, since a chest can't take text.
 */
class HubMenu(private val plugin: Plugin, private val actions: HubActions) : Listener {
    private enum class Page { HOME, TOKENS, ACCOUNTS, GROUPS }

    private class View(val player: Player, val page: Page, val slots: MutableMap<Int, () -> Unit> = mutableMapOf(), val shift: MutableMap<Int, () -> Unit> = mutableMapOf()) : InventoryHolder {
        lateinit var window: Inventory
        override fun getInventory() = window
    }

    /** Players asked to type a token name; their next chat line answers. */
    private val prompts = ConcurrentHashMap<UUID, (String) -> Unit>()

    fun open(player: Player) = show(player, Page.HOME)

    private fun show(player: Player, page: Page) {
        val view = View(player, page)
        val title = when (page) {
            Page.HOME -> "WorldAPI"
            Page.TOKENS -> "WorldAPI: tokens"
            Page.ACCOUNTS -> "WorldAPI: panel accounts"
            Page.GROUPS -> "WorldAPI: groups"
        }
        view.window = plugin.server.createInventory(view, 54, Component.text(title))
        val filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), emptyList())
        (0 until 54).forEach { view.window.setItem(it, filler) }
        when (page) {
            Page.HOME -> home(view)
            Page.TOKENS -> tokens(view)
            Page.ACCOUNTS -> accounts(view)
            Page.GROUPS -> groups(view)
        }
        if (page != Page.HOME) put(view, 45, item(Material.ARROW, title("Back", NamedTextColor.WHITE), emptyList())) { show(player, Page.HOME) }
        put(view, 49, item(Material.BARRIER, title("Close", NamedTextColor.RED), emptyList())) { player.closeInventory() }
        player.openInventory(view.window)
    }

    private fun home(view: View) {
        val player = view.player
        put(view, 4, item(Material.NETHER_STAR, title("WorldAPI", NamedTextColor.AQUA), actions.status().map(::gray)))
        put(view, 20, item(Material.FILLED_MAP, title("Admin panel", NamedTextColor.GREEN), listOf(gray("A code for the panel in your chat,"), gray("with a link when panel.publicUrl is set."), Component.empty(), hint("Click to get a code")))) {
            player.closeInventory()
            actions.adminCode(player)
        }
        put(view, 22, item(Material.NAME_TAG, title("Tokens", NamedTextColor.YELLOW), listOf(gray("${actions.tokens().size} in all"), Component.empty(), hint("Click to see, create, and revoke")))) { show(player, Page.TOKENS) }
        put(view, 24, item(Material.PLAYER_HEAD, title("Panel accounts", NamedTextColor.YELLOW), listOf(gray("${actions.accounts().size} in all"), Component.empty(), hint("Click to see them")))) { show(player, Page.ACCOUNTS) }
        put(view, 30, item(Material.BOOK, title("Groups", NamedTextColor.YELLOW), listOf(gray("What each group may do"), Component.empty(), hint("Click to see them")))) { show(player, Page.GROUPS) }
        put(view, 32, item(Material.LEVER, title("Reload config.yml", NamedTextColor.GOLD), listOf(gray("Applies changes without a restart"), Component.empty(), hint("Click to reload")))) {
            player.closeInventory()
            actions.reload().fold(
                { player.sendMessage(Component.text(it, NamedTextColor.GREEN)) },
                { player.sendMessage(Component.text("WorldAPI kept the old config: ${it.message}", NamedTextColor.RED)) },
            )
        }
    }

    private fun tokens(view: View) {
        val player = view.player
        actions.tokens().take(36).forEachIndexed { index, token ->
            val lore = listOf(gray(token.scopes.joinToString()), gray(token.detail)) +
                if (token.issued) listOf(Component.empty(), hint("Shift-click to revoke")) else listOf(Component.empty(), gray("Lives in config.yml"))
            val slot = 9 + index
            put(view, slot, item(if (token.issued) Material.PAPER else Material.MAP, title(token.name, NamedTextColor.WHITE), lore))
            if (token.issued) view.shift[slot] = {
                actions.revokeToken(token.name).fold(
                    { player.sendMessage(Component.text(it, NamedTextColor.GREEN)) },
                    { player.sendMessage(Component.text(it.message ?: "Couldn't revoke", NamedTextColor.RED)) },
                )
                show(player, Page.TOKENS)
            }
        }
        put(view, 4, item(Material.LIME_CONCRETE, title("Create a token", NamedTextColor.GREEN), listOf(gray("Type its name in chat,"), gray("then pick groups and a lifetime."), Component.empty(), hint("Click to start")))) {
            player.closeInventory()
            player.sendMessage(Component.text("Type the new token's name in chat: 1–32 letters, digits, dashes, or underscores. “cancel” stops.", NamedTextColor.YELLOW))
            prompts[player.uniqueId] = { name ->
                if (!name.equals("cancel", ignoreCase = true)) actions.createToken(player, name)?.let { player.sendMessage(Component.text(it, NamedTextColor.RED)) }
            }
        }
    }

    private fun accounts(view: View) {
        val accounts = actions.accounts()
        if (accounts.isEmpty()) {
            put(view, 22, item(Material.PAPER, title("No accounts yet", NamedTextColor.WHITE), listOf(gray("Get a code with Admin panel"), gray("on the first page."))))
        }
        accounts.take(36).forEachIndexed { index, account ->
            put(view, 9 + index, item(Material.PLAYER_HEAD, title(account.name, NamedTextColor.WHITE), listOf(gray(account.groups.joinToString()), gray(account.detail))))
        }
    }

    private fun groups(view: View) {
        actions.groups().entries.take(36).forEachIndexed { index, (name, permissions) ->
            put(view, 9 + index, item(Material.BOOK, title(name, NamedTextColor.WHITE), permissions.chunked(3).map { gray(it.joinToString()) }))
        }
    }

    private fun put(view: View, slot: Int, item: ItemStack, action: (() -> Unit)? = null) {
        view.window.setItem(slot, item)
        action?.let { view.slots[slot] = it }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val view = event.inventory.holder as? View ?: return
        // Nothing in these windows is a real item: no taking, no placing, no shift-clicking.
        event.isCancelled = true
        if (event.clickedInventory != view.window) return
        val action = (if (event.isShiftClick) view.shift[event.rawSlot] else null) ?: view.slots[event.rawSlot] ?: return
        view.player.clickSound()
        action()
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is View) event.isCancelled = true
    }

    /** A token name typed after “Create a token” never reaches public chat. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val answer = prompts.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        plugin.server.scheduler.runTask(plugin, Runnable { answer(text) })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        prompts.remove(event.player.uniqueId)
    }

    private fun title(text: String, color: NamedTextColor) = Component.text(text, color)
    private fun gray(text: String) = Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
    private fun hint(text: String) = Component.text(text, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)

    private fun item(material: Material, name: Component, lore: List<Component>) = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
            meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        }
    }
}
