package com.quiettee.utils.modules.movement;

import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.modules.combat.BoatShot;
import com.quiettee.utils.modules.combat.BoatShotEvents;
import com.quiettee.utils.modules.combat.BoatShotCycle;
import com.quiettee.utils.events.PlayerPositionLookEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoatPhase extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDive = settings.createGroup("Quick Dive");
    private final SettingGroup sgRecovery = settings.createGroup("Recovery");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgMeter = settings.createGroup("Speedometer");

    private final Setting<Boolean> speedometer = sgMeter.add(new BoolSetting.Builder()
        .name("speedometer").description("Show boat speed on screen.")
        .defaultValue(true).build());
    private final Setting<Double> meterScale = sgMeter.add(new DoubleSetting.Builder()
        .name("meter-scale").description("Speedometer text size.").defaultValue(1.4).range(.6,3).sliderRange(.6,3).build());
    private final Setting<Integer> meterX = sgMeter.add(new IntSetting.Builder()
        .name("meter-x").description("Speedometer horizontal position.").defaultValue(2).range(0,100).sliderRange(0,100).build());
    private final Setting<Integer> meterY = sgMeter.add(new IntSetting.Builder()
        .name("meter-y").description("Speedometer vertical position.").defaultValue(30).range(0,100).sliderRange(0,100).build());
    private final Setting<Keybind> meterKey = sgMeter.add(new KeybindSetting.Builder()
        .name("speedometer-key").description("Toggle the speedometer.")
        .defaultValue(Keybind.none()).action(this::toggleSpeedometer).build());

    private final Setting<Boolean> phase = sgGeneral.add(new BoolSetting.Builder()
        .name("phase").description("Move the boat through blocks.")
        .defaultValue(true).build());
    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed").description("Boat speed in blocks per tick.")
        .defaultValue(2.8).min(0).max(9).sliderRange(0, 9).build());
    private final Setting<Double> horizontalLimit = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-limit").description("Speed cap for limited travel.")
        .defaultValue(0.99).min(0.01).max(9).sliderRange(0.01, 3).build());
    private final Setting<Boolean> fastAir = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-air-travel").description("Single packet speed without substeps.")
        .defaultValue(false).build());
    private final Setting<Boolean> hashTravel = sgGeneral.add(new BoolSetting.Builder()
        .name("correction-hash-travel").description("Failed experiment. Leave off.")
        .defaultValue(false).build());
    private final Setting<Double> hashSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("hash-travel-speed").description("Speed for the hash experiment.")
        .defaultValue(3.2).range(.1,3.8).sliderRange(.1,3.8).visible(hashTravel::get).build());
    private final Setting<Boolean> airSubsteps = sgGeneral.add(new BoolSetting.Builder()
        .name("air-substeps").description("Split air travel into small updates.")
        .defaultValue(true).build());
    private final Setting<Double> substepSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("substep-speed").description("Horizontal blocks per tick with substeps.")
        .defaultValue(3.2).min(.1).max(BoatPhaseAirSteps.MAX_SPEED).sliderRange(.1, 3.8).visible(airSubsteps::get).build());
    private final Setting<Boolean> extendedSubsteps = sgGeneral.add(new BoolSetting.Builder()
        .name("extended-substeps").description("Use experimental speed in clear air.")
        .defaultValue(true).visible(airSubsteps::get).build());
    private final Setting<Double> experimentalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("experimental-speed").description("Clear air blocks per tick, up to 9.")
        .defaultValue(6.5).range(3.8,BoatPhaseAirSteps.TRIAL_SPEED).sliderRange(3.8,BoatPhaseAirSteps.TRIAL_SPEED)
        .onChanged(value -> retryExtension()).visible(() -> airSubsteps.get() && extendedSubsteps.get()).build());
    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed").description("Vertical blocks per tick.")
        .defaultValue(3).min(0.1).max(BoatPhaseVertical.MAX_SPEED).sliderRange(0.1, BoatPhaseVertical.MAX_SPEED).build());
    private final Setting<Double> phaseHorizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("phase-horizontal-speed").description("Horizontal speed inside blocks.")
        .defaultValue(0.24).min(0.01).max(BoatPhaseMotion.MAX_PHASE_HORIZONTAL).sliderRange(0.01, 0.249).visible(phase::get).build());
    private final Setting<Boolean> adaptivePhase = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive-phase").description("Collision free travel plus phase tolerance.")
        .defaultValue(true).visible(phase::get).build());
    private final Setting<Double> phaseSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("phase-speed").description("Requested speed for adaptive phasing.")
        .defaultValue(3).min(0.24).max(9).sliderRange(0.24, 3).visible(() -> phase.get() && adaptivePhase.get()).build());
    private final Setting<Double> airAcceleration = sgGeneral.add(new DoubleSetting.Builder()
        .name("air-acceleration").description("Max horizontal velocity change per tick.")
        .defaultValue(0.1).min(0.01).max(1).sliderRange(0.01, 0.3).build());
    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-boat-yaw").description("Face the boat where you look.")
        .defaultValue(true).build());
    private final Setting<Boolean> stopLava = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-before-lava").description("Stop before crossing lava.")
        .defaultValue(true).build());
    private final Setting<Boolean> antiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-kick").description("Small real descents to avoid kicks.")
        .defaultValue(true).build());
    private final Setting<Keybind> diveKey = sgDive.add(new KeybindSetting.Builder()
        .name("dive-key").description("Start or cancel a quick dive.")
        .defaultValue(Keybind.none()).action(this::toggleDive).build());
    private final Setting<Double> diveDistance = sgDive.add(new DoubleSetting.Builder()
        .name("dive-distance").description("Quick dive distance.")
        .defaultValue(30).min(1).max(BoatPhaseMotion.MAX_TRAVEL).sliderRange(1, BoatPhaseMotion.MAX_TRAVEL).build());
    private final Setting<Boolean> diveWithPassenger = sgDive.add(new BoolSetting.Builder()
        .name("dive-with-passenger").description("Dive once when a passenger boards.")
        .defaultValue(false).build());
    private final Setting<Integer> correctionHold = sgRecovery.add(new IntSetting.Builder()
        .name("correction-hold").description("Ticks to pause after a correction.")
        .defaultValue(8).min(1).max(100).sliderRange(1, 40).build());
    private final Setting<Boolean> landedRetry = sgRecovery.add(new BoolSetting.Builder()
        .name("retry-after-landing").description("Retry fast travel after a landed remount.")
        .defaultValue(true).build());
    private final Setting<Boolean> remountRetry = sgRecovery.add(new BoolSetting.Builder()
        .name("retry-after-remount").description("Retry fast travel after any remount.")
        .defaultValue(true).build());
    private final Setting<Double> landedRetrySpeed = sgRecovery.add(new DoubleSetting.Builder()
        .name("landed-retry-speed").description("Substep speed after a landed retry.")
        .defaultValue(3.8).range(.1,BoatPhaseAirSteps.MAX_SPEED).sliderRange(.1,3.8).visible(landedRetry::get).build());
    private final Setting<Boolean> pauseOthers = sgRecovery.add(new BoolSetting.Builder()
        .name("pause-conflicting-modules").description("Pause other movement modules while driving.")
        .defaultValue(true).build());
    private final Setting<Boolean> debugFile = sgRecovery.add(new BoolSetting.Builder()
        .name("debug-file").description("Write a log to .minecraft/boat-phase.")
        .defaultValue(true).build());
    private final Setting<Keybind> cruiseKey = sgNavigation.add(new KeybindSetting.Builder()
        .name("cruise-key").description("Toggle cruise along your heading.")
        .defaultValue(Keybind.none()).action(this::toggleCruise).build());
    private final Setting<Keybind> surfaceKey = sgNavigation.add(new KeybindSetting.Builder()
        .name("surface-key").description("Rise to the next clear pocket.")
        .defaultValue(Keybind.none()).action(this::surface).build());
    private final Setting<Keybind> returnHeightKey = sgNavigation.add(new KeybindSetting.Builder()
        .name("return-height-key").description("Return to your boarding height.")
        .defaultValue(Keybind.none()).action(this::returnHeight).build());

    private static final String[] CONFLICTS = { "entity-control", "boat-noclip", "BoatNoclip", "lance", "lance-original",
        "mace-smash", "float", "glider-walk", "elytra-fly", "elytra-fly+", "flight", "poltergeist", "belfry", "geyser", "treadmill" };
    private final BoatPhaseMotion motion = new BoatPhaseMotion();
    private final BoatShotEvents eventGate = new BoatShotEvents();
    private final BoatPhaseTravel travel = new BoatPhaseTravel();
    private final BoatPhaseMeter meter = new BoatPhaseMeter();
    private final BoatPhaseRemount remount = new BoatPhaseRemount();
    private BoatPhaseTravel.Mode plannedMode = BoatPhaseTravel.Mode.LIMITED;
    private ClientConnection travelConnection;
    private ClientWorld travelWorld;
    private ClientWorld hashWorld;
    private ClientConnection hashConnection;
    private Integer correctionHash;
    private int plannedHashTick = -1;
    private Object lastPlayerCorrection, lastVehicleCorrection, lastInputPacket, lastObservationPacket;
    private Object lastBoardInteraction;
    private int lastGroundedAge = -1000;
    private String boardingRetryReason = "no grounded boarding interaction";
    private String lastModeDescription = "";
    private final List<Module> paused = new ArrayList<>();
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final AtomicInteger observationCount = new AtomicInteger();
    private volatile long epoch;
    private volatile ClientConnection connection;
    private ClientPlayerEntity player;
    private ClientWorld world;
    private ClientPlayNetworkHandler network;
    private AbstractBoatEntity boat;
    private boolean driver;
    private boolean correcting;
    private boolean hadGuest;
    private Vec3d lastWire;
    private Vec3d plannedFrom;
    private int plannedTick = -1;
    private int sentCount;
    private int corrections;
    private double requestedDiveDistance;
    private String travelName = "Quick dive";
    private boolean plannedThroughBlocks;
    private boolean plannedClear;
    private boolean plannedShotMovement;
    private boolean verticalRejected;
    private int lastFastVerticalTick = -100;
    private boolean adaptiveBlocked;
    private int lastAdaptiveTick = -100;
    private int lastSneakTick = -100;
    private boolean sentSneak;
    private int airborneTicks;
    private boolean cruising;
    private float cruiseYaw;
    private double entryHeight;
    private Vec3d lastStep = Vec3d.ZERO;
    private VehicleMoveC2SPacket substepPacket;
    private boolean splitting;
    private Vec3d substepOrigin;
    private String status = "board a boat";
    private PrintWriter log;

    private record Observation(long epoch, ClientConnection connection, Packet<?> packet) {}

    public BoatPhase() {
        super(QuietteeUtils.CATEGORY, "boat-phase", "Fly a boat through blocks. WASD, jump and sprint.", "ferryman");
    }

    public static BoatPhase active() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        BoatPhase module = modules.get(BoatPhase.class);
        return module != null && module.isActive() ? module : null;
    }

    @Override
    public void onActivate() {
        detach();
        correctionHash = null; hashWorld = null; hashConnection = null;
        travel.retry();
        verticalRejected = false;
        remount.reset();
        eventGate.reset();
        adaptiveBlocked = false;
        status = "board a boat";
    }

    @Override
    public void onDeactivate() { detach(); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        detach();
        travel.retry(); travelConnection = null;
        verticalRejected = false;
        remount.reset();
        correctionHash = null; hashWorld = null; hashConnection = null;
    }

    private boolean sameSession() {
        return player == mc.player && world == mc.world && network == mc.getNetworkHandler();
    }

    private boolean owns(AbstractBoatEntity candidate) {
        return boat == candidate && driver && sameSession() && mc.player != null && mc.player.isAlive()
            && !candidate.isRemoved() && mc.player.getRootVehicle() == candidate && candidate.getControllingPassenger() == mc.player;
    }

    public boolean phases(AbstractBoatEntity candidate) {
        return owns(candidate) && phase.get() && plannedThroughBlocks && !correcting && motion.holdTicks() == 0 && mc.currentScreen == null;
    }

    public Vec3d movement(AbstractBoatEntity candidate, Vec3d vanilla) {
        if (!owns(candidate)) return vanilla;
        BoatShot shot = BoatShot.active();
        boolean inventoryFollow=shot!=null && shot.inventoryFollowAllowed();
        plannedShotMovement = false;
        plannedMode = travelMode();
        if (lockYaw.get()) candidate.setYaw(mc.player.getYaw());
        if (correcting || motion.holdTicks() > 0 || mc.currentScreen != null && !inventoryFollow) {
            candidate.setVelocity(Vec3d.ZERO);
            return Vec3d.ZERO;
        }
        Vec3d from = candidate.getEntityPos();
        Vec3d shotStep = shot == null ? null : shot.movement(candidate);
        if (shotStep != null) {
            plannedShotMovement = shot.singleUpdateMovement();
            cruising = false;
            motion.cancelTravel();
            plannedThroughBlocks = false;
            plannedFrom = from;
            plannedTick = motion.tick();
            status = "boat shot";
            plannedClear = clearTravel(candidate, shotStep);
            shotStep = hashStep(candidate, shotStep);
            candidate.setVelocity(shotStep);
            return shotStep;
        }
        double forward = (Input.isPressed(mc.options.forwardKey) ? 1 : 0) - (Input.isPressed(mc.options.backKey) ? 1 : 0);
        double sideways = (Input.isPressed(mc.options.leftKey) ? 1 : 0) - (Input.isPressed(mc.options.rightKey) ? 1 : 0);
        if (forward != 0 || sideways != 0) cruising = false;
        float yaw = mc.player.getYaw();
        if (cruising) { forward = 1; yaw = cruiseYaw; }
        boolean up = Input.isPressed(mc.options.jumpKey), down = Input.isPressed(mc.options.sprintKey);
        double floor = world.getBottomY() + 2;
        double topOffset = candidate.streamSelfAndPassengers().mapToDouble(e -> e.getBoundingBox().maxY - candidate.getY()).max().orElse(candidate.getHeight());
        double ceiling = world.getTopYInclusive() - topOffset;
        double airLimit = travelSpeed();
        double airSpeed = airLimit;
        double solidSpeed = Math.min(phaseSpeed.get(), horizontalLimit.get());
        boolean adaptive = phase.get() && adaptivePhase.get() && !adaptiveBlocked;
        BoatPhaseMotion.Point step = motion.plan(point(from), forward, sideways, up, down, yaw,
            adaptive ? Math.max(airSpeed, solidSpeed) : airSpeed, travelVerticalSpeed(), floor, ceiling, false);
        Vec3d delta = new Vec3d(step.x(), step.y(), step.z());
        Vec3d followStep = shot == null ? null : shot.followMovement(candidate, airLimit, Math.min(9, travelVerticalSpeed()));
        if(inventoryFollow && followStep==null) { candidate.setVelocity(Vec3d.ZERO);return Vec3d.ZERO; }
        if (followStep != null) {
            cruising = false;
            motion.cancelTravel();

            delta = followStep;
            if (delta.lengthSquared() < 1e-10 && antiKick.get() && !supported(candidate)
                && motion.tick() % 10 < 2 && clearTravel(candidate, new Vec3d(0, -.08, 0))) delta = new Vec3d(0, -.08, 0);
            plannedThroughBlocks = false;
            plannedFrom = from;
            plannedTick = motion.tick();
            if (!shot.safeTravel(candidate, delta)) delta = Vec3d.ZERO;
            plannedClear = clearTravel(candidate, delta);
            delta = hashStep(candidate, delta);
            candidate.setVelocity(delta);
            status = speedFallback() ? "following (speed fallback)" : "following";
            return delta;
        }
        boolean throughBlocks = phase.get() && !world.isSpaceEmpty(candidate, candidate.getBoundingBox().stretch(delta).contract(1e-7));
        boolean embedded = !world.isSpaceEmpty(candidate, candidate.getBoundingBox().contract(1e-7));
        if (!embedded && !supported(candidate) && !candidate.isTouchingWater()) airborneTicks++;
        else airborneTicks = 0;
        if (throughBlocks) {
            if (adaptive) {
                step = motion.plan(point(from), forward, sideways, up, down, yaw, solidSpeed, travelVerticalSpeed(), floor, ceiling, false);
                step = BoatPhaseMotion.collisionStep(step, phaseHorizontalSpeed.get(), p -> {
                    Vec3d desired = new Vec3d(p.x(), p.y(), p.z());
                    return point(Entity.adjustMovementForCollisions(candidate, desired, candidate.getBoundingBox(), world,
                        world.getEntityCollisions(candidate, candidate.getBoundingBox().stretch(desired))));
                });
                if (Math.hypot(step.x(), step.z()) > phaseHorizontalSpeed.get() + 1e-6) lastAdaptiveTick = motion.tick();
            } else step = BoatPhaseMotion.phaseStep(step, Math.min(phaseHorizontalSpeed.get(), BoatPhaseMotion.PROVEN_PHASE_HORIZONTAL));
            delta = new Vec3d(step.x(), step.y(), step.z());
        } else {
            step = motion.plan(point(from), forward, sideways, up, down, yaw, airSpeed, travelVerticalSpeed(), floor, ceiling, false);
            step = BoatPhaseMotion.airStep(step, BoatPhaseMotion.horizontalStep(point(lastStep), airLimit), airAcceleration.get(), airborneTicks,
                antiKick.get(), up || down || motion.diveRemaining() > 0 || motion.riseRemaining() > 0, from.y - floor);
            delta = new Vec3d(step.x(), step.y(), step.z());
            if (phase.get() && !world.isSpaceEmpty(candidate, candidate.getBoundingBox().stretch(delta).contract(1e-7))) {
                throughBlocks = true;
                step = BoatPhaseMotion.horizontalStep(step, horizontalLimit.get());
                step = BoatPhaseMotion.phaseStep(step, Math.min(phaseHorizontalSpeed.get(), BoatPhaseMotion.PROVEN_PHASE_HORIZONTAL));
                delta = new Vec3d(step.x(), step.y(), step.z());
            }
        }
        Vec3d waterStep=waterDeparture(candidate,delta.y);
        if(waterStep!=null) {delta=waterStep;throughBlocks=false;}
        plannedThroughBlocks = throughBlocks;
        Box path = candidate.getBoundingBox().stretch(delta);
        if (!loaded(path)) {
            status = "waiting for chunks";
            delta = Vec3d.ZERO;
            motion.cancelTravel();
        } else if (stopLava.get() && containsLava(path)) {
            status = "lava ahead";
            delta = Vec3d.ZERO;
            motion.cancelTravel();
        } else if (motion.diveRemaining() > 0 && from.y + delta.y <= floor + 1e-6) {
            status = "world floor";
            motion.cancelTravel();
        } else if (motion.riseRemaining() > 0 && from.y + delta.y >= ceiling - 1e-6) {
            status = "world ceiling";
            motion.cancelTravel();
        } else {
            double remaining = Math.max(motion.diveRemaining(), motion.riseRemaining());
            status = remaining > 0 ? String.format(Locale.ROOT, "%s %.1f", travelName, remaining)
                : throughBlocks ? adaptive ? "adaptive phase" : adaptiveBlocked && adaptivePhase.get() ? "phasing (fallback)" : "phasing"
                : (cruising ? "cruising" : "piloting") + (speedFallback() ? " (speed fallback)" : "");
        }
        if(waterStep!=null)status=delta.y>0?"exiting water":"water exit blocked";
        plannedFrom = from;
        plannedTick = motion.tick();
        if (shot != null && !shot.safeTravel(candidate, delta)) {
            cruising = false;
            motion.cancelTravel();
            plannedThroughBlocks = false;
            status = shot.travelBlockReason();
            delta = Vec3d.ZERO;
        }
        plannedClear = !throughBlocks && clearTravel(candidate, delta);
        delta = hashStep(candidate, delta);
        candidate.setVelocity(delta);
        return delta;
    }

    private boolean supported(AbstractBoatEntity candidate) {
        return supported(candidate, .05);
    }

    private boolean supported(AbstractBoatEntity candidate, double depth) {
        Box b = candidate.getBoundingBox();
        return mc.world != null && !mc.world.isSpaceEmpty(candidate, new Box(b.minX + 1e-4, b.minY - depth, b.minZ + 1e-4,
            b.maxX - 1e-4, b.minY + 1e-7, b.maxZ - 1e-4));
    }

    public boolean shotReady(AbstractBoatEntity candidate) {
        return isActive() && owns(candidate) && !correcting && motion.holdTicks() == 0 && mc.currentScreen == null;
    }

    private boolean travelReady(AbstractBoatEntity candidate) {
        BoatShot shot=BoatShot.active();
        return isActive() && owns(candidate) && !correcting && motion.holdTicks()==0
            && (mc.currentScreen==null || shot!=null && shot.inventoryFollowAllowed());
    }

    public double shotVerticalLimit() { return Math.min(9, travelVerticalSpeed()); }
    public double captureVerticalLimit() { return Math.min(45, travelVerticalSpeed()); }
    public double captureCeiling(AbstractBoatEntity candidate) {
        if(candidate==null || mc.world==null)return Double.NEGATIVE_INFINITY;
        double top=candidate.streamSelfAndPassengers().mapToDouble(e->e.getBoundingBox().maxY-candidate.getY()).max().orElse(candidate.getHeight());
        return mc.world.getTopYInclusive()-top;
    }

    private double travelVerticalSpeed() { return verticalRejected ? Math.min(9, verticalSpeed.get()) : verticalSpeed.get(); }

    public double travelSpeed() {
        return switch (travelMode()) {
            case HASH -> hashSpeed.get();
            case EXTENDED -> experimentalSpeed.get();
            case SUBSTEPS -> extendedSubsteps.get() && travel.rejected(BoatPhaseTravel.Mode.EXTENDED)
                ? BoatPhaseAirSteps.MAX_SPEED : substepSpeed.get();
            case DIRECT -> horizontalSpeed.get();
            case LIMITED -> Math.min(horizontalSpeed.get(), horizontalLimit.get());
        };
    }

    private BoatPhaseTravel.Mode travelMode() {
        return travel.select(hashTravel.get(), hashReady(), airSubsteps.get(), fastAir.get(), extendedSubsteps.get());
    }

    private boolean speedFallback() {
        return travelMode() == BoatPhaseTravel.Mode.LIMITED && (airSubsteps.get() || fastAir.get() || hashTravel.get())
            || travelMode() == BoatPhaseTravel.Mode.SUBSTEPS && extendedSubsteps.get() && travel.rejected(BoatPhaseTravel.Mode.EXTENDED);
    }

    private boolean hashReady() {
        return hashTravel.get() && !travel.rejected(BoatPhaseTravel.Mode.HASH) && correctionHash != null
            && world == hashWorld && connection == hashConnection;
    }

    private Vec3d hashStep(AbstractBoatEntity candidate, Vec3d delta) {
        plannedHashTick = -1;
        if (Math.abs(delta.y) > BoatPhaseVertical.STEP) return delta;
        if (!hashReady() || delta.horizontalLength() <= horizontalLimit.get()) return delta;

        candidate.setYaw(MathHelper.wrapDegrees(candidate.getYaw()));
        candidate.setPitch(MathHelper.wrapDegrees(candidate.getPitch()));
        Vec3d to = candidate.getEntityPos().add(delta);
        double y = BoatPhaseHash.altitude(to.x,to.y,to.z,candidate.getYaw(),candidate.getPitch(),correctionHash);
        Vec3d adjusted = new Vec3d(delta.x,y-candidate.getY(),delta.z);
        BoatShot shot = BoatShot.active();
        if (!Double.isFinite(y) || !clearTravel(candidate,adjusted) || shot != null && !shot.safeTravel(candidate,adjusted)) return Vec3d.ZERO;
        plannedHashTick = motion.tick();
        return adjusted;
    }

    public boolean clearTravel(AbstractBoatEntity candidate, Vec3d delta) {
        if (!travelReady(candidate) || !Double.isFinite(delta.lengthSquared()) || delta.length() > Math.hypot(BoatPhaseVertical.MAX_SPEED, BoatPhaseAirSteps.TRIAL_SPEED)) return false;
        return clearRoute(candidate,delta);
    }

    public boolean clearRoute(AbstractBoatEntity candidate, Vec3d delta) {
        if (!travelReady(candidate) || !Double.isFinite(delta.lengthSquared()) || delta.length()>192) return false;
        for (Entity entity : candidate.streamSelfAndPassengers().toList()) {
            Box path = entity.getBoundingBox().stretch(delta).contract(1e-7);
            if (path.minY < world.getBottomY()+2 || path.maxY > world.getTopYInclusive()+1 || !loaded(path)
                || !world.getWorldBorder().contains(path) || !world.isSpaceEmpty(entity, path) || containsFluid(path)) return false;
        }
        return true;
    }

    public boolean shotPathClear(AbstractBoatEntity candidate, Vec3d delta) {
        if (!shotReady(candidate) || !Double.isFinite(delta.y) || delta.x != 0 || delta.z != 0
            || Math.abs(delta.y) > BoatShotCycle.MAX_BURST + 1e-7) return false;
        for (Entity entity : candidate.streamSelfAndPassengers().toList()) {
            Box path = entity.getBoundingBox().stretch(delta).contract(1e-7);
            if (path.minY < world.getBottomY() + 2 || path.maxY > world.getTopYInclusive() + 1
                || !loaded(path) || !world.getWorldBorder().contains(path)
                || !world.isSpaceEmpty(entity, path) || containsFluid(path)) return false;
        }
        return true;
    }

    public void afterMove(AbstractBoatEntity candidate) {
        if (!owns(candidate)) return;
        boolean ground = candidate.getVelocity().y <= 0 && supported(candidate);
        candidate.setOnGround(ground);
        candidate.groundCollision = ground;
        if (!plannedThroughBlocks && !ground) candidate.verticalCollision = false;
    }

    private boolean loaded(Box box) {
        for (int x = ((int) Math.floor(box.minX)) >> 4; x <= ((int) Math.floor(box.maxX)) >> 4; x++) {
            for (int z = ((int) Math.floor(box.minZ)) >> 4; z <= ((int) Math.floor(box.maxZ)) >> 4; z++) {
                if (!world.getChunkManager().isChunkLoaded(x, z)) return false;
            }
        }
        return true;
    }

    private boolean containsLava(Box box) {
        for (BlockPos pos : BlockPos.iterate((int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (world.getFluidState(pos).isIn(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private boolean containsFluid(Box box) {
        for (BlockPos pos : BlockPos.iterate((int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (!world.getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    private boolean containsWater(Box box) {
        for(BlockPos pos:BlockPos.iterate(BlockPos.ofFloored(box.minX,box.minY,box.minZ),BlockPos.ofFloored(box.maxX,box.maxY,box.maxZ)))
            if(world.getFluidState(pos).isIn(FluidTags.WATER))return true;
        return false;
    }

    public Vec3d waterDeparture(AbstractBoatEntity candidate,double ascent) {
        if(candidate==null||world==null||!Double.isFinite(ascent)||ascent<=0||!containsWater(candidate.getBoundingBox()))return null;
        Vec3d step=new Vec3d(0,Math.min(.5,ascent),0);
        return clearWaterExit(candidate,step)?step:Vec3d.ZERO;
    }

    private boolean clearWaterExit(AbstractBoatEntity candidate,Vec3d step) {
        if(!travelReady(candidate)||!Double.isFinite(step.lengthSquared())||step.y<=0||step.y>.5||step.horizontalLengthSquared()!=0)return false;
        for(Entity entity:candidate.streamSelfAndPassengers().toList()) {
            Box path=entity.getBoundingBox().stretch(step).contract(1e-7);
            if(path.minY<world.getBottomY()+2||path.maxY>world.getTopYInclusive()+1||!loaded(path)
                ||!world.getWorldBorder().contains(path)||!world.isSpaceEmpty(entity,path))return false;
            for(BlockPos pos:BlockPos.iterate(BlockPos.ofFloored(path.minX,path.minY,path.minZ),BlockPos.ofFloored(path.maxX,path.maxY,path.maxZ))) {
                var fluid=world.getFluidState(pos);
                if(!fluid.isEmpty()&&!fluid.isIn(FluidTags.WATER))return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player != null && !eventGate.tick(mc.player, mc.player.age)) return;
        ClientConnection currentConnection = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getConnection();
        if (travelConnection != currentConnection) { travel.retry(); verticalRejected = false; remount.reset(); travelConnection = currentConnection; }
        if (travelWorld != mc.world) { remount.reset(); travelWorld=mc.world; }
        if (hashWorld != mc.world || mc.getNetworkHandler() == null || hashConnection != mc.getNetworkHandler().getConnection()) {
            correctionHash = null; hashWorld = null; hashConnection = null;
        }
        if (boat != null && sameSession()) {
            if (Input.isPressed(mc.options.sneakKey)) lastSneakTick = motion.tick();
            if (supported(boat)) lastGroundedAge = mc.player.age;
            drainObservations();
        }
        AbstractBoatEntity current = mc.player != null && mc.player.getRootVehicle() instanceof AbstractBoatEntity b ? b : null;
        boolean controlling = current != null && current.getControllingPassenger() == mc.player;
        if (!sameSession() || boat != current || controlling != driver || (mc.player != null && !mc.player.isAlive())) {
            detach();
            if (current != null && mc.player.isAlive()) attach(current, controlling);
        }
        if (boat == null) { status = "board a boat"; return; }
        motion.nextTick();
        meter.tick(motion.tick(), System.nanoTime());
        drainObservations();
        String modeDescription = modeDescription();
        if (!modeDescription.equals(lastModeDescription)) {
            record("SPEED-MODE %s hashRequested=%b substeps=%b direct=%b", modeDescription, hashTravel.get(), airSubsteps.get(), fastAir.get());
            lastModeDescription = modeDescription;
        }
        if (driver) {
            if (pauseOthers.get()) pauseConflicts();
            boolean guest = boat.getPassengerList().size() > 1;
            if (guest && !hadGuest && diveWithPassenger.get()) toggleDive();
            hadGuest = guest;
            if (motion.holdTicks() > 0) status = "correction hold " + motion.holdTicks();
        } else status = "passenger - observing";
        if (motion.tick() % 20 == 0) {
            record("STATE role=%s boat=%d local=%s wire=%s passengers=%s inside=%b health=%.1f tx=%d corrections=%d status=%s",
                driver ? "driver" : "passenger", boat.getId(), boat.getEntityPos(), lastWire, boat.getPassengerList().stream().map(Entity::getId).toList(),
                !world.isSpaceEmpty(boat, boat.getBoundingBox()), mc.player.getHealth() + mc.player.getAbsorptionAmount(), sentCount, corrections, status);
            long now = System.nanoTime();
            record("SPEED sentBpt=%.3f sentBps=%.2f echoBpt=%.3f echoBps=%.2f packetsLastTick=%d mode=%s elapsedMs=%d",
                meter.sentBpt(), meter.sentBps(), meter.echoBpt(now), meter.echoBps(now), meter.packetsLastTick(), modeDescription,
                (now - rideStartedNanos) / 1_000_000L);
            if (log != null) log.flush();
        }
    }

    private void attach(AbstractBoatEntity current, boolean controlling) {
        boat = current;
        player = mc.player;
        world = mc.world;
        network = mc.getNetworkHandler();
        connection = network.getConnection();
        driver = controlling;
        lastWire = boat.getEntityPos();
        motion.reset();
        adaptiveBlocked = false;
        lastAdaptiveTick = lastSneakTick = -100;
        travel.newRide();
        lastFastVerticalTick = -100;
        meter.reset();
        lastModeDescription = "";
        rideStartedNanos = System.nanoTime();
        sentSneak = false;
        plannedHashTick = -1;
        plannedClear = false;
        sentCount = corrections = 0;
        entryHeight = boat.getY();
        hadGuest = false;
        openLog();
        boolean retryToken = remount.board(boat.getId(), player.age, driver, remountRetry.get() || landedRetry.get());
        record("RETRY-CHECK token=%b driver=%b substepRejected=%b reason=%s", retryToken, driver,
            travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS), boardingRetryReason);
        if (retryToken && remountRetry.get() && (travel.flightRejected() || verticalRejected)) {
            retryConfiguredFlight();
            record("REMOUNT-RETRY horizontal=%.2f vertical=%.2f", travelSpeed(), travelVerticalSpeed());
            info("Reboarded: retrying %.2f horizontal / %.2f vertical b/t.", travelSpeed(), travelVerticalSpeed());
        } else if (retryToken && !remountRetry.get() && airSubsteps.get() && travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS)) {
            travel.retry(BoatPhaseTravel.Mode.SUBSTEPS);

            travel.reject(BoatPhaseTravel.Mode.EXTENDED);
            substepSpeed.set(landedRetrySpeed.get());
            record("LANDED-RETRY speed=%.2f",substepSpeed.get());
            info("Landed remount: retrying air substeps at %.2f b/t.",substepSpeed.get());
        }
        record("MOUNT boat=%d type=%s role=%s position=%s passengers=%s", boat.getId(), boat.getType(), driver ? "driver" : "passenger",
            boat.getEntityPos(), boat.getPassengerList().stream().map(Entity::getId).toList());
        if (driver && pauseOthers.get()) pauseConflicts();
        info(driver ? "Boat ready. WASD steers, jump rises, sprint descends; sneak dismounts." : "Passenger seat: observing the driver's boat; no movement controls are sent.");
    }

    private void detach() {
        if (boat != null && driver) {
            boolean deliberate = sameSession() && player.isAlive() && player.getRootVehicle()!=boat && recentSneak();
            if (sameSession()) remount.intent(boat.getId(), player.age,
                remountRetry.get() || supported(boat) || player.age - lastGroundedAge <= 5, deliberate,
                retryableFallback(), remountRetry.get() || landedRetry.get());
        }
        epoch++;
        connection = null;
        record("END tx=%d corrections=%d recentSneak=%b inputSneak=%b stillMounted=%b adaptiveFallback=%b", sentCount, corrections,
            recentSneak(), sentSneak, player != null && boat != null && player.getRootVehicle() == boat, adaptiveBlocked);
        if (log != null) { log.close(); log = null; }
        observations.clear();
        observationCount.set(0);
        lastPlayerCorrection = lastVehicleCorrection = lastInputPacket = lastObservationPacket = null;
        plannedMode = BoatPhaseTravel.Mode.LIMITED;
        plannedShotMovement = false;
        lastFastVerticalTick = -100;
        plannedHashTick = -1;
        meter.reset();
        if (boat != null && driver && !boat.isRemoved()) boat.setVelocity(Vec3d.ZERO);
        for (Module module : paused) if (!module.isActive()) module.toggle();
        paused.clear();
        boat = null;
        player = null;
        world = null;
        network = null;
        driver = correcting = hadGuest = false;
        cruising = plannedThroughBlocks = false;
        airborneTicks = 0;
        lastAdaptiveTick = -100;
        lastGroundedAge = -1000;
        lastStep = Vec3d.ZERO;
        lastWire = plannedFrom = null;
        plannedTick = -1;
        motion.reset();
    }

    private void pauseConflicts() {
        for (String name : CONFLICTS) {
            Module module = Modules.get().get(name);
            if (module != null && module != this && module.isActive()) {
                module.toggle();
                if (!paused.contains(module)) paused.add(module);
                record("PAUSE module=%s", module.name);
            }
        }
    }

    private void toggleDive() {
        if (!isActive() || boat == null || !owns(boat)) { info("Board the driver's seat and enable Boat Phase first."); return; }
        if (motion.diveRemaining() > 0 || motion.riseRemaining() > 0) {
            motion.cancelTravel();
            info("Vertical travel canceled.");
        } else if (motion.holdTicks() == 0) {
            cruising = false;
            travelName = "Quick dive";
            requestedDiveDistance = diveDistance.get();
            motion.dive(requestedDiveDistance);
            record("DIVE distance=%.2f start=%s", requestedDiveDistance, boat.getEntityPos());
            info("Diving %.0f blocks. Jump cancels.", requestedDiveDistance);
        }
    }

    private void toggleCruise() {
        if (!isActive() || boat == null || !owns(boat)) { info("Board the driver's seat first."); return; }
        cruising = !cruising;
        cruiseYaw = mc.player.getYaw();
        info(cruising ? "Cruise started. WASD cancels; jump and sprint control height." : "Cruise canceled.");
        record("CRUISE active=%b yaw=%.2f", cruising, cruiseYaw);
    }

    private void travelToHeight(double height, String name) {
        if (motion.holdTicks() > 0) return;
        double delta = height - boat.getY();
        if (Math.abs(delta) < 0.01) { info("Already at that height."); return; }
        if (Math.abs(delta) > BoatPhaseMotion.MAX_TRAVEL) { warning("That height is more than %.0f blocks away.", BoatPhaseMotion.MAX_TRAVEL); return; }
        cruising = false;
        requestedDiveDistance = Math.abs(delta);
        travelName = name;
        if (delta > 0) motion.rise(delta); else motion.dive(-delta);
        record("TRAVEL name=%s fromY=%.3f targetY=%.3f", name, boat.getY(), height);
        info("%s: %.1f blocks %s. Jump or sprint cancels a rise.", name, Math.abs(delta), delta > 0 ? "up" : "down");
    }

    private void returnHeight() {
        if (!isActive() || boat == null || !owns(boat)) { info("Board the driver's seat first."); return; }
        travelToHeight(entryHeight, "Entry height");
    }

    private void surface() {
        if (!isActive() || boat == null || !owns(boat)) { info("Board the driver's seat first."); return; }
        double max = Math.min(128, world.getTopYInclusive() - boat.getBoundingBox().maxY);
        for (int dy = 1; dy <= max; dy++) {
            Box target = boat.getBoundingBox().offset(0, dy, 0);
            if (!loaded(target)) break;
            if (!world.isSpaceEmpty(boat, target) || containsFluid(target)) continue;
            boolean clear = true;
            for (Entity passenger : boat.getPassengerList()) {
                Box passengerBox = passenger.getBoundingBox().offset(0, dy, 0);
                if (passengerBox.maxY > world.getTopYInclusive() + 1 || !world.isSpaceEmpty(passenger, passengerBox) || containsFluid(passengerBox)) { clear = false; break; }
            }
            if (clear) { travelToHeight(boat.getY() + dy, "Clear pocket"); return; }
        }
        info("No clear pocket with passenger headroom found within 128 blocks above.");
    }

    private void recover() {
        cruising = false;
        meter.reset();
        lastStep = Vec3d.ZERO;
        airborneTicks = 0;
        if (!adaptiveBlocked && !recentSneak() && motion.tick() - lastAdaptiveTick <= 10) {
            adaptiveBlocked = true;
            record("ADAPTIVE-FALLBACK lastAdaptiveTick=%d", lastAdaptiveTick);
            warning("Adaptive phase corrected; slow step for this ride. Board again or use Resume adaptive phase to retry.");
        }
        motion.corrected(correctionHold.get());
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();
        WButton dive = table.add(theme.button("Quick dive / cancel")).expandX().widget();
        dive.action = this::toggleDive;
        table.row();
        table.add(theme.button("Cruise / cancel")).expandX().widget().action = this::toggleCruise;
        table.row();
        table.add(theme.button("Rise to clear pocket")).expandX().widget().action = this::surface;
        table.row();
        table.add(theme.button("Return to entry height")).expandX().widget().action = this::returnHeight;
        table.row();
        table.add(theme.button("Resume adaptive phase")).expandX().widget().action = () -> {
            adaptiveBlocked = false;
            lastAdaptiveTick = -100;
            record("ADAPTIVE-RESUME horizontalLimit=%.3f", horizontalLimit.get());
            info("Adaptive fallback cleared. The adaptive-phase setting still controls whether it is enabled.");
        };
        table.row();
        table.add(theme.button("Retry fast air")).expandX().widget().action = () -> {
            travel.retry();
            verticalRejected = false;
            lastStep = Vec3d.ZERO;
            record("SPEED-RETRY mode=%s", modeDescription());
            info("Speed fallback cleared. %s", modeDescription());
        };
        return table;
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private void onSend(PacketEvent.Send event) {
        if (!event.isCancelled() && mc.isOnThread() && mc.player != null && mc.world != null
            && mc.getNetworkHandler() != null && event.connection == mc.getNetworkHandler().getConnection()
            && !mc.player.hasVehicle() && event.packet instanceof PlayerInteractEntityC2SPacket interaction
            && lastBoardInteraction != interaction) {
            lastBoardInteraction = interaction;
            boolean[] use = {false};
            interaction.handle(new PlayerInteractEntityC2SPacket.Handler() {
                @Override public void interact(Hand hand) { use[0] = true; }
                @Override public void interactAt(Hand hand, Vec3d position) { use[0] = true; }
                @Override public void attack() {}
            });
            if (use[0] && ((IPlayerInteractEntityC2SPacket)interaction).meteor$getEntity() instanceof AbstractBoatEntity candidate) {

                boolean grounded = supported(candidate, .35);
                boolean armed = remount.intent(candidate.getId(), mc.player.age, grounded || remountRetry.get(), true,
                    retryableFallback(), remountRetry.get() || landedRetry.get());
                boardingRetryReason = armed ? "deliberate boarding" : !landedRetry.get() && !remountRetry.get() ? "retry disabled"
                    : !retryableFallback() ? "no flight rejection; terrain limits are separate"
                    : "boat not landed";
            }
        }
        if (event.isCancelled() || !(event.packet instanceof VehicleMoveC2SPacket packet) || boat == null || !owns(boat) || event.connection != connection || correcting) return;
        if (packet == substepPacket) return;
        if (splitting) { event.cancel(); return; }
        if (motion.alreadySent()) {
            record("SUPPRESS extra vehicle movement");
            event.cancel();
            return;
        }
        if (plannedHashTick == motion.tick() && hashReady()) {
            Vec3d p = packet.position();
            if (BoatPhaseHash.hash(p.x,p.y,p.z,MathHelper.wrapDegrees(packet.yaw()),MathHelper.wrapDegrees(packet.pitch())) != correctionHash) {
                event.cancel();
                if (lastWire != null) boat.setPosition(lastWire);
                boat.setVelocity(Vec3d.ZERO);
                travel.reject(BoatPhaseTravel.Mode.HASH);
                record("HASH-STOP outgoing coordinates or rotation changed");
            }
            return;
        }
        if (lastWire != null) {
            Vec3d delta = packet.position().subtract(lastWire);
            boolean horizontalBatch = (plannedMode == BoatPhaseTravel.Mode.SUBSTEPS || plannedMode == BoatPhaseTravel.Mode.EXTENDED)
                && !plannedThroughBlocks;
            int horizontalCount = horizontalBatch ? BoatPhaseAirSteps.count(delta.horizontalLength(), plannedMode == BoatPhaseTravel.Mode.EXTENDED
                ? BoatPhaseAirSteps.TRIAL_PACKETS : BoatPhaseAirSteps.MAX_PACKETS) : 1;
            int verticalCount = plannedShotMovement ? 1 : BoatPhaseVertical.count(delta.y);
            int count = Math.max(horizontalCount, verticalCount);

            if (horizontalCount == 0 || verticalCount == 0 || count > 1 && (plannedTick != motion.tick()
                || plannedFrom == null || plannedFrom.distanceTo(lastWire) > 1e-4 || !plannedClear && !plannedThroughBlocks)) {
                event.cancel(); boat.setPosition(lastWire); boat.setVelocity(Vec3d.ZERO);
                record("STEPS-STOP unverified sweep or packet budget; oversized update suppressed");
                return;
            }
            if (count > 1) {
                event.cancel();
                splitting = true;
                substepOrigin = lastWire;
                try {
                    for (int i = 1; i <= count; i++) {
                        Vec3d end = substepOrigin.add(delta.multiply((double)i/count));
                        substepPacket = new VehicleMoveC2SPacket(end, packet.yaw(), packet.pitch(), packet.onGround());
                        network.sendPacket(substepPacket);
                        if (lastWire.distanceTo(end) > 1e-5) break;
                    }
                } finally { substepPacket = null; splitting = false; }
                boolean finished = motion.sent(point(substepOrigin), point(lastWire));
                lastStep = lastWire.subtract(substepOrigin);
                if (Math.abs(lastStep.y) > BoatPhaseVertical.STEP + 1e-6) {
                    lastFastVerticalTick = motion.tick();
                    travel.newRide();
                } else if (horizontalBatch) {
                    if (lastStep.horizontalLength() > horizontalLimit.get() + 1e-6) lastFastVerticalTick = -100;
                    travel.sent(lastStep.horizontalLength() > BoatPhaseAirSteps.MAX_SPEED + 1e-6
                        ? BoatPhaseTravel.Mode.EXTENDED : BoatPhaseTravel.Mode.SUBSTEPS,
                        motion.tick(), lastStep.horizontalLength(), horizontalLimit.get());
                }
                if (lastWire.distanceTo(packet.position()) > 1e-5) {
                    boat.setPosition(lastWire);
                    boat.setVelocity(Vec3d.ZERO);
                    record("SUBSTEP-INTERRUPTED position=%s", lastWire);
                }
                record("AIR-STEPS count=%d total=%s horizontal=%.3f", count, lastStep, lastStep.horizontalLength());
                if (finished) info("%s movement sent: %.1f blocks.", travelName, requestedDiveDistance);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private void onSent(PacketEvent.Sent event) {
        if (event.packet instanceof PlayerInputC2SPacket input && boat != null && sameSession() && event.connection == connection) {
            if (lastInputPacket == input) return;
            lastInputPacket = input;
            sentSneak = input.input().sneak();
            if (sentSneak) lastSneakTick = motion.tick();
            record("INPUT %s", input.input());
        }
        if (!(event.packet instanceof VehicleMoveC2SPacket packet) || boat == null || !owns(boat) || event.connection != connection) return;

        if (!eventGate.vehicle(packet)) return;
        if (packet == substepPacket && splitting) {
            meter.sent(motion.tick(), System.nanoTime(), lastWire.x, lastWire.z, packet.position().x, packet.position().y, packet.position().z);
            record("SUBSTEP-WIRE from=%s to=%s delta=%s", lastWire, packet.position(), packet.position().subtract(lastWire));
            lastWire = packet.position();
            sentCount++;
            return;
        }
        if (motion.alreadySent()) return;
        if (correcting) {
            motion.acknowledged();
            record("CORRECTION-ACK position=%s", packet.position());
            return;
        }
        Vec3d from = lastWire == null ? packet.position() : lastWire;
        boolean finished = motion.sent(point(from), point(packet.position()));
        lastWire = packet.position();
        lastStep = lastWire.subtract(from);
        meter.sent(motion.tick(), System.nanoTime(), from.x, from.z, lastWire.x, lastWire.y, lastWire.z);
        BoatPhaseTravel.Mode sentMode = plannedHashTick == motion.tick() ? BoatPhaseTravel.Mode.HASH
            : plannedTick == motion.tick() && !plannedThroughBlocks ? plannedMode : BoatPhaseTravel.Mode.LIMITED;
        if (plannedShotMovement) {
            travel.newRide();
            lastFastVerticalTick = -100;
        } else {
            travel.sent(sentMode, motion.tick(), lastStep.horizontalLength(), horizontalLimit.get());
            if (sentMode != BoatPhaseTravel.Mode.LIMITED && lastStep.horizontalLength() > horizontalLimit.get() + 1e-6) lastFastVerticalTick = -100;
        }
        if (plannedHashTick == motion.tick() && lastStep.horizontalLength() > horizontalLimit.get()) {
            record("HASH-WIRE horizontal=%.3f hash=%d",lastStep.horizontalLength(),correctionHash);
        }
        sentCount++;
        record("WIRE from=%s to=%s delta=%s local=%s plannedFrom=%s plannedTick=%d inside=%b passengers=%d ground=%b airTicks=%d horizontalLimit=%.3f fallback=%b status=%s",
            from, lastWire, lastWire.subtract(from), boat.getEntityPos(), plannedFrom, plannedTick,
            !world.isSpaceEmpty(boat, boat.getBoundingBox()), boat.getPassengerList().size(), packet.onGround(), airborneTicks, horizontalLimit.get(), adaptiveBlocked, status);
        if (finished) info("%s movement sent: %.1f blocks.", travelName, requestedDiveDistance);
    }

    public void beforeVehicleCorrection(ClientPlayNetworkHandler handler, VehicleMoveS2CPacket packet) {
        if (handler != network || boat == null || !owns(boat)) return;
        if (lastVehicleCorrection == packet) return;
        lastVehicleCorrection = packet;
        rejectFastAir();
        correcting = true;
        corrections++;
        record("VEHICLE-CORRECTION server=%s local=%s lastWire=%s error=%.3f", packet.position(), boat.getEntityPos(), lastWire,
            packet.position().distanceTo(boat.getEntityPos()));
        recover();
    }

    public void afterVehicleCorrection(ClientPlayNetworkHandler handler, VehicleMoveS2CPacket packet) {
        if (handler != network || !correcting) return;
        lastWire = packet.position();
        if (boat != null) boat.setVelocity(Vec3d.ZERO);
        correcting = false;
        status = "server correction";
        if (corrections <= 3 || corrections % 10 == 0) warning("Server corrected the boat (#%d). Dive canceled; pausing %d ticks.", corrections, correctionHold.get());
    }

    @EventHandler
    private void onPlayerCorrectionBefore(PlayerPositionLookEvent.Before event) {
        if (lastPlayerCorrection == event.packet) return;
        lastPlayerCorrection = event.packet;
        if (mc.player != null && mc.world != null && mc.getNetworkHandler() != null) {
            EntityPosition p = EntityPosition.apply(EntityPosition.fromEntity(mc.player), event.packet.change(), event.packet.relatives());
            correctionHash = BoatPhaseHash.hash(p.position().x,p.position().y,p.position().z,p.yaw(),p.pitch());
            hashWorld = mc.world; hashConnection = mc.getNetworkHandler().getConnection();
            record("HASH-REFERENCE hash=%d recentSneak=%b", correctionHash, recentSneak());
        }
        if (boat == null || !driver || !sameSession()) return;
        if (!recentSneak()) rejectFastAir();
        correcting = true;
        corrections++;
        recover();
        record("PLAYER-CORRECTION packet=%s recentSneak=%b inputSneak=%b", event.packet, recentSneak(), sentSneak);
    }

    @EventHandler
    private void onPlayerCorrectionAfter(PlayerPositionLookEvent.After event) {
        if (!correcting) return;
        correcting = false;
        if (boat != null) { lastWire = boat.getEntityPos(); boat.setVelocity(Vec3d.ZERO); }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        long observedEpoch = epoch;
        ClientConnection owner = connection;
        if (owner == null || event.connection != owner) return;
        if (!(event.packet instanceof EntityPassengersSetS2CPacket || event.packet instanceof EntityPositionSyncS2CPacket
            || event.packet instanceof EntityPositionS2CPacket || event.packet instanceof EntityS2CPacket)) return;
        if (observationCount.incrementAndGet() > 256) { observationCount.decrementAndGet(); return; }
        observations.add(new Observation(observedEpoch, owner, event.packet));
    }

    private void drainObservations() {
        Observation entry;
        while ((entry = observations.poll()) != null) {
            observationCount.updateAndGet(n -> Math.max(0, n - 1));
            if (entry.epoch != epoch || entry.connection != connection || boat == null || !sameSession()) continue;
            Packet<?> packet = entry.packet;
            if (lastObservationPacket == packet) continue;
            lastObservationPacket = packet;
            if (packet instanceof EntityPassengersSetS2CPacket p && p.getEntityId() == boat.getId()) {
                record("SERVER-PASSENGERS ids=%s", java.util.Arrays.toString(p.getPassengerIds()));
            } else if (packet instanceof EntityPositionSyncS2CPacket p && p.id() == boat.getId()) {
                record("SERVER-BOAT-SYNC values=%s", p.values());
                Vec3d pos = p.values().position();
                meter.echo(motion.tick(), System.nanoTime(), pos.x, pos.y, pos.z);
            } else if (packet instanceof EntityPositionS2CPacket p && p.entityId() == boat.getId()) {
                record("SERVER-BOAT-POSITION values=%s relatives=%s", p.change(), p.relatives());
            } else if (packet instanceof EntityS2CPacket p && p.getEntity(world) == boat && p.isPositionChanged()) {
                record("SERVER-BOAT-DELTA x=%d y=%d z=%d local=%s", p.getDeltaX(), p.getDeltaY(), p.getDeltaZ(), boat.getEntityPos());
            }
        }
    }

    private void openLog() {
        if (!debugFile.get()) return;
        try {
            Path folder = mc.runDirectory.toPath().resolve("boat-phase");
            Files.createDirectories(folder);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Path path = folder.resolve("boat-" + time + ".log");
            log = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
            record("BOAT-PHASE v18 server=%s role=%s", mc.getCurrentServerEntry() == null ? "local" : mc.getCurrentServerEntry().address, driver ? "driver" : "passenger");
            for (SettingGroup group : settings) for (Setting<?> setting : group) record("cfg %s = %s", setting.name, setting.get());
            info("Boat log: %s", path);
        } catch (IOException e) {
            QuietteeUtils.LOG.warn("Cannot open Boat Phase log", e);
        }
    }

    private void record(String format, Object... args) {
        if (log != null) log.printf(Locale.ROOT, "[%d] %s%n", motion.tick(), String.format(Locale.ROOT, format, args));
    }

    private static BoatPhaseMotion.Point point(Vec3d v) { return new BoatPhaseMotion.Point(v.x, v.y, v.z); }

    private boolean recentSneak() {
        return motion.tick() - lastSneakTick <= 10 || (sameSession() && mc.player != null && Input.isPressed(mc.options.sneakKey));
    }

    private void rejectFastAir() {
        if (motion.tick() - lastFastVerticalTick >= 0 && motion.tick() - lastFastVerticalTick <= 20) {
            verticalRejected = true;
            lastFastVerticalTick = -100;
            travel.newRide();
            record("VERTICAL-REJECTED next=%.2f", travelVerticalSpeed());
            warning("Fast vertical movement corrected; using at most 9 vertical b/t until reboarding or Retry fast air.");
            return;
        }
        BoatPhaseTravel.Mode rejected = travel.corrected(motion.tick());
        if (rejected == BoatPhaseTravel.Mode.LIMITED) return;
        record("SPEED-REJECTED mode=%s next=%s", rejected, modeDescription());
        if (rejected == BoatPhaseTravel.Mode.HASH)
            warning("Hash experiment rejected. Air substeps remain available; movement resumes after recovery / boarding.");
        else if (rejected == BoatPhaseTravel.Mode.EXTENDED)
            warning("Experimental speed corrected; returning to 3.8 b/t. Reboard, change experimental-speed or use Retry fast air to retry.");
        else warning("%s corrected: using the limited speed. Retry fast air or re-enable Boat Phase to retry.", rejected);
    }

    private long rideStartedNanos;

    private boolean retryableFallback() {
        return remountRetry.get() ? travel.flightRejected() || verticalRejected : travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS);
    }

    private void retryConfiguredFlight() {
        travel.retryFlight();
        verticalRejected = false;
        lastFastVerticalTick = -100;
        lastStep = Vec3d.ZERO;
        motion.corrected(4);
    }

    private void retryExtension() {
        if (travel != null) travel.retry(BoatPhaseTravel.Mode.EXTENDED);
    }

    public void toggleSpeedometer() {
        speedometer.set(!speedometer.get());
        info("Boat speedometer %s.",speedometer.get()?"on":"off");
    }

    private String modeDescription() {
        String mode = switch (travelMode()) {
            case EXTENDED -> "Adjustable substeps";
            case SUBSTEPS -> "Air substeps";
            case DIRECT -> "Single update";
            case HASH -> "Hash experiment";
            case LIMITED -> hashTravel.get() && !travel.rejected(BoatPhaseTravel.Mode.HASH) && !hashReady()
                ? "Waiting for hash reference" : speedFallback() ? "Speed fallback - Retry fast air" : "Limited";
        };
        return String.format(Locale.ROOT, "%s | cap %.2f b/t", mode, travelSpeed());
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!speedometer.get() || boat == null || !owns(boat) || mc.options.hudHidden) return;
        long now = System.nanoTime();
        double echo = meter.echoBpt(now);
        String[] lines = {
            String.format(Locale.ROOT, "BOAT  Sent %.2f b/t | %.1f b/s", meter.sentBpt(), meter.sentBps()),
            Double.isFinite(echo) ? String.format(Locale.ROOT, "Server echo %.2f b/t | %.1f b/s", echo, meter.echoBps(now)) : "Server echo -- waiting for matching positions",
            modeDescription(),
            status + " | " + meter.packetsLastTick() + " updates/tick"
        };
        TextRenderer text = TextRenderer.get();
        if (text.isBuilding()) return;
        text.begin(meterScale.get());
        double width = 0, lineHeight = text.getHeight() + 4;
        for (String line : lines) width = Math.max(width, text.getWidth(line, true));
        double x = Math.max(0, event.screenWidth-width-16) * meterX.get()/100.0;
        double y = Math.max(0, event.screenHeight-lines.length*lineHeight-16) * meterY.get()/100.0;
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x, y, width+16, lines.length*lineHeight+16, new Color(10,14,20,210));
        Renderer2D.COLOR.render();
        for (int i=0; i<lines.length; i++) text.render(lines[i], x+8, y+8+i*lineHeight,
            i == 1 ? new Color(110,235,220) : i == 2 && speedFallback() ? new Color(255,195,85) : new Color(255,255,255), true);
        text.end();
    }

    @Override
    public String getInfoString() {
        return boat == null ? status : String.format(Locale.ROOT, "%s | %.2f b/t sent", status, meter.sentBpt());
    }
}
