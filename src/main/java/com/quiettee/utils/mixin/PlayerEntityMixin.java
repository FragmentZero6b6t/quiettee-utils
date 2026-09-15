package com.quiettee.utils.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.quiettee.utils.modules.player.AirMiner;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @ModifyReturnValue(method = "getBlockBreakingSpeed", at = @At("RETURN"))
    private float quiettee$onGetBlockBreakingSpeed(float breakSpeed, BlockState block) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (!self.getEntityWorld().isClient()) return breakSpeed;

        AirMiner airMiner = Modules.get().get(AirMiner.class);
        if (airMiner != null && airMiner.liftsPenalty() && self == MinecraftClient.getInstance().player) breakSpeed *= 5.0f;

        return breakSpeed;
    }
}
