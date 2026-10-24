package com.mairwunnx.worldapi.mixin;

import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A container's lock, private in the game: what Bukkit's `Lockable.isLocked` reads. */
@Mixin(BaseContainerBlockEntity.class)
public interface LockAccess {
    @Accessor("lockKey")
    LockCode worldapi$lock();
}
