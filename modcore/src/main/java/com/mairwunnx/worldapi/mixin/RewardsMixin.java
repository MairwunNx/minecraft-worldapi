package com.mairwunnx.worldapi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mairwunnx.worldapi.modcore.Hooks;
import net.minecraft.advancements.triggers.EnchantedItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** What players earn and make: orbs picked up, fish caught, items crafted, items enchanted. */
public final class RewardsMixin {
    private RewardsMixin() {}

    /** Experience from an orb, as Bukkit's `PlayerExpChangeEvent` counts it: `/xp` takes another way. */
    @Mixin(ExperienceOrb.class)
    public abstract static class Orb {
        @WrapOperation(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;giveExperiencePoints(I)V"))
        private void worldapi$orb(Player player, int amount, Operation<Void> original) {
            Hooks.experience(player, amount);
            original.call(player, amount);
        }
    }

    /** A catch, as Bukkit's `PlayerFishEvent` with `CAUGHT_FISH`: the item the hook pulls out. */
    @Mixin(FishingHook.class)
    public abstract static class Fishing {
        @WrapOperation(method = "retrieve", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
        private boolean worldapi$caught(Level level, Entity entity, Operation<Boolean> original) {
            boolean added = original.call(level, entity);
            if (added && entity instanceof ItemEntity item && ((FishingHook) (Object) this).getPlayerOwner() instanceof ServerPlayer player) {
                Hooks.fished(player, item.getItem());
            }
            return added;
        }
    }

    /** A craft taken from the result slot, as Bukkit's `CraftItemEvent`. */
    @Mixin(ResultSlot.class)
    public abstract static class Craft {
        @Inject(method = "onTake", at = @At("HEAD"))
        private void worldapi$crafted(Player player, ItemStack stack, CallbackInfo info) {
            Hooks.crafted(player, stack);
        }
    }

    /** An enchanting table's work, with the enchanted item as it comes out. */
    @Mixin(EnchantedItemTrigger.class)
    public abstract static class Enchant {
        @Inject(method = "trigger", at = @At("HEAD"))
        private void worldapi$enchanted(ServerPlayer player, ItemStack stack, int levels, CallbackInfo info) {
            Hooks.enchanted(player, stack, levels);
        }
    }
}
