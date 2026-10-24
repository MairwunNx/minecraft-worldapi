package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.PaperCall
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.EndpointGroup
import com.mairwunnx.worldapi.core.PaperApi
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.bukkit.hides
import com.mairwunnx.worldapi.bukkit.knownPlayer
import com.mairwunnx.worldapi.bukkit.giveOrDrop
import com.mairwunnx.worldapi.bukkit.onlinePlayer
import com.mairwunnx.worldapi.bukkit.toItemStack
import com.mairwunnx.worldapi.bukkit.view
import com.mairwunnx.worldapi.bukkit.views
import com.mairwunnx.worldapi.events.inventoryView
import com.mairwunnx.worldapi.model.ContainerView
import com.mairwunnx.worldapi.model.GiveRequest
import com.mairwunnx.worldapi.model.GiveResult
import com.mairwunnx.worldapi.model.ItemInput
import com.mairwunnx.worldapi.model.ItemView
import com.mairwunnx.worldapi.model.TakeRequest
import com.mairwunnx.worldapi.model.TakeResult
import io.ktor.http.HttpStatusCode
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

private const val SLOTS = "Inventory slots: 0–8 hotbar, 9–35 main, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand. Ender chest slots: 0–26."
private const val READ = "$SLOTS An offline player's items come from their playerdata file, as last saved."
private const val WRITE = "$SLOTS The player must be online."

val inventoryRoutes = endpoints("Inventories", Scope.PLAYERS_READ) {
    get("/players/{player}/inventory", "Main inventory, armor, and offhand with full item data", description = READ, also = Scope.PLAYERS_PRIVATE) {
        requireInventories()
        val (player, online) = main { knownPlayer().let { it to it.player?.takeUnless(::hides) } }
        inventories(player, online).first
            ?: notFound("no_player_data", "No saved inventory for ${player.name ?: player.uniqueId}")
    }

    get("/players/{player}/enderchest", "Ender chest contents", description = READ, also = Scope.PLAYERS_PRIVATE) {
        requireInventories()
        val (player, online) = main { knownPlayer().let { it to it.player?.takeUnless(::hides) } }
        inventories(player, online).second
            ?: notFound("no_player_data", "No saved ender chest for ${player.name ?: player.uniqueId}")
    }

    for (kind in listOf("inventory", "enderchest")) containerWrites(kind)
}

private fun EndpointGroup<PaperApi>.containerWrites(kind: String) {
    val title = if (kind == "inventory") "inventory" else "ender chest"

    patch<ItemInput, ItemView>("/players/{player}/$kind/slots/{slot}", "Put an item in a $title slot", Scope.PLAYERS_WRITE, description = WRITE) { input ->
        main {
            val player = onlinePlayer()
            val container = container(player, kind)
            val slot = slot(container)
            val item = input.toItemStack()
            container.setItem(slot, item)
            auditTarget = "${player.name} $kind[$slot]"
            auditDetail = item.view()?.components
            item.view(slot)!!
        }
    }

    post<TakeRequest, TakeResult>(
        "/players/{player}/$kind/slots/{slot}/take", "Take some items from a $title slot", Scope.PLAYERS_WRITE,
        description = "$WRITE The rest of the stack keeps its enchantments, name, and the like.",
    ) { request ->
        main {
            val player = onlinePlayer()
            val container = container(player, kind)
            val slot = slot(container)
            auditTarget = "${player.name} $kind[$slot]"
            take(container, slot, request.amount)
        }
    }

    delete("/players/{player}/$kind/slots/{slot}", "Empty a $title slot", Scope.PLAYERS_WRITE, description = WRITE) {
        main {
            val player = onlinePlayer()
            val container = container(player, kind)
            val slot = slot(container)
            auditTarget = "${player.name} $kind[$slot]"
            container.setItem(slot, null)
        }
    }

    delete("/players/{player}/$kind", "Empty the whole $title", Scope.PLAYERS_WRITE) {
        main {
            val player = onlinePlayer()
            auditTarget = "${player.name} $kind"
            container(player, kind).clear()
        }
    }

    post<GiveRequest, GiveResult>(
        "/players/{player}/$kind/give", "Add an item to the $title",
        Scope.PLAYERS_WRITE,
        description = if (kind == "inventory") "Whatever doesn't fit drops at the player's feet." else "Whatever doesn't fit is lost and counted in `dropped`.",
    ) { request ->
        main {
            val player = onlinePlayer()
            val item = request.item.toItemStack()
            auditTarget = player.name
            auditDetail = item.view()?.components
            val amount = item.amount
            val dropped = if (kind == "inventory") player.giveOrDrop(item) else player.enderChest.addItem(item).values.sumOf { it.amount }
            GiveResult(amount - dropped, dropped)
        }
    }
}

private fun container(player: Player, kind: String): Inventory = if (kind == "inventory") player.inventory else player.enderChest

private fun PaperCall.slot(inventory: Inventory): Int {
    val slot = path("slot").toIntOrNull() ?: badRequest("slot must be a number")
    if (slot !in 0 until inventory.size) badRequest("slot must be between 0 and ${inventory.size - 1}")
    return slot
}

private fun PaperCall.requireInventories() {
    if (!seesInventories) throw ApiException(HttpStatusCode.Forbidden, "private", "Inventories are hidden from anonymous clients")
}

/** Takes up to [amount] from a slot, keeping the rest of the stack as it was. Server thread only. */
internal fun take(inventory: Inventory, slot: Int, amount: Int): TakeResult {
    if (amount < 1) badRequest("amount must be at least 1")
    val stack = inventory.getItem(slot)?.takeUnless { it.type.isAir } ?: return TakeResult(0)
    val taken = minOf(amount, stack.amount)
    if (taken == stack.amount) {
        inventory.setItem(slot, null)
        return TakeResult(taken)
    }
    stack.amount -= taken
    inventory.setItem(slot, stack)
    return TakeResult(taken, stack.view(slot))
}
