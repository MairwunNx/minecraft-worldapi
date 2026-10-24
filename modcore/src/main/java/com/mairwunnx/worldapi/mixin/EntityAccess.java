package com.mairwunnx.worldapi.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What Bukkit reads and sets on entities and the game keeps private. */
public final class EntityAccess {
    private EntityAccess() {}

    /** Whether a mob stays when no player is near: Bukkit's `setPersistent` sets it both ways. */
    @Mixin(Mob.class)
    public interface Persistence {
        @Accessor("persistenceRequired")
        void worldapi$setPersistent(boolean persistent);
    }

    /** A dropped item's delay before pickup, and who threw it. */
    @Mixin(ItemEntity.class)
    public interface Dropped {
        @Accessor("pickupDelay")
        int worldapi$pickupDelay();

        @Accessor("thrower")
        EntityReference<Entity> worldapi$thrower();
    }
}
