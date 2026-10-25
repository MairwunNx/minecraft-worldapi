package com.mairwunnx.worldapi.mixin;

import com.mairwunnx.worldapi.modcore.Hooks;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An advancement done, as Bukkit's `PlayerAdvancementDoneEvent`: the criterion that finished it, once. */
@Mixin(PlayerAdvancements.class)
public abstract class AdvancementMixin {
    @Shadow private ServerPlayer player;

    @Shadow public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    private boolean worldapi$wasDone;

    @Inject(method = "award", at = @At("HEAD"))
    private void worldapi$before(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> info) {
        worldapi$wasDone = getOrStartProgress(advancement).isDone();
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void worldapi$after(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> info) {
        if (info.getReturnValue() && !worldapi$wasDone && getOrStartProgress(advancement).isDone()) Hooks.advancement(player, advancement);
    }
}
