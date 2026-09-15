package com.quiettee.utils.modules.combat;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;

public final class BoatShotPackets {
    public static boolean preserveQueuedRelease(Packet<?> packet,boolean automatic,boolean sameBow,int slot) {
        if(!sameBow)return false;
        return automatic && packet instanceof PlayerInteractItemC2SPacket use && use.getHand()==Hand.MAIN_HAND
            || packet instanceof UpdateSelectedSlotC2SPacket sync && sync.getSelectedSlot()==slot;
    }
}
