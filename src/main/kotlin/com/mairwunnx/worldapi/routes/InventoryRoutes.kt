package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.EndpointGroup
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
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
import io.ktor.http.HttpStatusCode
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

private const val SLOTS = "Inventory slots: 0–8 hotbar, 9–35 main, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand. Ender chest slots: 0–26. The player must be online."

val inventoryRoutes = endpoints("Inventories") {
    get("/players/{player}/inventory", "Main inventory, armor, and offhand with full item data", description = SLOTS) {
        requireInventories()
        main { inventoryView(onlinePlayer()) }
    }

    get("/players/{player}/enderchest", "Ender chest contents", description = SLOTS) {
        requireInventories()
        main { onlinePlayer().enderChest.let { ContainerView(it.size, it.views()) } }
    }

    for (kind in listOf("inventory", "enderchest")) containerWrites(kind)
}

private fun EndpointGroup.containerWrites(kind: String) {
    val title = if (kind == "inventory") "inventory" else "ender chest"

    patch<ItemInput, ItemView>("/players/{player}/$kind/slots/{slot}", "Put an item in a $title slot", Scope.WRITE_PLAYERS, description = SLOTS) { input ->
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

    delete("/players/{player}/$kind/slots/{slot}", "Empty a $title slot", Scope.WRITE_PLAYERS, description = SLOTS) {
        main {
            val player = onlinePlayer()
            val container = container(player, kind)
            val slot = slot(container)
            auditTarget = "${player.name} $kind[$slot]"
            container.setItem(slot, null)
        }
    }

    delete("/players/{player}/$kind", "Empty the whole $title", Scope.WRITE_PLAYERS) {
        main {
            val player = onlinePlayer()
            auditTarget = "${player.name} $kind"
            container(player, kind).clear()
        }
    }

    post<GiveRequest, GiveResult>(
        "/players/{player}/$kind/give", "Add an item to the $title",
        Scope.WRITE_PLAYERS,
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

private fun ApiCall.slot(inventory: Inventory): Int {
    val slot = path("slot").toIntOrNull() ?: badRequest("slot must be a number")
    if (slot !in 0 until inventory.size) badRequest("slot must be between 0 and ${inventory.size - 1}")
    return slot
}

private fun ApiCall.requireInventories() {
    if (!seesInventories) throw ApiException(HttpStatusCode.Forbidden, "private", "Inventories are hidden from anonymous clients")
}
