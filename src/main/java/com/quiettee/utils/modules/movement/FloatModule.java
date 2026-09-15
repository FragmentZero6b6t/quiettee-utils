package com.quiettee.utils.modules.movement;

import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.mixin.PlayerMoveC2SPacketAccessor;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Flight;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkRenderDistanceCenterS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class FloatModule extends Module {
    private static final int ARMOR_SLOT_CHEST = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgKit = settings.createGroup("Kit");

    private final SettingGroup sgRelease = settings.createGroup("Release");

    private final SettingGroup sgProbe = settings.createGroup("Probe");

    private final SettingGroup sgTelemetry = settings.createGroup("Telemetry");

    private final Setting<Double> creepSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("creep-speed")
        .description("Drift speed while pinned. 0 disables.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 0.6)
        .build()
    );

    private final Setting<Double> creepVertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("creep-vertical")
        .description("Vertical trim on jump or sneak.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 0.6)
        .build()
    );

    private final Setting<Integer> lookRate = sgGeneral.add(new IntSetting.Builder()
        .name("look-rate")
        .description("Minimum ticks between look packets.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> adaptiveLook = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive-look")
        .description("Back off look rate after corrections.")
        .defaultValue(true)
        .build()
    );

    private final Setting<CreepMode> creepMode = sgGeneral.add(new EnumSetting.Builder<CreepMode>()
        .name("creep-mode")
        .description("Continuous or stutter movement.")
        .defaultValue(CreepMode.Stutter)
        .visible(() -> creepSpeed.get() > 0 || creepVertical.get() > 0)
        .build()
    );

    private final Setting<Integer> creepBurst = sgGeneral.add(new IntSetting.Builder()
        .name("creep-burst")
        .description("Ticks of movement per stutter.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .visible(() -> creepMode.get() == CreepMode.Stutter && (creepSpeed.get() > 0 || creepVertical.get() > 0))
        .build()
    );

    private final Setting<Integer> creepRest = sgGeneral.add(new IntSetting.Builder()
        .name("creep-rest")
        .description("Ticks of stillness per stutter.")
        .defaultValue(17)
        .min(0)
        .sliderRange(0, 40)
        .visible(() -> creepMode.get() == CreepMode.Stutter && (creepSpeed.get() > 0 || creepVertical.get() > 0))
        .build()
    );

    private final Setting<Boolean> snapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("snap-back")
        .description("Glide back to the anchor when shoved.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> claimCollision = sgGeneral.add(new BoolSetting.Builder()
        .name("claim-collision")
        .description("Also claim a wall collision.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> antiAfk = sgGeneral.add(new IntSetting.Builder()
        .name("anti-afk")
        .description("Seconds between tiny yaw wiggles.")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<Boolean> standDown = sgGeneral.add(new BoolSetting.Builder()
        .name("stand-down-others")
        .description("Pause flight modules while pinned.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Armor> armor = sgKit.add(new EnumSetting.Builder<Armor>()
        .name("armor")
        .description("Swap to a chestplate or keep the elytra.")
        .defaultValue(Armor.Chestplate)
        .build()
    );

    private final Setting<Chestplate> chestplate = sgKit.add(new EnumSetting.Builder<Chestplate>()
        .name("chestplate")
        .description("Which chestplate to use.")
        .defaultValue(Chestplate.PreferNetherite)
        .visible(() -> armor.get() == Armor.Chestplate)
        .build()
    );

    private final Setting<Boolean> restoreElytra = sgKit.add(new BoolSetting.Builder()
        .name("restore-elytra")
        .description("Put the elytra back on release.")
        .defaultValue(true)
        .visible(() -> armor.get() == Armor.Chestplate)
        .build()
    );

    private final Setting<Boolean> autoGlide = sgRelease.add(new BoolSetting.Builder()
        .name("auto-glide")
        .description("Open the wings on release.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> releaseOnTeleport = sgRelease.add(new BoolSetting.Builder()
        .name("release-on-teleport")
        .description("Release when the server moves you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> releaseDistance = sgRelease.add(new DoubleSetting.Builder()
        .name("release-distance")
        .description("Correction distance that counts as eviction.")
        .defaultValue(1.0)
        .min(0.0)
        .sliderRange(0.0, 5.0)
        .visible(releaseOnTeleport::get)
        .build()
    );

    private final Setting<Integer> noopLimit = sgRelease.add(new IntSetting.Builder()
        .name("resync-limit")
        .description("Release after this many resyncs in 20 seconds.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 40)
        .visible(releaseOnTeleport::get)
        .build()
    );

    private final Setting<Boolean> releaseOnDamage = sgRelease.add(new BoolSetting.Builder()
        .name("release-on-damage")
        .description("Release when you take damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> releaseBelow = sgRelease.add(new DoubleSetting.Builder()
        .name("release-below")
        .description("Release below this health. 0 disables.")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 20.0)
        .build()
    );

    private final Setting<Boolean> probe = sgProbe.add(new BoolSetting.Builder()
        .name("probe")
        .description("Ladder the creep speed to find the limit.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> probeStart = sgProbe.add(new DoubleSetting.Builder()
        .name("probe-start")
        .description("First probe speed.")
        .defaultValue(0.02)
        .min(0.01)
        .sliderRange(0.01, 0.5)
        .visible(probe::get)
        .build()
    );

    private final Setting<Double> probeStep = sgProbe.add(new DoubleSetting.Builder()
        .name("probe-step")
        .description("Speed added per rung.")
        .defaultValue(0.02)
        .min(0.01)
        .sliderRange(0.01, 0.25)
        .visible(probe::get)
        .build()
    );

    private final Setting<Integer> probeDwell = sgProbe.add(new IntSetting.Builder()
        .name("probe-dwell")
        .description("Clean ticks before stepping up.")
        .defaultValue(60)
        .min(20)
        .sliderRange(20, 200)
        .visible(probe::get)
        .build()
    );

    private final Setting<Boolean> chatDebug = sgTelemetry.add(new BoolSetting.Builder()
        .name("chat-debug")
        .description("Narrate the pin in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fileDebug = sgTelemetry.add(new BoolSetting.Builder()
        .name("debug-file")
        .description("Write a log to .minecraft/float.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> cageWatch = sgTelemetry.add(new BoolSetting.Builder()
        .name("cage-watch")
        .description("Warn if you get walled in.")
        .defaultValue(true)
        .build()
    );

    private Vec3d anchor;
    private long startedAt;
    private int ticks;
    private int movingTicks;
    private int packets;
    private int corrections;
    private boolean announcedSurvival;
    private boolean pendingRelease;
    private String releaseReason;
    private double lastHealth;
    private int cageCheckIn = -1;
    private boolean afkFlip;
    private int noopCorrections;
    private final ArrayDeque<Integer> recentNoops = new ArrayDeque<>();
    private int lookInterval;
    private int lastLookTick;
    private int lookSuppressed;
    private int lastCorrectionTick;
    private static volatile boolean keepLook;

    private double activeCreep;
    private double bestClean;
    private int dwellLeft;
    private boolean ladderDone;

    private final List<Module> stoodDown = new ArrayList<>();

    private PrintWriter dbgOut;

    public FloatModule() {
        super(QuietteeUtils.CATEGORY, "float", "Hang motionless in mid air with wings closed.");
    }

    @Override
    public void onActivate() {
        anchor = mc.player.getEntityPos();
        startedAt = java.lang.System.currentTimeMillis();
        ticks = 0;
        movingTicks = 0;
        packets = 0;
        corrections = 0;
        noopCorrections = 0;
        recentNoops.clear();
        lookInterval = lookRate.get();
        lastLookTick = -100;
        lookSuppressed = 0;
        lastCorrectionTick = 0;
        keepLook = false;
        announcedSurvival = false;
        pendingRelease = false;
        releaseReason = null;
        cageCheckIn = -1;
        lastHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        stoodDown.clear();

        activeCreep = probe.get() ? probeStart.get() : creepSpeed.get();
        bestClean = 0;
        dwellLeft = probeDwell.get();
        ladderDone = false;

        openDebug();

        if (standDown.get()) standDownOthers();
        if (armor.get() == Armor.Chestplate) equipChestplate();

        mc.player.setVelocity(0, 0, 0);
        mc.player.fallDistance = 0;

        say("pinned at %.1f / %.1f / %.1f%s", anchor.x, anchor.y, anchor.z,
            probe.get() ? " - PROBE ladder armed, hold a movement key" : "");
        dbg("ACTIVATE anchor %.3f/%.3f/%.3f gliding=%b onGroundClient=%b creep=%.3f",
            anchor.x, anchor.y, anchor.z, mc.player.isGliding(), mc.player.isOnGround(), activeCreep);
    }

    @Override
    public void onDeactivate() {

        release();

        long held = (java.lang.System.currentTimeMillis() - startedAt) / 1000L;
        say("released after %ds - %d packets, %d corrections (%d of them harmless no-ops)%s",
            held, packets, corrections, noopCorrections, releaseReason == null ? "" : " (" + releaseReason + ")");
        dbg("DEACTIVATE held=%ds packets=%d corrections=%d lookSuppressed=%d finalLookInterval=%d reason=%s",
            held, packets, corrections, lookSuppressed, lookInterval, releaseReason);

        if (probe.get() && bestClean > 0) {
            say("probe verdict: the server tolerated a ground-claimed creep of %.3f b/t (%.1f%% of vanilla walking speed)",
                bestClean, bestClean / 0.216 * 100.0);
        }

        keepLook = false;
        if (standDown.get()) standUpOthers();
        closeDebug();
        anchor = null;
    }

    private void release() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        boolean airborne = !mc.player.isOnGround();

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.player.getYaw(), mc.player.getPitch(), false, mc.player.horizontalCollision));

        if (armor.get() == Armor.Chestplate && restoreElytra.get()) equipElytra();

        if (autoGlide.get() && airborne
            && mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER)
            && !mc.player.isTouchingWater()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            dbg("RELEASE wings opened");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || event.type != MovementType.SELF) return;

        Vec3d creep = creepVector();
        double vx = creep.x, vy = creep.y, vz = creep.z;

        if (snapBack.get() && anchor != null) {

            if (creep.lengthSquared() < 1e-9) {
                Vec3d back = anchor.subtract(mc.player.getEntityPos());
                double d = back.length();
                if (d > 1e-4) {
                    double stepCap = Math.max(0.05, creepSpeed.get());
                    double f = Math.min(1.0, stepCap / d);
                    vx = back.x * f;
                    vy = back.y * f;
                    vz = back.z * f;
                }
            }
            else {
                anchor = mc.player.getEntityPos().add(vx, vy, vz);
            }
        }

        ((IVec3d) event.movement).meteor$set(vx, vy, vz);
    }

    private Vec3d creepVector() {
        if (mc.player == null || mc.currentScreen != null) return Vec3d.ZERO;

        double speed = probe.get() ? activeCreep : creepSpeed.get();

        if (creepMode.get() == CreepMode.Stutter) {
            int cycle = creepBurst.get() + creepRest.get();
            if (cycle > 0 && ticks % cycle >= creepBurst.get()) return Vec3d.ZERO;
        }

        Vec3d fwd = Vec3d.fromPolar(0, mc.player.getYaw());
        Vec3d rgt = Vec3d.fromPolar(0, mc.player.getYaw() + 90);

        double fx = 0, fz = 0;
        if (mc.options.forwardKey.isPressed()) { fx += fwd.x; fz += fwd.z; }
        if (mc.options.backKey.isPressed())    { fx -= fwd.x; fz -= fwd.z; }
        if (mc.options.rightKey.isPressed())   { fx += rgt.x; fz += rgt.z; }
        if (mc.options.leftKey.isPressed())    { fx -= rgt.x; fz -= rgt.z; }

        double len = Math.sqrt(fx * fx + fz * fz);
        double vx = 0, vz = 0;
        if (len > 1e-6 && speed > 0) {
            vx = fx / len * speed;
            vz = fz / len * speed;
        }

        double vy = 0;
        double vs = creepVertical.get();
        if (vs > 0) {
            if (mc.options.jumpKey.isPressed()) vy = vs;
            else if (mc.options.sneakKey.isPressed()) vy = -vs;
        }

        return new Vec3d(vx, vy, vz);
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        ticks++;

        if (pendingRelease) {
            toggle();
            return;
        }

        mc.player.setVelocity(0, 0, 0);
        mc.player.fallDistance = 0;

        Vec3d creep = creepVector();
        boolean moving = creep.lengthSquared() > 1e-9;
        if (moving) movingTicks++;

        if (probe.get() && !ladderDone && moving) {
            if (--dwellLeft <= 0) {
                bestClean = activeCreep;
                activeCreep += probeStep.get();
                dwellLeft = probeDwell.get();
                say("probe: %.3f b/t held clean, stepping up to %.3f", bestClean, activeCreep);
                dbg("PROBE rung clean=%.3f next=%.3f", bestClean, activeCreep);
            }
        }

        if (!announcedSurvival && ticks > 100 && !mc.player.isGliding() && corrections == 0) {
            announcedSurvival = true;
            say("4 seconds of un-glided hover with no kick - this server allows flight, the pin is doing the rest");
            dbg("SURVIVED the 80-tick vanilla floating window");
        }

        if (snapBack.get() && anchor != null && !moving) {
            double drift = mc.player.getEntityPos().distanceTo(anchor);
            if (drift > 1.5) {
                requestRelease(String.format("shoved %.1f blocks off the anchor", drift));
                dbg("DRIFT %.2f blocks off anchor - releasing", drift);
            }
        }

        if (adaptiveLook.get() && lookInterval > lookRate.get() && ticks - lastCorrectionTick > 600) {
            lookInterval = Math.max(lookRate.get(), lookInterval - 2);
            lastCorrectionTick = ticks;
            dbg("ADAPT look interval -> %d ticks after 600 clean ticks", lookInterval);
        }

        if (cageCheckIn > 0 && --cageCheckIn == 0) checkCage();

        if (antiAfk.get() > 0 && ticks % (antiAfk.get() * 20) == 0) {
            mc.player.setYaw(mc.player.getYaw() + (afkFlip ? 0.05f : -0.05f));
            afkFlip = !afkFlip;
            dbg("ANTIAFK yaw wiggle");
        }

        double hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (releaseOnDamage.get() && hp < lastHealth - 0.01) {
            requestRelease(String.format("took %.1f damage", lastHealth - hp));
        }
        else if (releaseBelow.get() > 0 && hp < releaseBelow.get()) {
            requestRelease(String.format("health %.1f below %.1f", hp, releaseBelow.get()));
        }
        lastHealth = hp;

        if (fileDebug.get() && ticks % 20 == 0) {
            Vec3d p = mc.player.getEntityPos();
            dbg("TICK pos=%.3f/%.3f/%.3f creep=%.3f moving=%b gliding=%b hp=%.1f",
                p.x, p.y, p.z, creep.length(), moving, mc.player.isGliding(), hp);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null || !(event.packet instanceof PlayerMoveC2SPacket p)) return;

        boolean lookOnly = !p.changesPosition() && p.changesLook();
        if (lookOnly && lookInterval > 1 && ticks - lastLookTick < lookInterval
            && !mc.options.attackKey.isPressed() && !mc.options.useKey.isPressed()) {
            lookSuppressed++;
            event.cancel();
            return;
        }
        if (p.changesLook()) lastLookTick = ticks;

        ((PlayerMoveC2SPacketAccessor) p).quiettee$setOnGround(true);
        if (claimCollision.get()) ((PlayerMoveC2SPacketAccessor) p).quiettee$setHorizontalCollision(true);
        packets++;

        if (fileDebug.get()) {
            dbg("PKT %-9s pos=%s look=%s onGround=true%s",
                p.changesPosition() ? (p.changesLook() ? "pos+look" : "pos") : (p.changesLook() ? "look" : "ground"),
                p.changesPosition() ? String.format("%.3f/%.3f/%.3f", p.getX(0), p.getY(0), p.getZ(0)) : "-",
                p.changesLook() ? String.format("%.1f/%.1f", (double) p.getYaw(0), (double) p.getPitch(0)) : "-",
                claimCollision.get() ? " collision=true" : "");
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (event.packet instanceof PlayerPositionLookS2CPacket p) {
            corrections++;

            Vec3d cur = mc.player.getEntityPos();
            Set<PositionFlag> rel = p.relatives();
            Vec3d raw = p.change().position();
            double tx = rel.contains(PositionFlag.X) ? cur.x + raw.x : raw.x;
            double ty = rel.contains(PositionFlag.Y) ? cur.y + raw.y : raw.y;
            double tz = rel.contains(PositionFlag.Z) ? cur.z + raw.z : raw.z;
            double dist = Math.sqrt((tx - cur.x) * (tx - cur.x) + (ty - cur.y) * (ty - cur.y) + (tz - cur.z) * (tz - cur.z));

            anchor = new Vec3d(tx, ty, tz);

            keepLook = true;

            if (adaptiveLook.get() && lookInterval < 20) {
                lookInterval = Math.min(20, lookInterval + 4);
                dbg("ADAPT look interval -> %d ticks after a correction", lookInterval);
            }
            lastCorrectionTick = ticks;

            boolean material = dist >= releaseDistance.get();

            if (material) {
                say("SERVER CORRECTION #%d - moved %.2f blocks to %.1f / %.1f / %.1f", corrections, dist, tx, ty, tz);
            }
            else {
                noopCorrections++;
                recentNoops.addLast(ticks);
                while (!recentNoops.isEmpty() && ticks - recentNoops.peekFirst() > 400) recentNoops.pollFirst();
                say("harmless resync #%d (moved %.2f blocks - the server agrees with where we are)", noopCorrections, dist);
            }

            dbg("CORRECTION #%d id=%d dist=%.3f %s -> %.3f/%.3f/%.3f (we were %.3f/%.3f/%.3f) creep=%.3f",
                corrections, p.teleportId(), dist, material ? "MATERIAL" : "NO-OP",
                tx, ty, tz, cur.x, cur.y, cur.z, activeCreep);

            if (probe.get() && !ladderDone && material) {

                ladderDone = true;
                activeCreep = bestClean;
                say("probe ceiling: %.3f b/t was clean, %.3f drew a %.2f-block correction", bestClean, activeCreep + probeStep.get(), dist);
                dbg("PROBE ceiling clean=%.3f failed=%.3f", bestClean, activeCreep + probeStep.get());
            }

            if (cageWatch.get() && material) cageCheckIn = 6;

            if (!material) {
                if (recentNoops.size() > noopLimit.get()) {
                    requestRelease(String.format("%d harmless resyncs in 20s - something is escalating", recentNoops.size()));
                }
                return;
            }

            if (releaseOnTeleport.get() && !probe.get()) {
                requestRelease(String.format("server corrected us %.2f blocks", dist));
            }
            return;
        }

        if (fileDebug.get() && event.packet instanceof ChunkRenderDistanceCenterS2CPacket p) {
            double cx = p.getChunkX() * 16 + 8, cz = p.getChunkZ() * 16 + 8;
            dbg("SRVPOS server thinks we are near %.0f/%.0f (%.1f blocks from us)",
                cx, cz, Math.hypot(mc.player.getX() - cx, mc.player.getZ() - cz));
            return;
        }

        if (event.packet instanceof DisconnectS2CPacket p) {
            String reason = p.reason().getString();
            dbg("*** DISCONNECTED: %s", reason);
            if (reason.toLowerCase().contains("flying") || reason.toLowerCase().contains("flight")) {
                dbg("*** that is vanilla's floating kick - this server does NOT allow flight and no claim can prevent it");
            }
        }
    }

    private void checkCage() {
        if (mc.player == null || mc.world == null) return;

        BlockPos feet = mc.player.getBlockPos();
        int walls = 0;
        for (BlockPos side : new BlockPos[]{feet.north(), feet.south(), feet.east(), feet.west(), feet.up(2), feet.down()}) {
            if (!mc.world.getBlockState(side).isAir()) walls++;
        }

        if (walls >= 5) {
            warning("CAGED - %d/6 sides solid at %s. They did not honour the ground claim; assume the check now reads supportingBlockPos.", walls, feet.toShortString());
            dbg("CAGE %d/6 solid around %s", walls, feet.toShortString());
        }
        else {
            dbg("CAGE clear (%d/6 solid) around %s", walls, feet.toShortString());
        }
    }

    private void requestRelease(String reason) {
        if (pendingRelease) return;
        pendingRelease = true;
        releaseReason = reason;
    }

    private void equipChestplate() {
        ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!worn.contains(DataComponentTypes.GLIDER)) return;

        int best = -1;
        boolean done = false;
        for (int i = 0; i < mc.player.getInventory().getMainStacks().size() && !done; i++) {
            Item item = mc.player.getInventory().getMainStacks().get(i).getItem();
            switch (chestplate.get()) {
                case Diamond -> { if (item == Items.DIAMOND_CHESTPLATE) { best = i; done = true; } }
                case Netherite -> { if (item == Items.NETHERITE_CHESTPLATE) { best = i; done = true; } }
                case PreferDiamond -> {
                    if (item == Items.DIAMOND_CHESTPLATE) { best = i; done = true; }
                    else if (item == Items.NETHERITE_CHESTPLATE) best = i;
                }
                case PreferNetherite -> {
                    if (item == Items.NETHERITE_CHESTPLATE) { best = i; done = true; }
                    else if (item == Items.DIAMOND_CHESTPLATE) best = i;
                }
            }
        }

        if (best == -1) {
            warning("no chestplate in the inventory - staying in the elytra");
            dbg("KIT no chestplate found");
            return;
        }

        InvUtils.move().from(best).toArmor(ARMOR_SLOT_CHEST);
        mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(0));
        dbg("KIT chestplate from slot %d", best);
    }

    private void equipElytra() {
        if (mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER)) return;

        for (int i = 0; i < mc.player.getInventory().getMainStacks().size(); i++) {
            if (mc.player.getInventory().getMainStacks().get(i).contains(DataComponentTypes.GLIDER)) {
                InvUtils.move().from(i).toArmor(ARMOR_SLOT_CHEST);
                mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(0));
                dbg("KIT elytra restored from slot %d", i);
                return;
            }
        }

        warning("no elytra in the inventory - you are releasing without wings");
    }

    private void standDownOthers() {
        Class<?>[] rivals = { ElytraFly.class, Flight.class };

        for (Class<?> klass : rivals) {
            @SuppressWarnings("unchecked")
            Module m = Modules.get().get((Class<Module>) klass);
            if (m != null && m.isActive()) {
                m.toggle();
                stoodDown.add(m);
            }
        }

        if (!stoodDown.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Module m : stoodDown) names.append(names.isEmpty() ? "" : ", ").append(m.name);
            say("stood down %s", names);
            dbg("STANDDOWN %s", names);
        }
    }

    private void standUpOthers() {
        for (Module m : stoodDown) if (!m.isActive()) m.toggle();
        stoodDown.clear();
    }

    private void say(String fmt, Object... args) {
        if (chatDebug.get()) info(fmt, args);
    }

    private void openDebug() {
        closeDebug();
        if (!fileDebug.get()) return;
        try {
            File dir = new File(mc.runDirectory, "float");
            dir.mkdirs();
            File f = new File(dir, "float-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
            dbgOut = new PrintWriter(new BufferedWriter(new FileWriter(f, true)));
            dbg("=== FLOAT r90 black box - %s ===", new Date());
            dbg("legend: exactly one bit on the wire is a lie (onGround). Position is always honest.");
            for (SettingGroup g : settings) for (Setting<?> s : g) dbg("cfg %s = %s", s.name, s.get());
            info("black box recording to %s", f.getAbsolutePath());
        }
        catch (Exception e) {
            dbgOut = null;
            info("black box failed to open: %s", e.getMessage());
        }
    }

    private void closeDebug() {
        if (dbgOut != null) {
            dbgOut.flush();
            dbgOut.close();
            dbgOut = null;
        }
    }

    private void dbg(String fmt, Object... args) {
        if (dbgOut == null) return;
        Object[] all = new Object[args.length + 1];
        all[0] = ticks;
        java.lang.System.arraycopy(args, 0, all, 1, args.length);
        dbgOut.printf("[%d] " + fmt + "%n", all);
        dbgOut.flush();
    }

    @Override
    public String getInfoString() {
        if (anchor == null) return null;
        long held = (java.lang.System.currentTimeMillis() - startedAt) / 1000L;
        if (probe.get() && !ladderDone) return String.format("%ds probe %.2f", held, activeCreep);
        return String.format("%ds", held);
    }

    public static boolean keepLookArmed() {
        return keepLook;
    }

    public static boolean consumeKeepLook() {
        boolean a = keepLook;
        keepLook = false;
        return a;
    }

    public enum CreepMode {
        Continuous,
        Stutter
    }

    public enum Armor {
        Chestplate,
        KeepElytra
    }

    public enum Chestplate {
        Diamond,
        Netherite,
        PreferDiamond,
        PreferNetherite
    }
}
