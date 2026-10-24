package com.mairwunnx.worldapi.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every chunk the map holds, which the game keeps to itself: what Paper's chunk and block entity counts read. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> worldapi$chunks();
}
