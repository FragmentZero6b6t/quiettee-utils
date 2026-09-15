package com.quiettee.utils.events;

import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

public abstract class PlayerPositionLookEvent {
    public final PlayerPositionLookS2CPacket packet;

    private PlayerPositionLookEvent(PlayerPositionLookS2CPacket packet) {
        this.packet = packet;
    }

    public static final class Before extends PlayerPositionLookEvent {
        public Before(PlayerPositionLookS2CPacket packet) {
            super(packet);
        }
    }

    public static final class After extends PlayerPositionLookEvent {
        public After(PlayerPositionLookS2CPacket packet) {
            super(packet);
        }
    }
}
