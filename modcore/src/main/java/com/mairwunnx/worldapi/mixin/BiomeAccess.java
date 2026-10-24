package com.mairwunnx.worldapi.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A biome's temperature at a block, private in the game: what Bukkit's `World.getTemperature` reads. */
@Mixin(Biome.class)
public interface BiomeAccess {
    @Invoker("getTemperature")
    float worldapi$temperature(BlockPos pos, int seaLevel);
}
