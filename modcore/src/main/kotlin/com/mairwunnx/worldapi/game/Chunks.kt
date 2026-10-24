package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.notFound
import kotlinx.coroutines.future.await
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkStatus

/** Whether a chunk is loaded in full, without loading it. Server thread. */
fun ServerLevel.chunkLoaded(chunkX: Int, chunkZ: Int) = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null

/**
 * Loads the chunk holding a block, as Paper's `getChunkAtAsync(x, z, false)`: a chunk on disk loads, and one that was
 * never generated stays that way and answers 404. The game reads the file off the server thread; the load itself
 * then takes a moment on it.
 */
suspend fun ModCall.loadChunk(world: ServerLevel, chunkX: Int, chunkZ: Int) {
    if (main { world.chunkLoaded(chunkX, chunkZ) }) return
    val saved = runCatching { world.chunkSource.chunkMap.read(ChunkPos(chunkX, chunkZ)).await() }.getOrNull()?.isPresent == true
    if (!saved) notFound("chunk_not_generated", "Chunk $chunkX, $chunkZ in ${world.worldName} was never generated")
    main { world.getChunk(chunkX, chunkZ) }
}

suspend fun ModCall.loadBlockChunk(world: ServerLevel, x: Int, z: Int) = loadChunk(world, x shr 4, z shr 4)
