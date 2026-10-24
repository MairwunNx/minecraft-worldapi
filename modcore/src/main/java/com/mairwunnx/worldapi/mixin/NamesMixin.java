package com.mairwunnx.worldapi.mixin;

import com.mairwunnx.worldapi.game.Names;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The chat name a client set through WorldAPI, in place of the player's own, as Paper's `displayName` works. */
@Mixin(Player.class)
public abstract class NamesMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void worldapi$displayName(CallbackInfoReturnable<Component> info) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        Component name = Names.display(player.getUUID());
        if (name != null) info.setReturnValue(name);
    }
}
