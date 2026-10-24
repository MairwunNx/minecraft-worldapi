package com.mairwunnx.worldapi.mixin;

import com.mairwunnx.worldapi.game.Names;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player list's name a client set through WorldAPI, as Paper's `playerListName` works. */
@Mixin(ServerPlayer.class)
public abstract class ListNameMixin {
    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void worldapi$listName(CallbackInfoReturnable<Component> info) {
        Component name = Names.list(((ServerPlayer) (Object) this).getUUID());
        if (name != null) info.setReturnValue(name);
    }
}
