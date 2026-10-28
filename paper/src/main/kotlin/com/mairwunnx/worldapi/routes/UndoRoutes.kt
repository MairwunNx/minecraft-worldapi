package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.PaperCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.bukkit.loadChunk
import com.mairwunnx.worldapi.changes.Changes
import com.mairwunnx.worldapi.model.UndoRequest
import com.mairwunnx.worldapi.model.UndoResult
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.block.TileState

val undoRoutes = endpoints("Changes", Scope.WORLD_WRITE) {
    post<UndoRequest, UndoResult>("/changes/{id}/undo", "Take back a change the API made", Scope.WORLD_WRITE, description = UNDO_DESCRIPTION) { request ->
        val change = changeToUndo(api.changes)
        val world = api.server.getWorld(change.world) ?: notFound("world_not_found", "No world \"${change.world}\"")
        change.chunks.forEach { (cx, cz) -> loadChunk(world, cx, cz) }
        main {
            if (change.undone) return@main UndoResult(0, 0)
            var restored = 0
            for (block in change.blocks.asReversed()) {
                if (!request.force && world.getBlockAt(block.x, block.y, block.z).blockData.asString != block.after) continue
                block.before.update(true, false)
                restored++
            }
            change.undone = true
            UndoResult(restored, change.blocks.size - restored)
        }
    }
}

/**
 * Copies blocks before the API changes them, so the change can be undone: [copy] each block just before it changes,
 * then [finish] once they all did. Server thread.
 */
class Recording(private val world: World) {
    private val copies = LinkedHashMap<Long, BlockState>()

    fun copy(block: Block) {
        copies.putIfAbsent(Block.getBlockKey(block.x, block.y, block.z), block.state)
    }

    /** Copies a container and, for a double chest, its other half, since an edit may reach either. */
    fun copyContainer(block: Block) {
        copy(block)
        val holder = (block.getState(false) as? Container)?.inventory?.holder as? DoubleChest ?: return
        listOfNotNull(holder.leftSide, holder.rightSide).forEach { (it as? BlockState)?.block?.let(::copy) }
    }

    /** Records what changed: blocks whose state differs now, and every block entity, whose data an edit may have changed. */
    fun finish(call: PaperCall, what: String): Long? {
        val blocks = copies.values.mapNotNull { before ->
            val after = world.getBlockAt(before.x, before.y, before.z).blockData.asString
            if (after == before.blockData.asString && before !is TileState) null else Changes.Block(before.x, before.y, before.z, before, after)
        }
        return call.api.changes.record(call.principal.name, world.name, what, blocks)
    }
}
