package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.PaperCall
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.key
import com.mairwunnx.worldapi.bukkit.loadBlockChunk
import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.bukkit.toItemStack
import com.mairwunnx.worldapi.bukkit.view
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.ContainerSummary
import com.mairwunnx.worldapi.model.GiveRequest
import com.mairwunnx.worldapi.model.GiveResult
import com.mairwunnx.worldapi.model.ItemInput
import com.mairwunnx.worldapi.model.ItemView
import com.mairwunnx.worldapi.model.TakeRequest
import com.mairwunnx.worldapi.model.TakeResult
import io.ktor.http.HttpStatusCode
import org.bukkit.Material
import org.bukkit.Nameable
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.inventory.Inventory
import kotlin.math.round
import kotlin.math.sqrt

private const val SLOTS = "Slots count from 0; a double chest has 54, the left half first."

val containerRoutes = endpoints("Containers", Scope.SERVER_READ) {
    get(
        "/worlds/{world}/containers", "Chests, barrels, shulker boxes, and other containers near a point",
        description = "Looks through loaded chunks only, nearest first. With `item`, only containers holding one of those items, and how many. " +
            "Without it, each container lists its five most plentiful items. A double chest counts once. Needs players:private, " +
            "or privacy that lets anonymous clients see inventories. Read one in full with GET /worlds/{world}/blocks/{x}/{y}/{z}.",
        query = pageParams + listOf(
            integer("x", "Center X", required = true),
            integer("y", "Center Y", required = true),
            integer("z", "Center Z", required = true),
            integer("radius", "Search radius in blocks (16 by default, at most limits.maxBlockSearchRadius)"),
            string("item", "Item keys or `#tags`, separated by commas: `minecraft:diamond,#minecraft:logs`"),
        ),
        also = Scope.PLAYERS_PRIVATE,
    ) {
        if (!seesInventories) throw ApiException(HttpStatusCode.Forbidden, "private", "Inventories are hidden from anonymous clients")
        val x = requireInt("x")
        val y = requireInt("y")
        val z = requireInt("z")
        if (x !in -30_000_000..30_000_000 || z !in -30_000_000..30_000_000) badRequest("x and z must be within the world")
        val radius = int("radius") ?: minOf(16, config.limits.maxBlockSearchRadius)
        if (radius !in 1..config.limits.maxBlockSearchRadius) badRequest("radius must be between 1 and ${config.limits.maxBlockSearchRadius}")
        val (limit, cursor) = limit to cursor
        val itemQuery = query("item")
        main {
            val wanted = itemQuery?.let(::items)
            containersNear(world(), x, y, z, radius, wanted).page(limit, cursor)
        }
    }

    patch<ItemInput, ItemView>(
        "/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}", "Put an item in a container slot", Scope.WORLD_WRITE, description = SLOTS,
    ) { input ->
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val slot = slot(inventory)
            val item = input.toItemStack()
            val recording = Recording(world).apply { copyContainer(world.getBlockAt(x, y, z)) }
            inventory.setItem(slot, item)
            recording.finish(this, "container slot $slot")
            auditTarget = "${world.name} $x $y $z [$slot]"
            auditDetail = item.view()?.components
            item.view(slot)!!
        }
    }

    post<TakeRequest, TakeResult>(
        "/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}/take", "Take some items from a container slot", Scope.WORLD_WRITE,
        description = "$SLOTS The rest of the stack keeps its enchantments, name, and the like.",
    ) { request ->
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val slot = slot(inventory)
            auditTarget = "${world.name} $x $y $z [$slot]"
            val recording = Recording(world).apply { copyContainer(world.getBlockAt(x, y, z)) }
            take(inventory, slot, request.amount).also { recording.finish(this, "container take from slot $slot") }
        }
    }

    delete("/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}", "Empty a container slot", Scope.WORLD_WRITE, description = SLOTS) {
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val slot = slot(inventory)
            auditTarget = "${world.name} $x $y $z [$slot]"
            val recording = Recording(world).apply { copyContainer(world.getBlockAt(x, y, z)) }
            inventory.setItem(slot, null)
            recording.finish(this, "container empty slot $slot")
            Unit
        }
    }

    delete("/worlds/{world}/blocks/{x}/{y}/{z}/container", "Empty a whole container", Scope.WORLD_WRITE) {
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            auditTarget = "${world.name} $x $y $z"
            val inventory = containerAt(world, x, y, z)
            val recording = Recording(world).apply { copyContainer(world.getBlockAt(x, y, z)) }
            inventory.clear()
            recording.finish(this, "container empty")
            Unit
        }
    }

    post<GiveRequest, GiveResult>(
        "/worlds/{world}/blocks/{x}/{y}/{z}/container/give", "Add an item to a container", Scope.WORLD_WRITE,
        description = "Whatever doesn't fit is lost and counted in `dropped`.",
    ) { request ->
        val world = world()
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val item = request.item.toItemStack()
            auditTarget = "${world.name} $x $y $z"
            auditDetail = item.view()?.components
            val amount = item.amount
            val recording = Recording(world).apply { copyContainer(world.getBlockAt(x, y, z)) }
            val dropped = inventory.addItem(item).values.sumOf { it.amount }
            recording.finish(this, "container give")
            GiveResult(amount - dropped, dropped)
        }
    }
}

