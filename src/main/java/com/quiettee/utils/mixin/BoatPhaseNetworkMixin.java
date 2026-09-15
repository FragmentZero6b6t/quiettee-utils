package com.quiettee.utils.mixin;

import com.quiettee.utils.modules.movement.BoatPhase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class BoatPhaseNetworkMixin {
    @Inject(method = "onVehicleMove", at = @At("HEAD"))
    private void quiettee$boatCorrectionBefore(VehicleMoveS2CPacket packet, CallbackInfo info) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        BoatPhase module = BoatPhase.active();
        if (module != null) module.beforeVehicleCorrection((ClientPlayNetworkHandler) (Object) this, packet);
    }

    @Inject(method = "onVehicleMove", at = @At("RETURN"))
    private void quiettee$boatCorrectionAfter(VehicleMoveS2CPacket packet, CallbackInfo info) {
        if (!MinecraftClient.getInstance().isOnThread()) return;
        BoatPhase module = BoatPhase.active();
        if (module != null) module.afterVehicleCorrection((ClientPlayNetworkHandler) (Object) this, packet);
    }
}
