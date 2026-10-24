package com.mairwunnx.worldapi.mixin;

import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a spawner makes next, private in the game: what Bukkit's `CreatureSpawner.getSpawnedType` reads. */
@Mixin(BaseSpawner.class)
public interface SpawnerAccess {
    @Accessor("nextSpawnData")
    SpawnData worldapi$next();
}
