package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.changes.Changes
import com.mairwunnx.worldapi.game.BlockCopy
import com.mairwunnx.worldapi.game.data
import com.mairwunnx.worldapi.game.findWorld
import com.mairwunnx.worldapi.game.loadChunk
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.model.UndoRequest
import com.mairwunnx.worldapi.model.UndoResult
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.storage.TagValueInput

val undoRoutes = endpoints("Changes", Scope.WORLD_WRITE) {
    post<UndoRequest, UndoResult>("/changes/{id}/undo", "Take back a change the API made", Scope.WORLD_WRITE, description = UNDO_DESCRIPTION) { request ->
        val change = changeToUndo(api.changes)
        val world = main { api.server.findWorld(change.world) } ?: notFound("world_not_found", "No world \"${change.world}\"")
        change.chunks.forEach { (cx, cz) -> loadChunk(world, cx, cz) }
        main {
            if (change.undone) return@main UndoResult(0, 0)
            var restored = 0
            val pos = BlockPos.MutableBlockPos()
            for (block in change.blocks.asReversed()) {
                pos.set(block.x, block.y, block.z)
                if (!request.force && world.getBlockState(pos).data != block.after) continue
                block.before.restore(world, pos.immutable())
                restored++
            }
            change.undone = true
            UndoResult(restored, change.blocks.size - restored)
        }
    }
}

/** Puts a copied block back: its state without physics, then its block entity's data. Server thread. */
private fun BlockCopy.restore(world: ServerLevel, pos: BlockPos) {
    val old = world.getBlockState(pos)
    world.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS or net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE)
    val tag = entity ?: return
    val entity = world.getBlockEntity(pos) ?: return
    entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), tag))
    entity.setChanged()
    world.sendBlockUpdated(pos, old, state, net.minecraft.world.level.block.Block.UPDATE_ALL)
}

/**
 * Copies blocks before the API changes them, so the change can be undone: [copy] each block just before it changes,
 * then [finish] once they all did. Server thread.
 */
class Recording(private val world: ServerLevel) {
    private val copies = LinkedHashMap<BlockPos, BlockCopy>()

    fun copy(pos: BlockPos) {
        val at = pos.immutable()
        copies.getOrPut(at) { BlockCopy(world.getBlockState(at), world.getBlockEntity(at)?.saveWithFullMetadata(world.registryAccess())) }
    }

    /** Copies a container and, for a double chest, its other half, since an edit may reach either. */
    fun copyContainer(pos: BlockPos) {
        copy(pos)
        val state = world.getBlockState(pos)
        if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) copy(ChestBlock.getConnectedBlockPos(pos, state))
    }

    /** Records what changed: blocks whose state differs now, and every block entity, whose data an edit may have changed. */
    fun finish(call: ModCall, what: String): Long? {
        val blocks = copies.mapNotNull { (pos, before) ->
            val after = world.getBlockState(pos).data
            if (after == before.state.data && before.entity == null) null else Changes.Block(pos.x, pos.y, pos.z, before, after)
        }
        return call.api.changes.record(call.principal.name, world.worldName, what, blocks)
    }
}
