package com.quiettee.utils.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerMoveC2SPacket.class)
public interface PlayerMoveC2SPacketAccessor {
    @Mutable
    @Accessor("x")
    void quiettee$setX(double x);

    @Mutable
    @Accessor("y")
    void quiettee$setY(double y);

    @Mutable
    @Accessor("z")
    void quiettee$setZ(double z);

    @Mutable
    @Accessor("yaw")
    void quiettee$setYaw(float yaw);

    @Mutable
    @Accessor("pitch")
    void quiettee$setPitch(float pitch);

    @Mutable
    @Accessor("onGround")
    void quiettee$setOnGround(boolean onGround);

    @Mutable
    @Accessor("horizontalCollision")
    void quiettee$setHorizontalCollision(boolean horizontalCollision);
}
