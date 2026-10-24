package com.mairwunnx.worldapi.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Food exhaustion, which the game keeps private and Bukkit reads and sets. */
@Mixin(FoodData.class)
public interface FoodDataAccess {
    @Accessor("exhaustionLevel")
    float worldapi$exhaustion();

    @Accessor("exhaustionLevel")
    void worldapi$setExhaustion(float exhaustion);
}
