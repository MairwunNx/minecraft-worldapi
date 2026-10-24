package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.EndpointGroup
import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.core.ModApi
import com.mairwunnx.worldapi.game.giveOrDrop
import com.mairwunnx.worldapi.game.hides
import com.mairwunnx.worldapi.game.knownPlayer
import com.mairwunnx.worldapi.game.onlinePlayer
import com.mairwunnx.worldapi.game.toItemStack
import com.mairwunnx.worldapi.game.view
import com.mairwunnx.worldapi.model.GiveRequest
import com.mairwunnx.worldapi.model.GiveResult
import com.mairwunnx.worldapi.model.ItemInput
import com.mairwunnx.worldapi.model.ItemView
import com.mairwunnx.worldapi.model.TakeRequest
import com.mairwunnx.worldapi.model.TakeResult
import io.ktor.http.HttpStatusCode
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack

private const val SLOTS = "Inventory slots: 0–8 hotbar, 9–35 main, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand. Ender chest slots: 0–26."
private const val READ = "$SLOTS An offline player's items come from their playerdata file, as last saved."
private const val WRITE = "$SLOTS The player must be online."

val inventoryRoutes = endpoints("Inventories", Scope.PLAYERS_READ) {
    get("/players/{player}/inventory", "Main inventory, armor, and offhand with full item data", description = READ, also = Scope.PLAYERS_PRIVATE) {
        requireInventories()
        val (player, online) = main { knownPlayer().let { it to api.server.playerList.getPlayer(it.uuid)?.takeUnless(::hides) } }
        inventories(player, online).first
            ?: notFound("no_player_data", "No saved inventory for ${player.name ?: player.uuid}")
    }

    get("/players/{player}/enderchest", "Ender chest contents", description = READ, also = Scope.PLAYERS_PRIVATE) {
        requireInventories()
        val (player, online) = main { knownPlayer().let { it to api.server.playerList.getPlayer(it.uuid)?.takeUnless(::hides) } }
        inventories(player, online).second
            ?: notFound("no_player_data", "No saved ender chest for ${player.name ?: player.uuid}")
    }

    for (kind in listOf("inventory", "enderchest")) containerWrites(kind)
}

private fun EndpointGroup<ModApi>.containerWrites(kind: String) {
    val title = if (kind == "inventory") "inventory" else "ender chest"

    patch<ItemInput, ItemView>("/players/{player}/$kind/slots/{slot}", "Put an item in a $title slot", Scope.PLAYERS_WRITE, description = WRITE) { input ->
        main {
            val player = onlinePlayer()
            val container = container(player, kind)
            val slot = slot(container)
            val item = input.toItemStack(api.server)
            container.setItem(slot, item)
            auditTarget = "${player.gameProfile.name()} $kind[$slot]"
            auditDetail = item.view(registries = api.server.registryAccess())?.components
            item.view(slot, registries = api.server.registryAccess())!!
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
            auditTarget = "${player.gameProfile.name()} $kind[$slot]"
            take(container, slot, request.amount, api)
        }
    }

    delete("/players/{player}/$kind/slots/{slot}", "Empty a $title slot", Scope.PLAYERS_WRITE, description = WRITE) {
        main {
            val player = onlinePlayer()
            val container = container(player, kind)
            val slot = slot(container)
            auditTarget = "${player.gameProfile.name()} $kind[$slot]"
            container.setItem(slot, ItemStack.EMPTY)
        }
    }

    delete("/players/{player}/$kind", "Empty the whole $title", Scope.PLAYERS_WRITE) {
        main {
            val player = onlinePlayer()
            auditTarget = "${player.gameProfile.name()} $kind"
            container(player, kind).clearContent()
        }
    }

    post<GiveRequest, GiveResult>(
        "/players/{player}/$kind/give", "Add an item to the $title",
        Scope.PLAYERS_WRITE,
        description = if (kind == "inventory") "Whatever doesn't fit drops at the player's feet." else "Whatever doesn't fit is lost and counted in `dropped`.",
    ) { request ->
        main {
            val player = onlinePlayer()
            val item = request.item.toItemStack(api.server)
            auditTarget = player.gameProfile.name()
            auditDetail = item.view(registries = api.server.registryAccess())?.components
            val amount = item.count
            val dropped = if (kind == "inventory") player.giveOrDrop(item) else player.enderChestInventory.addItem(item).count
            GiveResult(amount - dropped, dropped)
        }
    }
}

/** The player's inventory in Bukkit's numbering, or their ender chest. Bukkit counts 41 slots: 36 storage, 4 armor, the offhand. */
private fun container(player: ServerPlayer, kind: String): Container = if (kind == "inventory") player.inventory else player.enderChestInventory

/** Bukkit's player inventory has 41 slots; the game's says 41 too, with 36–39 armor feet to head and 40 the offhand. */
private val Container.slots: Int get() = if (this is net.minecraft.world.entity.player.Inventory) 41 else containerSize

private fun ModCall.slot(container: Container): Int {
    val slot = path("slot").toIntOrNull() ?: badRequest("slot must be a number")
    if (slot !in 0 until container.slots) badRequest("slot must be between 0 and ${container.slots - 1}")
    return slot
}

private fun ModCall.requireInventories() {
    if (!seesInventories) throw ApiException(HttpStatusCode.Forbidden, "private", "Inventories are hidden from anonymous clients")
}

/** Takes up to [amount] from a slot, keeping the rest of the stack as it was. Server thread only. */
internal fun take(container: Container, slot: Int, amount: Int, api: ModApi): TakeResult {
    if (amount < 1) badRequest("amount must be at least 1")
    val stack = container.getItem(slot).takeUnless { it.isEmpty } ?: return TakeResult(0)
    val taken = minOf(amount, stack.count)
    if (taken == stack.count) {
        container.setItem(slot, ItemStack.EMPTY)
        return TakeResult(taken)
    }
    val rest = stack.copyWithCount(stack.count - taken)
    container.setItem(slot, rest)
    return TakeResult(taken, rest.view(slot, registries = api.server.registryAccess()))
}
