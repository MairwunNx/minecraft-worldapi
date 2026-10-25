package com.mairwunnx.worldapi.mixin;

import com.mairwunnx.worldapi.modcore.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.UUID;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The world's moments without an event on one loader or the other. */
public final class WorldMixin {
    private WorldMixin() {}

    /** A block a player placed, as Bukkit's `BlockPlaceEvent`, once it stands. */
    @Mixin(BlockItem.class)
    public abstract static class Place {
        @Inject(method = "place", at = @At("RETURN"))
        private void worldapi$placed(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> info) {
            if (info.getReturnValue().consumesAction() && context.getLevel() instanceof ServerLevel level) {
                Hooks.placed(context.getPlayer(), level.getBlockState(context.getClickedPos()));
            }
        }
    }

    @Mixin(ServerExplosion.class)
    public abstract static class Explosion {
        @Shadow @Final private ServerLevel level;
        @Shadow @Final private Vec3 center;
        @Shadow @Final private Entity source;

        @Inject(method = "explode", at = @At("RETURN"))
        private void worldapi$exploded(CallbackInfoReturnable<Integer> info) {
            Hooks.exploded(level, source, center, info.getReturnValue());
        }
    }

    @Mixin(AbstractVillager.class)
    public abstract static class Trade {
        @Inject(method = "notifyTrade", at = @At("HEAD"))
        private void worldapi$traded(MerchantOffer offer, CallbackInfo info) {
            AbstractVillager villager = (AbstractVillager) (Object) this;
            Hooks.traded(villager.getTradingPlayer(), villager, offer);
        }
    }

    @Mixin(TamableAnimal.class)
    public abstract static class Tame {
        @Inject(method = "tame", at = @At("HEAD"))
        private void worldapi$tamed(Player owner, CallbackInfo info) {
            Hooks.tamed(owner);
        }
    }

    @Mixin(AbstractHorse.class)
    public abstract static class Horse {
        @Inject(method = "tameWithName", at = @At("RETURN"))
        private void worldapi$tamed(Player owner, CallbackInfoReturnable<Boolean> info) {
            if (info.getReturnValue()) Hooks.tamed(owner);
        }
    }

    /** A kick: the server hangs up on a player, which a quit by the player never does. */
    @Mixin(ServerCommonPacketListenerImpl.class)
    public abstract static class Kick {
        @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
        private void worldapi$kicked(DisconnectionDetails details, CallbackInfo info) {
            if ((Object) this instanceof ServerGamePacketListenerImpl game) Hooks.kicked(game.player, details.reason());
        }
    }

    @Mixin(MinecraftServer.class)
    public abstract static class Save {
        @Inject(method = "saveAllChunks", at = @At("RETURN"))
        private void worldapi$saved(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> info) {
            Hooks.saved();
        }
    }

    @Mixin(Raids.class)
    public abstract static class RaidStart {
        @Inject(method = "createOrExtendRaid", at = @At("RETURN"))
        private void worldapi$raid(ServerPlayer player, BlockPos pos, CallbackInfoReturnable<Raid> info) {
            Raid raid = info.getReturnValue();
            if (raid != null && !((RaidState) raid).worldapi$started()) Hooks.raidStarted(player.level(), player, raid, ((RaidState) raid).worldapi$waves());
        }
    }

    @Mixin(Raid.class)
    public interface RaidState {
        @org.spongepowered.asm.mixin.gen.Accessor("started")
        boolean worldapi$started();

        @org.spongepowered.asm.mixin.gen.Accessor("numGroups")
        int worldapi$waves();

        @org.spongepowered.asm.mixin.gen.Accessor("heroesOfTheVillage")
        Set<UUID> worldapi$heroes();
    }

    /** A raid's end: its tick turns it to victory or stops it. */
    @Mixin(Raid.class)
    public abstract static class RaidEnd {
        private boolean worldapi$over;

        @Inject(method = "tick", at = @At("HEAD"))
        private void worldapi$before(ServerLevel level, CallbackInfo info) {
            worldapi$over = ((Raid) (Object) this).isOver();
        }

        @Inject(method = "tick", at = @At("RETURN"))
        private void worldapi$after(ServerLevel level, CallbackInfo info) {
            Raid raid = (Raid) (Object) this;
            if (!worldapi$over && raid.isOver()) Hooks.raidEnded(level, raid, ((RaidState) raid).worldapi$heroes());
        }
    }
}
