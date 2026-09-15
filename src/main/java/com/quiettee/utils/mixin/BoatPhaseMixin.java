package com.quiettee.utils.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.quiettee.utils.modules.movement.BoatPhase;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractBoatEntity.class)
public abstract class BoatPhaseMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/vehicle/AbstractBoatEntity;move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V"))
    private void quiettee$boatMove(AbstractBoatEntity boat, MovementType type, Vec3d movement, Operation<Void> original) {
        BoatPhase module = BoatPhase.active();
        if (module == null) {
            original.call(boat, type, movement);
            return;
        }
        Vec3d step = module.movement(boat, movement);
        boolean oldNoClip = boat.noClip;
        try {
            if (module.phases(boat)) boat.noClip = true;
            original.call(boat, type, step);
        } finally {
            boat.noClip = oldNoClip;
        }
        module.afterMove(boat);
    }
}
