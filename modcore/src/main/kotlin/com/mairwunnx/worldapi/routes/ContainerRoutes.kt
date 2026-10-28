package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.game.chunkLoaded
import com.mairwunnx.worldapi.game.containerOf
import com.mairwunnx.worldapi.game.loadBlockChunk
import com.mairwunnx.worldapi.game.locked
import com.mairwunnx.worldapi.game.plain
import com.mairwunnx.worldapi.game.toItemStack
import com.mairwunnx.worldapi.game.typeKey
import com.mairwunnx.worldapi.game.view
import com.mairwunnx.worldapi.game.world
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.model.ContainerSummary
import com.mairwunnx.worldapi.model.GiveRequest
import com.mairwunnx.worldapi.model.GiveResult
import com.mairwunnx.worldapi.model.ItemInput
import com.mairwunnx.worldapi.model.ItemView
import com.mairwunnx.worldapi.model.TakeRequest
import com.mairwunnx.worldapi.model.TakeResult
import io.ktor.http.HttpStatusCode
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.CompoundContainer
import net.minecraft.world.Container
import net.minecraft.world.Nameable
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.ChestBlock
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
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val slot = slot(inventory)
            val item = input.toItemStack(api.server)
            val recording = Recording(world).apply { copyContainer(BlockPos(x, y, z)) }
            inventory.setItem(slot, item)
            inventory.setChanged()
            recording.finish(this, "container slot $slot")
            auditTarget = "${world.worldName} $x $y $z [$slot]"
            auditDetail = item.view(registries = api.server.registryAccess())?.components
            item.view(slot, registries = api.server.registryAccess())!!
        }
    }

    post<TakeRequest, TakeResult>(
        "/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}/take", "Take some items from a container slot", Scope.WORLD_WRITE,
        description = "$SLOTS The rest of the stack keeps its enchantments, name, and the like.",
    ) { request ->
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val slot = slot(inventory)
            auditTarget = "${world.worldName} $x $y $z [$slot]"
            val recording = Recording(world).apply { copyContainer(BlockPos(x, y, z)) }
            take(inventory, slot, request.amount, api).also {
                inventory.setChanged()
                recording.finish(this, "container take from slot $slot")
            }
        }
    }

    delete("/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}", "Empty a container slot", Scope.WORLD_WRITE, description = SLOTS) {
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val slot = slot(inventory)
            auditTarget = "${world.worldName} $x $y $z [$slot]"
            val recording = Recording(world).apply { copyContainer(BlockPos(x, y, z)) }
            inventory.setItem(slot, ItemStack.EMPTY)
            inventory.setChanged()
            recording.finish(this, "container empty slot $slot")
            Unit
        }
    }

    delete("/worlds/{world}/blocks/{x}/{y}/{z}/container", "Empty a whole container", Scope.WORLD_WRITE) {
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            auditTarget = "${world.worldName} $x $y $z"
            val inventory = containerAt(world, x, y, z)
            val recording = Recording(world).apply { copyContainer(BlockPos(x, y, z)) }
            inventory.clearContent()
            inventory.setChanged()
            recording.finish(this, "container empty")
            Unit
        }
    }

    post<GiveRequest, GiveResult>(
        "/worlds/{world}/blocks/{x}/{y}/{z}/container/give", "Add an item to a container", Scope.WORLD_WRITE,
        description = "Whatever doesn't fit is lost and counted in `dropped`.",
    ) { request ->
        val world = main { world() }
        val (x, y, z) = coordinates()
        loadBlockChunk(world, x, z)
        main {
            val inventory = containerAt(world, x, y, z)
            val item = request.item.toItemStack(api.server)
            auditTarget = "${world.worldName} $x $y $z"
            auditDetail = item.view(registries = api.server.registryAccess())?.components
            val amount = item.count
            val recording = Recording(world).apply { copyContainer(BlockPos(x, y, z)) }
            val left = addItem(inventory, item)
            inventory.setChanged()
            recording.finish(this, "container give")
            GiveResult(amount - left, left)
        }
    }
}