/** Item keys and `#tags` as one set of materials. */
private fun PaperCall.items(text: String): Set<Material> {
    val types = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { name ->
        if (name.startsWith("#")) {
            api.server.getTag(Tag.REGISTRY_ITEMS, key(name.removePrefix("#"), "tag"), Material::class.java)?.values
                ?: badRequest("Unknown item tag \"$name\"", "unknown_tag")
        } else {
            listOf(Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir } ?: badRequest("Unknown item \"$name\"", "unknown_item"))
        }
    }.toSet()
    if (types.isEmpty()) badRequest("Name at least one item")
    return types
}

/** Containers in loaded chunks within [radius], nearest first. Server thread only. */
private fun containersNear(world: World, x: Int, y: Int, z: Int, radius: Int, wanted: Set<Material>?): List<ContainerSummary> {
    val r2 = radius.toLong() * radius
    val seen = HashSet<Inventory>()
    val found = mutableListOf<Pair<Long, ContainerSummary>>()
    for (cx in ((x - radius) shr 4)..((x + radius) shr 4)) for (cz in ((z - radius) shr 4)..((z + radius) shr 4)) {
        if (!chunkInRange(cx, cz, x, z, radius) || !world.isChunkLoaded(cx, cz)) continue
        for (state in world.getChunkAt(cx, cz).getTileEntities(false)) {
            val container = state as? Container ?: continue
            val dx = (state.x - x).toLong()
            val dy = (state.y - y).toLong()
            val dz = (state.z - z).toLong()
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > r2) continue
            // Both halves of a double chest share one inventory: list it once, at the half seen first.
            val inventory = container.inventory
            if (inventory.holder is DoubleChest && !seen.add(inventory)) continue
            val stacks = inventory.contents.filterNotNull().filter { !it.type.isAir }
            val counts = stacks.groupingBy { it.type }.fold(0) { sum, stack -> sum + stack.amount }
            val items = if (wanted != null) counts.filterKeys { it in wanted } else counts
            if (wanted != null && items.isEmpty()) continue
            val top = items.entries.sortedWith(compareByDescending<Map.Entry<Material, Int>> { it.value }.thenBy { it.key.key.asString() })
                .let { if (wanted != null) it else it.take(5) }
                .associateTo(LinkedHashMap()) { it.key.key.asString() to it.value }
            found += d2 to ContainerSummary(
                type = state.type.key.asString(),
                x = state.x, y = state.y, z = state.z,
                distance = round(sqrt(d2.toDouble()) * 10) / 10,
                customName = (state as? Nameable)?.customName()?.plain(),
                size = inventory.size,
                usedSlots = stacks.size,
                items = top,
                locked = container.isLocked,
            )
        }
    }
    return found.sortedBy { it.first }.map { it.second }
}

/** The inventory of the container at a block, both halves for a double chest. Server thread only. */
private fun containerAt(world: World, x: Int, y: Int, z: Int): Inventory =
    (world.getBlockAt(x, y, z).getState(false) as? Container)?.inventory ?: notFound("not_a_container", "The block at $x $y $z holds no items")

private fun PaperCall.slot(inventory: Inventory): Int {
    val slot = path("slot").toIntOrNull() ?: badRequest("slot must be a number")
    if (slot !in 0 until inventory.size) badRequest("slot must be between 0 and ${inventory.size - 1}")
    return slot
}
