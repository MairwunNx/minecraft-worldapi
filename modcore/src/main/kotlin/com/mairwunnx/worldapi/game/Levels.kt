package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.mixin.ChunkMapAccess
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.LevelChunk

/** The chunks loaded in full, as Paper's `World.getLoadedChunks` lists them. Server thread. */
fun ServerLevel.loadedChunks(): List<LevelChunk> =
    (chunkSource.chunkMap as ChunkMapAccess).`worldapi$chunks`().values.mapNotNull { it.fullChunkFuture.getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null) }

/** Every entity loaded in the world, players included. */
fun ServerLevel.entityList() = allEntities.toList()

val ServerLevel.blockEntityCount: Int get() = loadedChunks().sumOf { it.blockEntities.size }
