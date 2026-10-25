package com.mairwunnx.worldapi.mixin;

import com.mairwunnx.worldapi.modcore.Hooks;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.attribute.BedRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player's moments that neither loader announces the same way: a death once it happened (a mod that saves the
 * player returns before the end), a game mode change, and getting into and out of bed.
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerMixin {
    private int worldapi$drops;
    private GameType worldapi$mode;

    @Inject(method = "die", at = @At("HEAD"))
    private void worldapi$dying(DamageSource source, CallbackInfo info) {
        var inventory = ((ServerPlayer) (Object) this).getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) count += inventory.getItem(slot).getCount();
        worldapi$drops = count;
    }

    @Inject(method = "die", at = @At("TAIL"))
    private void worldapi$died(DamageSource source, CallbackInfo info) {
        Hooks.died((ServerPlayer) (Object) this, source, worldapi$drops);
    }

    @Inject(method = "setGameMode", at = @At("HEAD"))
    private void worldapi$modeBefore(GameType mode, CallbackInfoReturnable<Boolean> info) {
        worldapi$mode = ((ServerPlayer) (Object) this).gameMode();
    }

    @Inject(method = "setGameMode", at = @At("RETURN"))
    private void worldapi$modeAfter(GameType mode, CallbackInfoReturnable<Boolean> info) {
        if (info.getReturnValue()) Hooks.gameMode((ServerPlayer) (Object) this, worldapi$mode, mode);
    }

    @Inject(method = "startSleepInBed", at = @At("RETURN"))
    private void worldapi$slept(AbstractBedBlock bed, BlockState state, BedRule rule, BlockPos pos, CallbackInfoReturnable<Either<Player.BedSleepingProblem, net.minecraft.util.Unit>> info) {
        if (info.getReturnValue().right().isPresent()) Hooks.sleep((ServerPlayer) (Object) this, true);
    }

    @Inject(method = "stopSleepInBed", at = @At("HEAD"))
    private void worldapi$woke(boolean forcefulWakeUp, boolean updateLevelList, CallbackInfo info) {
        if (((ServerPlayer) (Object) this).isSleeping()) Hooks.sleep((ServerPlayer) (Object) this, false);
    }
}
