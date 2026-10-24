package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.notFound
import kotlinx.coroutines.future.await
import org.bukkit.World

/**
 * Loads the chunk holding a block without stalling the tick: Paper reads it from disk off-thread
 * and we wait for it here. Chunks that were never generated stay that way.
 */
suspend fun ApiCall.loadChunk(world: World, chunkX: Int, chunkZ: Int) {
    val future = main { if (world.isChunkLoaded(chunkX, chunkZ)) null else world.getChunkAtAsync(chunkX, chunkZ, false) }
    if (future != null && future.await() == null) {
        notFound("chunk_not_generated", "Chunk $chunkX, $chunkZ in ${world.name} was never generated")
    }
}

suspend fun ApiCall.loadBlockChunk(world: World, x: Int, z: Int) = loadChunk(world, x shr 4, z shr 4)
