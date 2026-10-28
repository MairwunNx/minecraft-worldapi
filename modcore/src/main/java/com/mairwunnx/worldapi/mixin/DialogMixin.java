package com.mairwunnx.worldapi.mixin;

import com.mairwunnx.worldapi.modcore.Hooks;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dialog's answer, such as the webhook wizard's secret, taken on the server thread before the game's own handler,
 * which would write the payload to the debug log.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class DialogMixin {
    @Inject(
        method = "handleCustomClickAction",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"),
        cancellable = true
    )
    private void worldapi$answer(ServerboundCustomClickActionPacket packet, CallbackInfo info) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && Hooks.dialogAnswer(game.player, packet.id(), packet.payload())) info.cancel();
    }
}
