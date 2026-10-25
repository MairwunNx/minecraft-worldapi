package com.mairwunnx.worldapi.mixin;

import com.mairwunnx.worldapi.modcore.Hooks;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A command a player typed, as Bukkit's `PlayerCommandPreprocessEvent`, signed or not. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CommandMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"))
    private void worldapi$unsigned(String command, CallbackInfo info) {
        Hooks.command(player, command);
    }

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"))
    private void worldapi$signed(ServerboundChatCommandSignedPacket packet, LastSeenMessages seen, CallbackInfo info) {
        Hooks.command(player, packet.command());
    }
}
