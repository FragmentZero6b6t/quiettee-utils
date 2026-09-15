package com.quiettee.utils.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.quiettee.utils.modules.combat.BoatShot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftClient.class)
public abstract class BoatShotInputMixin {

    @WrapOperation(method="handleInputEvents", at=@At(value="INVOKE", target="Lnet/minecraft/client/network/ClientPlayerInteractionManager;stopUsingItem(Lnet/minecraft/entity/player/PlayerEntity;)V"))
    private void quiettee$keepAutomaticBow(ClientPlayerInteractionManager manager, PlayerEntity player, Operation<Void> original) {
        BoatShot shot=BoatShot.active();
        if(shot==null || !shot.retainAutomaticDraw(player))original.call(manager,player);
    }
}
