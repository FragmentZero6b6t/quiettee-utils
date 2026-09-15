package com.quiettee.utils.modules.player;

import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.mixin.PlayerMoveC2SPacketAccessor;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class AirMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> whileGliding = sgGeneral.add(new BoolSetting.Builder()
        .name("while-gliding")
        .description("Also claim while gliding. Dangerous.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-speed")
        .description("No claims above this speed.")
        .defaultValue(1.0)
        .min(0.0)
        .sliderRange(0.0, 3.0)
        .build()
    );

    private final Setting<Integer> claimWindow = sgGeneral.add(new IntSetting.Builder()
        .name("claim-window")
        .description("Ticks the ground claim stays on.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> chatDebug = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-debug")
        .description("Announce each claim in chat.")
        .defaultValue(false)
        .build()
    );

    private int claimLeft;
    private boolean lying;
    private boolean injecting;
    private int claims;

    public AirMiner() {
        super(QuietteeUtils.CATEGORY, "air-miner", "Mine at full speed while airborne.");
    }

    @Override
    public void onActivate() {
        claimLeft = 0;
        lying = false;
        injecting = false;
        claims = 0;
    }

    @Override
    public void onDeactivate() {
        if (lying) restore();
    }

    public boolean liftsPenalty() {
        return isActive() && canClaim();
    }

    private boolean canClaim() {
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        if (mc.player.isOnGround()) return false;
        if (mc.player.isGliding() && !whileGliding.get()) return false;
        if (mc.player.isCreative() || mc.player.isSpectator()) return false;

        double speed = mc.player.getVelocity().horizontalLength();
        return speed <= maxSpeed.get();
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (claimLeft > 0 && --claimLeft == 0 && lying) restore();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null || injecting) return;

        if (event.packet instanceof PlayerActionC2SPacket p) {
            PlayerActionC2SPacket.Action a = p.getAction();
            if ((a == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK || a == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK)
                && canClaim()) {
                claim();
            }
            return;
        }

        if (event.packet instanceof PlayerMoveC2SPacket move && claimLeft > 0 && move.changesPosition()) {
            ((PlayerMoveC2SPacketAccessor) move).quiettee$setOnGround(true);
        }
    }

    private void claim() {
        injecting = true;
        try {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYaw(), mc.player.getPitch(), true, mc.player.horizontalCollision));
        }
        finally {
            injecting = false;
        }

        claimLeft = claimWindow.get();
        lying = true;
        claims++;
        if (chatDebug.get()) info("ground claimed for the break (#%d)", claims);
    }

    private void restore() {
        lying = false;
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        injecting = true;
        try {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
        }
        finally {
            injecting = false;
        }
    }
}
