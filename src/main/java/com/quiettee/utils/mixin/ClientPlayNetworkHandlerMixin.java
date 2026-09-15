package com.quiettee.utils.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.quiettee.utils.events.PlayerPositionLookEvent;
import com.quiettee.utils.modules.combat.MaceSmash;
import com.quiettee.utils.modules.movement.FloatModule;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onPlayerPositionLook", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V", shift = At.Shift.AFTER))
    private void quiettee$onPlayerPositionLookHead(PlayerPositionLookS2CPacket packet, CallbackInfo ci,
          @Share("quietteeYaw") LocalFloatRef yawRef,
          @Share("quietteePitch") LocalFloatRef pitchRef,
          @Share("quietteeKeepLook") LocalBooleanRef keepLookRef,
          @Share("quietteeTrack") LocalBooleanRef trackRef,
          @Share("quietteePlayer") LocalRef<Entity> playerRef,
          @Share("quietteeWorld") LocalRef<ClientWorld> worldRef) {
        MinecraftClient client = MinecraftClient.getInstance();
        trackRef.set((Object) this == client.getNetworkHandler());
        if (!trackRef.get()) return;
        playerRef.set(client.player);
        worldRef.set(client.world);
        MeteorClient.EVENT_BUS.post(new PlayerPositionLookEvent.Before(packet));
        keepLookRef.set(client.player != null && (MaceSmash.keepLookArmed() || FloatModule.keepLookArmed()));
        if (!keepLookRef.get()) return;

        yawRef.set(client.player.getYaw());
        pitchRef.set(client.player.getPitch());
    }

    @Inject(method = "onPlayerPositionLook", at = @At("RETURN"))
    private void quiettee$onPlayerPositionLookReturn(PlayerPositionLookS2CPacket packet, CallbackInfo ci,
        @Share("quietteeYaw") LocalFloatRef yawRef,
        @Share("quietteePitch") LocalFloatRef pitchRef,
        @Share("quietteeKeepLook") LocalBooleanRef keepLookRef,
        @Share("quietteeTrack") LocalBooleanRef trackRef,
        @Share("quietteePlayer") LocalRef<Entity> playerRef,
        @Share("quietteeWorld") LocalRef<ClientWorld> worldRef) {
        if (!trackRef.get()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        MaceSmash.consumeKeepLook();
        FloatModule.consumeKeepLook();
        if (keepLookRef.get() && client.player != null && client.player == playerRef.get()
            && client.world == worldRef.get() && (Object) this == client.getNetworkHandler()) {
            float savedYaw = yawRef.get();
            float savedPitch = pitchRef.get();

            client.player.setYaw(savedYaw + 0.000001f);
            client.player.setPitch(savedPitch + 0.000001f);
            client.player.headYaw = savedYaw;
            client.player.bodyYaw = savedYaw;
        }
        MeteorClient.EVENT_BUS.post(new PlayerPositionLookEvent.After(packet));
    }
}