/** Puts a stack into a container as Bukkit's `Inventory.addItem` does: onto matching stacks first, then empty slots. Returns what didn't fit. */
internal fun addItem(container: Container, stack: ItemStack): Int {
    var left = stack.copy()
    for (pass in 0..1) for (slot in 0 until container.containerSize) {
        if (left.isEmpty) return 0
        val current = container.getItem(slot)
        val max = minOf(container.getMaxStackSize(left), left.maxStackSize)
        if (pass == 0 && !current.isEmpty && ItemStack.isSameItemSameComponents(current, left) && current.count < max) {
            val moved = minOf(max - current.count, left.count)
            current.grow(moved)
            left.shrink(moved)
        } else if (pass == 1 && current.isEmpty && container.canPlaceItem(slot, left)) {
            val moved = minOf(max, left.count)
            container.setItem(slot, left.copyWithCount(moved))
            left.shrink(moved)
        }
    }
    return left.count
}

/** Item keys and `#tags` as one set of items. */
private fun ModCall.items(text: String): Set<Item> {
    val types = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { name ->
        if (name.startsWith("#")) {
            val id = Identifier.tryParse(name.removePrefix("#").lowercase()) ?: badRequest("\"$name\" isn't a valid tag")
            BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id)).map { set -> set.map { it.value() } }.orElse(null)
                ?: badRequest("Unknown item tag \"$name\"", "unknown_tag")
        } else {
            val id = Identifier.tryParse(name.lowercase())
            listOf(id?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.takeIf { it != Items.AIR } ?: badRequest("Unknown item \"$name\"", "unknown_item"))
        }
    }.toSet()
    if (types.isEmpty()) badRequest("Name at least one item")
    return types
}

/** Containers in loaded chunks within [radius], nearest first. Server thread only. */
private fun containersNear(world: ServerLevel, x: Int, y: Int, z: Int, radius: Int, wanted: Set<Item>?): List<ContainerSummary> {
    val r2 = radius.toLong() * radius
    val chests = HashSet<BlockPos>()
    val found = mutableListOf<Pair<Long, ContainerSummary>>()
    for (cx in ((x - radius) shr 4)..((x + radius) shr 4)) for (cz in ((z - radius) shr 4)..((z + radius) shr 4)) {
        if (!chunkInRange(cx, cz, x, z, radius) || !world.chunkLoaded(cx, cz)) continue
        for (entity in world.getChunk(cx, cz).blockEntities.values.toList()) {
            val inventory = containerOf(world, entity) ?: continue
            val pos = entity.blockPos
            val dx = (pos.x - x).toLong()
            val dy = (pos.y - y).toLong()
            val dz = (pos.z - z).toLong()
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > r2) continue
            // Both halves of a double chest share one inventory: list it once, at the half seen first.
            if (inventory is CompoundContainer) {
                val partner = pos.relative(ChestBlock.getConnectedDirection(entity.blockState))
                if (partner in chests) continue
                chests += pos.immutable()
            }
            val stacks = (0 until inventory.containerSize).map(inventory::getItem).filterNot { it.isEmpty }
            val counts = stacks.groupingBy { it.item }.fold(0) { sum, stack -> sum + stack.count }
            val items = if (wanted != null) counts.filterKeys { it in wanted } else counts
            if (wanted != null && items.isEmpty()) continue
            val top = items.entries.sortedWith(compareByDescending<Map.Entry<Item, Int>> { it.value }.thenBy { BuiltInRegistries.ITEM.getKey(it.key).toString() })
                .let { if (wanted != null) it else it.take(5) }
                .associateTo(LinkedHashMap()) { BuiltInRegistries.ITEM.getKey(it.key).toString() to it.value }
            found += d2 to ContainerSummary(
                type = entity.blockState.typeKey,
                x = pos.x, y = pos.y, z = pos.z,
                distance = round(sqrt(d2.toDouble()) * 10) / 10,
                customName = (entity as? Nameable)?.customName?.plain(),
                size = inventory.containerSize,
                usedSlots = stacks.size,
                items = top,
                locked = locked(entity),
            )
        }
    }
    return found.sortedBy { it.first }.map { it.second }
}

/** The inventory of the container at a block, both halves for a double chest. Server thread only. */
private fun containerAt(world: ServerLevel, x: Int, y: Int, z: Int): Container =
    world.getBlockEntity(BlockPos(x, y, z))?.let { containerOf(world, it) } ?: notFound("not_a_container", "The block at $x $y $z holds no items")

private fun ModCall.slot(inventory: Container): Int {
    val slot = path("slot").toIntOrNull() ?: badRequest("slot must be a number")
    if (slot !in 0 until inventory.containerSize) badRequest("slot must be between 0 and ${inventory.containerSize - 1}")
    return slot
}
