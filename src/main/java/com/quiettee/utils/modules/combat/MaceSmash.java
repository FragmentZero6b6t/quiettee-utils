package com.quiettee.utils.modules.combat;

import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.events.PlayerPositionLookEvent;
import com.quiettee.utils.mixin.PlayerMoveC2SPacketAccessor;
import com.quiettee.utils.util.MaceMovementMath;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerMoveC2SPacket;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.NoFall;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MaceSmash extends Module {
    private static final int TAG = 1337;
    private static final double EYE_GLIDING = 0.4;
    private static final double EYE_STANDING = 1.62;
    private static final int TRACKED_FLAGS_ID = 0;
    private static final int TRACKED_POSE_ID = 6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgTrack = settings.createGroup("Tracking");

    private final SettingGroup sgEngine = settings.createGroup("Engine");

    private final SettingGroup sgOccam = settings.createGroup("Occam engine");

    private final SettingGroup sgHover = settings.createGroup("Hover engine");

    private final SettingGroup sgSmash = settings.createGroup("Smash");

    private final SettingGroup sgWeb = settings.createGroup("Webbing");

    public enum Engine { Occam, Chain, Hover }

    public enum Power { Off, Haymaker, Guillotine }
    public enum ChainMode { SameTick, Alternate }
    public enum Unglide { Strike, Carry }
    public enum RiseTo { RealPosition, ReachTop }
    public enum OccamRise { ReachTop, Bounce, RealPosition }
    public enum VerticalFollow { Off, Window, Hold }
    public enum WebWhen { Always, Airborne, Gliding }

    public enum WebCells { Feet, FeetAndHead, Cube }

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("What to smash.")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER, EntityType.SLIME, EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.IRON_GOLEM)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Max reach from the spoofed eye.")
        .defaultValue(3)
        .min(1)
        .sliderRange(2, 6)
        .build()
    );

    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
        .name("fov")
        .description("View cone for picking targets. 360 is everywhere.")
        .defaultValue(360)
        .min(10)
        .sliderRange(30, 360)
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to pick a target.")
        .defaultValue(SortPriority.LowestDistance)
        .build()
    );

    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Also strike targets you cannot see.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> faceTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("face-target")
        .description("Point the server side look at the victim.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lockTarget = sgTrack.add(new BoolSetting.Builder()
        .name("lock-target")
        .description("Stay on a victim until it dies or leaves.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> follow = sgTrack.add(new BoolSetting.Builder()
        .name("follow")
        .description("Fly your real body after the victim.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> followRange = sgTrack.add(new DoubleSetting.Builder()
        .name("follow-range")
        .description("Max sideways distance to pick a victim.")
        .defaultValue(24)
        .min(2)
        .sliderRange(4, 64)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> followRadius = sgTrack.add(new DoubleSetting.Builder()
        .name("follow-radius")
        .description("Only move beyond this distance from the victim.")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 5)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> followSpeed = sgTrack.add(new DoubleSetting.Builder()
        .name("follow-speed")
        .description("Max horizontal follow speed.")
        .defaultValue(3.0)
        .min(0.1)
        .sliderRange(0.5, 3)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> followLead = sgTrack.add(new DoubleSetting.Builder()
        .name("follow-lead")
        .description("Ticks to aim ahead of the victim.")
        .defaultValue(2)
        .min(0)
        .sliderMax(10)
        .visible(follow::get)
        .build()
    );

    private final Setting<VerticalFollow> followVertical = sgTrack.add(new EnumSetting.Builder<VerticalFollow>()
        .name("follow-vertical")
        .description("How the follow handles height.")
        .defaultValue(VerticalFollow.Hold)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> followHeight = sgTrack.add(new DoubleSetting.Builder()
        .name("follow-height")
        .description("Feet height above the victim's head.")
        .defaultValue(10)
        .min(1)
        .sliderRange(2, 40)
        .visible(() -> follow.get() && followVertical.get() == VerticalFollow.Hold)
        .build()
    );

    private final Setting<Double> followVerticalSpeed = sgTrack.add(new DoubleSetting.Builder()
        .name("follow-vertical-speed")
        .description("Max vertical follow speed.")
        .defaultValue(1.5)
        .min(0.1)
        .sliderRange(0.25, 3)
        .visible(() -> follow.get() && followVertical.get() != VerticalFollow.Off)
        .build()
    );

    private final Setting<Boolean> cameraTrack = sgTrack.add(new BoolSetting.Builder()
        .name("camera-track")
        .description("Turn your camera onto the victim.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Engine> engine = sgEngine.add(new EnumSetting.Builder<Engine>()
        .name("engine")
        .description("Occam, Chain or Hover engine.")
        .defaultValue(Engine.Occam)
        .build()
    );

    private final Setting<ChainMode> chainMode = sgEngine.add(new EnumSetting.Builder<ChainMode>()
        .name("chain-mode")
        .description("Same tick, or alternate ticks.")
        .defaultValue(ChainMode.SameTick)
        .visible(() -> engine.get() == Engine.Chain)
        .build()
    );

    private final Setting<Double> hop = sgEngine.add(new DoubleSetting.Builder()
        .name("hop")
        .description("Dive per smash in blocks.")
        .defaultValue(3)
        .min(1.6)
        .sliderRange(1.6, 4.5)
        .visible(() -> engine.get() == Engine.Chain)
        .build()
    );

    private final Setting<Double> divePacket = sgEngine.add(new DoubleSetting.Builder()
        .name("dive-packet")
        .description("Biggest single move packet during a dive.")
        .defaultValue(1.5)
        .min(0.75)
        .sliderRange(0.75, 3)
        .visible(() -> engine.get() == Engine.Chain)
        .build()
    );

    private final Setting<Integer> maxRate = sgEngine.add(new IntSetting.Builder()
        .name("max-rate")
        .description("Max smashes per second.")
        .defaultValue(10)
        .min(1)
        .sliderRange(2, 20)
        .visible(() -> engine.get() == Engine.Chain)
        .build()
    );

    private final Setting<Double> escalate = sgEngine.add(new DoubleSetting.Builder()
        .name("escalate")
        .description("Extra dive per rung of the ladder.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 3)
        .visible(() -> engine.get() == Engine.Chain)
        .build()
    );

    private final Setting<Boolean> phaseLock = sgEngine.add(new BoolSetting.Builder()
        .name("phase-lock")
        .description("Sync the ladder to the victim's i frames.")
        .defaultValue(true)
        .visible(() -> engine.get() == Engine.Chain)
        .build()
    );

    private final Setting<Double> maxDive = sgEngine.add(new DoubleSetting.Builder()
        .name("max-dive")
        .description("Max height of the ghost above the target.")
        .defaultValue(10)
        .min(1)
        .sliderRange(3, 50)
        .build()
    );

    private final Setting<Double> step = sgEngine.add(new DoubleSetting.Builder()
        .name("step")
        .description("Spoofed descent per tick.")
        .defaultValue(1.5)
        .min(0.25)
        .sliderRange(0.5, 4)
        .build()
    );

    private final Setting<Double> riseStep = sgEngine.add(new DoubleSetting.Builder()
        .name("rise-step")
        .description("Spoofed ascent per tick.")
        .defaultValue(1.5)
        .min(0.25)
        .sliderRange(0.5, 4)
        .build()
    );

    private final Setting<Double> occamDive = sgOccam.add(new DoubleSetting.Builder()
        .name("dive-step")
        .description("Biggest downward move in one tick.")
        .defaultValue(1.75)
        .min(0.6)
        .sliderRange(0.6, 3)
        .visible(() -> engine.get() == Engine.Occam)
        .build()
    );

    private final Setting<Double> occamRise = sgOccam.add(new DoubleSetting.Builder()
        .name("rise-step")
        .description("Biggest upward move in one tick.")
        .defaultValue(1.5)
        .min(0.25)
        .sliderRange(0.5, 4)
        .visible(() -> engine.get() == Engine.Occam)
        .build()
    );

    private final Setting<OccamRise> occamRiseTo = sgOccam.add(new EnumSetting.Builder<OccamRise>()
        .name("rise-to")
        .description("Where to climb back to after a strike.")
        .defaultValue(OccamRise.ReachTop)
        .visible(() -> engine.get() == Engine.Occam)
        .build()
    );

    private final Setting<Integer> stallKick = sgOccam.add(new IntSetting.Builder()
        .name("stall-kick")
        .description("Reset the engine after this many idle ticks.")
        .defaultValue(40)
        .min(0)
        .sliderMax(200)
        .visible(() -> engine.get() == Engine.Occam)
        .build()
    );

    private final Setting<Integer> occamHold = sgOccam.add(new IntSetting.Builder()
        .name("setback-hold")
        .description("Ticks to wait after a setback.")
        .defaultValue(10)
        .min(0)
        .sliderMax(60)
        .visible(() -> engine.get() == Engine.Occam)
        .build()
    );

    private final Setting<Double> occamClearance = sgOccam.add(new DoubleSetting.Builder()
        .name("head-clearance")
        .description("Dive bottom height above the victim's feet.")
        .defaultValue(0.5)
        .min(-4)
        .sliderRange(-4, 3)
        .visible(() -> engine.get() == Engine.Occam)
        .build()
    );

    private final Setting<Power> power = sgOccam.add(new EnumSetting.Builder<Power>()
        .name("power")
        .description("Off, Haymaker or Guillotine.")
        .defaultValue(Power.Off)
        .visible(() -> engine.get() == Engine.Occam)
        .build()
    );

    private final Setting<Double> minCharge = sgOccam.add(new DoubleSetting.Builder()
        .name("min-charge")
        .description("Minimum attack charge before a smash.")
        .defaultValue(1.0)
        .min(0.5)
        .max(1.0)
        .sliderRange(0.85, 1.0)
        .visible(() -> engine.get() == Engine.Occam && power.get() != Power.Off)
        .build()
    );

    private final Setting<Boolean> windBurst = sgSmash.add(new BoolSetting.Builder()
        .name("wind-burst")
        .description("Farm Wind Burst instead of damage.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> windBurstCadence = sgSmash.add(new BoolSetting.Builder()
        .name("wind-burst-cadence")
        .description("Ignore min charge with wind burst.")
        .defaultValue(true)
        .visible(windBurst::get)
        .build()
    );

    private final Setting<Boolean> windBurstWarn = sgSmash.add(new BoolSetting.Builder()
        .name("wind-burst-warn")
        .description("Warn about wind burst side effects.")
        .defaultValue(true)
        .visible(windBurst::get)
        .build()
    );

    private final Setting<Double> powerDive = sgOccam.add(new DoubleSetting.Builder()
        .name("dive-power")
        .description("Guillotine descent speed.")
        .defaultValue(1.5)
        .min(0.5)
        .max(1.5)
        .sliderRange(0.75, 1.5)
        .visible(() -> engine.get() == Engine.Occam && power.get() == Power.Guillotine)
        .build()
    );

    private final Setting<Boolean> stealth = sgOccam.add(new BoolSetting.Builder()
        .name("stealth")
        .description("Un glide during Guillotine dives.")
        .defaultValue(false)
        .visible(() -> engine.get() == Engine.Occam && power.get() == Power.Guillotine)
        .build()
    );

    private final Setting<Double> bankFd = sgOccam.add(new DoubleSetting.Builder()
        .name("bank-fd")
        .description("Guillotine fall distance to bank.")
        .defaultValue(8.0)
        .min(2.0)
        .max(25.0)
        .sliderRange(3.0, 18.0)
        .visible(() -> engine.get() == Engine.Occam && power.get() == Power.Guillotine)
        .build()
    );

    private final Setting<RiseTo> riseTo = sgHover.add(new EnumSetting.Builder<RiseTo>()
        .name("rise-to")
        .description("Real position or reach top.")
        .defaultValue(RiseTo.RealPosition)
        .visible(() -> engine.get() == Engine.Hover)
        .build()
    );

    private final Setting<Unglide> unglide = sgHover.add(new EnumSetting.Builder<Unglide>()
        .name("unglide")
        .description("Toggle gliding per strike, or carry it off.")
        .defaultValue(Unglide.Strike)
        .visible(() -> engine.get() == Engine.Hover)
        .build()
    );

    private final Setting<Boolean> fullChargeOnly = sgHover.add(new BoolSetting.Builder()
        .name("full-charge-only")
        .description("One swing per cycle at full charge.")
        .defaultValue(false)
        .visible(() -> engine.get() == Engine.Hover)
        .build()
    );

    private final Setting<Integer> cycleDelay = sgHover.add(new IntSetting.Builder()
        .name("cycle-delay")
        .description("Extra ticks between cycles.")
        .defaultValue(0)
        .min(0)
        .sliderMax(40)
        .visible(() -> engine.get() == Engine.Hover)
        .build()
    );

    private final Setting<Boolean> autoSwap = sgSmash.add(new BoolSetting.Builder()
        .name("auto-swap")
        .description("Silently swap to a mace for the attack.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgSmash.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swap back in the same tick.")
        .defaultValue(true)
        .visible(autoSwap::get)
        .build()
    );

    private final Setting<Boolean> keepCamera = sgSmash.add(new BoolSetting.Builder()
        .name("keep-camera")
        .description("Keep your own look through setbacks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatDebug = sgSmash.add(new BoolSetting.Builder()
        .name("chat-debug")
        .description("Telemetry in chat once a second.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> webEnabled = sgWeb.add(new BoolSetting.Builder()
        .name("web")
        .description("Web the victim while smashing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> webPlayersOnly = sgWeb.add(new BoolSetting.Builder()
        .name("players-only")
        .description("Only web players.")
        .defaultValue(true)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<WebWhen> webWhen = sgWeb.add(new EnumSetting.Builder<WebWhen>()
        .name("when")
        .description("Always, airborne, or gliding victims.")
        .defaultValue(WebWhen.Always)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<WebCells> webCells = sgWeb.add(new EnumSetting.Builder<WebCells>()
        .name("cells")
        .description("Feet, feet and head, or a cube.")
        .defaultValue(WebCells.FeetAndHead)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Double> webLead = sgWeb.add(new DoubleSetting.Builder()
        .name("lead")
        .description("Ticks to place webs ahead of movement.")
        .defaultValue(3)
        .min(0)
        .sliderMax(10)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Double> webRange = sgWeb.add(new DoubleSetting.Builder()
        .name("range")
        .description("Max reach for placing webs.")
        .defaultValue(4.5)
        .min(1)
        .sliderRange(2, 5.5)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Integer> websPerTick = sgWeb.add(new IntSetting.Builder()
        .name("webs-per-tick")
        .description("Max web placements per tick.")
        .defaultValue(2)
        .range(1, 18)
        .sliderRange(1, 18)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Integer> webDelay = sgWeb.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between web volleys.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Boolean> webAirPlace = sgWeb.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allow placing webs in mid air.")
        .defaultValue(true)
        .visible(webEnabled::get)
        .build()
    );

    private final Setting<Double> webSelfClearance = sgWeb.add(new DoubleSetting.Builder()
        .name("self-clearance")
        .description("Never web within this distance of yourself.")
        .defaultValue(2)
        .min(0.5)
        .sliderRange(0.5, 5)
        .visible(webEnabled::get)
        .build()
    );

    private double offset;
    private Entity target;
    private int cooldown;
    private int setbacks;
    private boolean posSentThisTick;
    private boolean serverOff;
    private String status = "idle";
    private int debugThrottle;
    private double prevRealY;
    private boolean havePrevRealY;

    private double fVx, fVy, fVz;
    private boolean fHorizontal;
    private boolean fVertical;
    private boolean fMoving;
    private double serverDY;

    private int webDelayLeft;
    private int telWebs;
    private int webNoItemThrottle;
    private final BlockPos.Mutable webScanPos = new BlockPos.Mutable();

    private int telTicks, telSwings, telLanded, telFresh;
    private double telDmg;
    private Entity telTarget;
    private int lastHurtTime;
    private float lastHealth;

    private boolean restSet;
    private double restY;
    private int ladder;
    private boolean serverAtDive;
    private boolean smashThisTick, dumpThisTick, restMoved;
    private double hopNow;
    private int lostTicks;
    private int sinceSmash;
    private int recentSetbacks;
    private int setbackDecay;
    private double altDiveY;

    private enum OPhase { Descend, Rise }
    private OPhase oPhase = OPhase.Descend;
    private boolean oPlanned;
    private boolean oMove;
    private double oDy;
    private boolean oSwing;
    private double oFill;
    private int oRung;
    private int oTick;
    private int oWindowTick = -1000;
    private Boolean serverGliding;
    private String oKind = "idle";
    private OPlan oLast;
    private String oDiag = "";
    private final ArrayDeque<String> oTrace = new ArrayDeque<>();
    private double prevRealX, prevRealZ, sweepX0, sweepZ0;
    private static volatile boolean keepLookArmed;
    private Vec3d lastWirePos;
    private double oStartY;
    private boolean correcting, correctionWasDesynced;
    private Vec3d correctionFrom;
    private double correctionGhostY;
    private String correctionTrace;
    private Entity statePlayer;
    private ClientWorld stateWorld;
    private ClientConnection stateConnection;

    private record ServerObservation(ClientConnection connection, Entity player, Boolean gliding, boolean landed) {}
    private final ConcurrentLinkedQueue<ServerObservation> observations = new ConcurrentLinkedQueue<>();

    private enum PPhase { Charge, Dive }
    private PPhase pPhase = PPhase.Charge;
    private int pDiveTicks;
    private double pFdEst;
    private int chargeTicks;
    private double pStrikeCharge, pStrikeFd;
    private boolean pStrikeBig;
    private boolean pNoToggle;
    private int pReglideIn;
    private static final double MACE_FULL_CHARGE_TICKS = 34.0;

    private enum Phase { Idle, Descend, Ascend }
    private Phase phase = Phase.Idle;
    private double cycleTop;
    private double strikeOffset;
    private double riseCeil;
    private boolean strikeNow;
    private boolean summaryPending;
    private int swings;

    public MaceSmash() {
        super(QuietteeUtils.CATEGORY, "mace-smash", "Mace smash aura while hovering on an elytra.");
    }

    @Override
    public void onActivate() {
        if (windBurst.get() && windBurstWarn.get()) {
            int lvl = windBurstLevel();
            if (lvl == 0) warning("wind-burst is on but your mace has no Wind Burst - nothing will shove.");
            else {
                info("Wind Burst %s: every bonus smash also shoves everything within 7 blocks for ZERO damage (6b6t's mace nerf does not apply - this is not damage).", lvl == 1 ? "I" : lvl == 2 ? "II" : "III");
                if (lvl >= 2) warning("Wind Burst %s pushes YOU at %.2f b/t - that may exceed 6b6t's vertical acceptance. Level I (1.2) is the safe one.", lvl == 2 ? "II" : "III", lvl == 2 ? 1.75 : 2.2);
                warning("the blast runs onExplodedBy(null) on you, which WIPES any movement grace you were holding.");
            }
        }

        boolean resumeOffset = homing && sameSession();
        stopHoming();
        if (!resumeOffset) {
            offset = 0;
            lastWirePos = mc.player == null ? null : mc.player.getEntityPos();
        }
        observations.clear();
        correcting = correctionWasDesynced = false;
        bindSession();
        resetCycle();
        target = null;
        telTarget = null;
        cooldown = 0;
        setbacks = 0;
        serverOff = false;
        serverAtDive = false;
        restSet = false;
        ladder = 0;
        lostTicks = 0;
        sinceSmash = 0;
        recentSetbacks = 0;
        setbackDecay = 0;
        havePrevRealY = false;
        pPhase = PPhase.Charge;
        pDiveTicks = 0;
        pFdEst = 0;
        pStrikeBig = false;
        chargeTicks = (int) MACE_FULL_CHARGE_TICKS;
        telTicks = telSwings = telLanded = telFresh = 0;
        telDmg = 0;
        webDelayLeft = 0;
        telWebs = 0;
        webNoItemThrottle = 0;
        fHorizontal = fVertical = fMoving = false;
        fVx = fVy = fVz = 0;
        status = "idle";
        oReset();
        serverGliding = null;
        NoFall nf = Modules.get().get(NoFall.class);
        if (nf != null && nf.isActive()) warning("NoFall is on - if your smashes come out as normal hits, turn it off (onGround=true resets fall distance).");
    }

    @Override
    public void onDeactivate() {
        if (sameSession()) {
            if (serverOff) { reglide(); serverOff = false; }
            if (serverAtDive) { sendPos(offset); serverAtDive = false; }
            if (offset != 0) startHoming();
        } else {
            offset = 0;
            lastWirePos = null;
        }
        resetCycle();
        restSet = false;
        target = null;
        if (autoSwap.get() && swapBack.get()) InvUtils.swapBack();
    }

    private void resetCycle() {
        phase = Phase.Idle;
        cycleTop = 0;
        strikeOffset = 0;
        riseCeil = 0;
        strikeNow = false;
        posSentThisTick = false;
        summaryPending = false;
        swings = 0;
        smashThisTick = false;
        dumpThisTick = false;
        restMoved = false;
    }

    private boolean desynced() {
        return offset != 0 || serverAtDive || phase != Phase.Idle;
    }

    private boolean sameSession() {
        return mc.player != null && mc.player == statePlayer && mc.world != null && mc.world == stateWorld
            && mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() == stateConnection;
    }

    private void bindSession() {
        statePlayer = mc.player;
        stateWorld = mc.world;
        stateConnection = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getConnection();
    }

    private void resetForSession() {
        bindSession();
        offset = 0;
        lastWirePos = mc.player == null ? null : mc.player.getEntityPos();
        serverOff = serverAtDive = restSet = havePrevRealY = false;
        correcting = correctionWasDesynced = false;
        target = telTarget = null;
        fHorizontal = fVertical = fMoving = false;
        serverGliding = null;
        observations.clear();
        resetCycle();
        oReset();
    }

    private boolean chain() {
        return engine.get() == Engine.Chain;
    }

    private boolean carryMode() {
        return engine.get() == Engine.Hover && unglide.get() == Unglide.Carry;
    }

    private double gentlePenalty() {
        return Math.min(Math.max(0, recentSetbacks - 2) * 0.5, Math.max(0, hop.get() - 1.6));
    }

    private double baseHop() {
        double h = hop.get() - gentlePenalty();
        if (chainMode.get() == ChainMode.SameTick) h = Math.min(h, 2 * divePacket.get());
        return Math.max(1.6, h);
    }

    private int smashPeriod() {
        return Math.max(swingPeriod(), Math.max(1, Math.round(20f / maxRate.get())));
    }

    private boolean acquire(Entity e) {
        return valid(e, true);
    }

    private boolean valid(Entity e, boolean acquiring) {
        if (e == mc.player || e == mc.getCameraEntity()) return false;
        if (!(e instanceof LivingEntity le) || le.isDead() || !e.isAlive()) return false;
        if (!entities.get().contains(e.getType())) return false;
        if (e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator() || !Friends.get().shouldAttack(p))) return false;
        if (!ignoreWalls.get() && !PlayerUtils.canSeeEntity(e)) return false;

        boolean locked = lockTarget.get() && !acquiring;
        boolean chasing = follow.get() && mc.player.isGliding();

        if (chasing) {
            double dx = e.getX() - mc.player.getX(), dz = e.getZ() - mc.player.getZ();
            double slack = locked ? 8 : 0;
            if (Math.sqrt(dx * dx + dz * dz) > followRange.get() + slack) return false;
            if (Math.abs(e.getY() - mc.player.getY()) > maxDive.get() + reach.get() + 8 + slack) return false;
        } else if (PlayerUtils.distanceTo(e) > maxDive.get() + reach.get() + 6) return false;

        if (!locked && fov.get() < 360) {
            Box b = e.getBoundingBox();
            Vec3d eye = mc.player.getEyePos();
            Vec3d dir = new Vec3d(e.getX() - eye.x, (b.minY + b.maxY) * 0.5 - eye.y, e.getZ() - eye.z);
            if (dir.lengthSquared() > 1e-6) {
                double dot = mc.player.getRotationVec(1f).dotProduct(dir.normalize());
                if (Math.toDegrees(Math.acos(MathHelper.clamp(dot, -1, 1))) > fov.get() / 2) return false;
            }
        }

        if (chasing) return true;

        if (occam()) return occamPlan(e, mc.player.getY(), true) != null;
        if (chain()) {
            Band band = band(e, EYE_GLIDING);
            if (band == null) return false;
            double idealRest = band.top - 0.3 + hop.get();
            return Math.abs(idealRest - mc.player.getY()) <= maxDive.get() + 2;
        }
        return plan(e, phase == Phase.Descend ? cycleTop : offset) != null;
    }

    private boolean haveMace() {
        return mc.player.getMainHandStack().isOf(Items.MACE) || (autoSwap.get() && InvUtils.findInHotbar(Items.MACE).found());
    }

    private boolean charged() {
        if (chain()) return true;
        return !fullChargeOnly.get() || mc.player.getAttackCooldownProgress(0) >= 1;
    }

    private double eyeHeight() {
        if (!mc.player.isGliding()) return mc.player.getEyeHeight(mc.player.getPose());
        return carryMode() ? EYE_STANDING : EYE_GLIDING;
    }

    private double minDrop() {
        return Math.max(2 * step.get(), 1.6);
    }

    private record Band(double bot, double top) {}

    private Band band(Entity e, double eye) {
        return bandFor(e.getBoundingBox(), eye);
    }

    private Band bandFor(Box b, double eye) {
        double x = mc.player.getX(), z = mc.player.getZ();
        double hx = Math.max(Math.max(b.minX - x, x - b.maxX), 0);
        double hz = Math.max(Math.max(b.minZ - z, z - b.maxZ), 0);
        return bandAt(b, eye, Math.sqrt(hx * hx + hz * hz));
    }

    private Band bandAt(Box b, double eye, double h) {
        double r = reach.get() - 0.05;
        if (h >= r) return null;
        double v = Math.sqrt(r * r - h * h);
        return new Band(b.minY - v - eye, b.maxY + v - eye);
    }

    private double[] window(Entity e) {
        Box b = e.getBoundingBox();
        if (occam()) {
            Band band = bandAt(b, EYE_GLIDING, 0);
            if (band == null) return null;
            double bot = Math.max(Math.max(band.bot + 0.2, b.minY + occamClearance.get() - EYE_GLIDING), webFloorY(b));
            return new double[]{bot + 1.0 + oStrikeDip(0) + 0.1, band.top + maxDive.get()};
        }
        if (chain()) {
            Band band = bandAt(b, EYE_GLIDING, 0);
            if (band == null) return null;
            double rest = band.top - 0.3 + baseHop();
            return new double[]{rest - maxDive.get(), rest + maxDive.get()};
        }
        Band band = bandAt(b, eyeHeight(), 0);
        if (band == null) return null;
        return new double[]{band.bot + minDrop() + 0.1, band.top + maxDive.get()};
    }

    private boolean spaceFree(double x, double y, double z) {
        return mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(x - mc.player.getX(), y - mc.player.getY(), z - mc.player.getZ()));
    }

    private void followTick() {
        fHorizontal = fVertical = fMoving = false;
        if (!follow.get() || target == null || !mc.player.isGliding()) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double lead = followLead.get();
        double tx = target.getX() + (target.getX() - target.lastX) * lead;
        double tz = target.getZ() + (target.getZ() - target.lastZ) * lead;
        double dx = tx - px, dz = tz - pz;
        double h = Math.sqrt(dx * dx + dz * dz);
        double excess = h - followRadius.get();
        fVx = fVz = 0;
        if (excess > 1e-3 && h > 1e-6) {
            double mv = Math.min(excess, followSpeed.get());
            fVx = dx / h * mv;
            fVz = dz / h * mv;
            fMoving = true;
        }
        fHorizontal = true;

        fVy = 0;

        VerticalFollow vf = followVertical.get();
        if (occam() && vf == VerticalFollow.Hold) vf = VerticalFollow.Window;
        if (vf != VerticalFollow.Off) {
            double[] win = window(target);
            if (win != null && win[1] > win[0]) {
                double m = Math.min(2.0, (win[1] - win[0]) * 0.25);
                double lo = win[0] + m, hi = win[1] - m;
                double maxV = followVerticalSpeed.get();
                if (vf == VerticalFollow.Hold) {

                    double ty = target.getBoundingBox().maxY + (target.getY() - target.lastY) * lead + followHeight.get();
                    ty = MathHelper.clamp(ty, lo, hi);
                    double dy = ty - py, tol = 0.5;
                    if (Math.abs(dy) > tol) { fVy = Math.signum(dy) * Math.min(Math.abs(dy) - tol, maxV); fMoving = true; }
                    fVertical = true;
                } else {
                    if (py < lo) { fVy = Math.min(maxV, lo - py); fVertical = true; }
                    else if (py > hi) { fVy = -Math.min(maxV, py - hi); fVertical = true; }
                    if (fVertical) fMoving = true;
                }
            }
        }

        if ((fVx != 0 || fVz != 0) && !spaceFree(px + fVx, py + (fVertical ? fVy : 0), pz + fVz)) {
            fVx = fVz = 0;
            if (!fVertical || fVy <= 0) { fVy = Math.min(followVerticalSpeed.get(), 1.0); fVertical = true; fMoving = true; }
        }
        if (fVertical && !spaceFree(px, py + fVy, pz)) fVertical = false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null || !fHorizontal || !mc.player.isGliding()) return;
        double vx = fVx, vz = fVz;
        double vy = fVertical ? fVy : event.movement.y;
        int cx = (int) Math.floor((mc.player.getX() + vx) / 16), cz = (int) Math.floor((mc.player.getZ() + vz) / 16);
        if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) { vx = 0; vz = 0; }
        ((IVec3d) event.movement).meteor$set(vx, vy, vz);
    }

    private void cameraTick() {
        if (!cameraTrack.get() || target == null) return;
        Box b = target.getBoundingBox();
        Vec3d c = new Vec3d((b.minX + b.maxX) * 0.5, (b.minY + b.maxY) * 0.5, (b.minZ + b.maxZ) * 0.5);
        mc.player.setYaw((float) Rotations.getYaw(c));
        mc.player.setPitch((float) MathHelper.clamp(Rotations.getPitch(c), -90, 90));
    }

    private record Plan(double botOff, double topOff, double strikeOff) {}

    private Plan plan(Entity e, double top) {
        Band band = band(e, eyeHeight());
        if (band == null) return null;
        double y = mc.player.getY();
        double botOff = band.bot - y;
        double topOff = band.top - y;
        if (topOff < top - maxDive.get()) return null;
        if (botOff > top) return null;
        double strike = Math.max(top - maxDive.get(), botOff + 0.05);
        if (strike > top - minDrop()) {
            if (top - minDrop() < botOff + 0.05) return null;
            strike = top - minDrop();
        }
        return new Plan(botOff, topOff, strike);
    }

    private boolean free(double off) {
        double x = mc.player.getX(), y = mc.player.getY() + off, z = mc.player.getZ();
        double h = (mc.player.isGliding() && !carryMode()) ? 0.6 : 1.8;
        return mc.world.isSpaceEmpty(mc.player, new Box(x - 0.3, y, z - 0.3, x + 0.3, y + h, z + 0.3));
    }

    private boolean columnFree(double lo, double hi, double boxHeight) {
        double x = mc.player.getX(), z = mc.player.getZ();
        return mc.world.isSpaceEmpty(mc.player, new Box(x - 0.3, Math.min(lo, hi), z - 0.3, x + 0.3, Math.max(lo, hi) + boxHeight, z + 0.3));
    }

    private void sendPos(double off) {
        sendPosAbs(mc.player.getY() + off);
    }

    private void sendPosAbs(double y) {
        sendPosRaw(y);
        posSentThisTick = true;
    }

    private void sendPosRaw(double y) {
        PlayerMoveC2SPacket p = new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), y, mc.player.getZ(), false, mc.player.horizontalCollision);
        ((IPlayerMoveC2SPacket) p).meteor$setTag(TAG);
        mc.getNetworkHandler().sendPacket(p);
    }

    private int windBurstLevel() {
        if (mc.player == null) return 0;
        ItemStack mace = mc.player.getMainHandStack().isOf(Items.MACE) ? mc.player.getMainHandStack() : null;
        if (mace == null) {
            FindItemResult r = InvUtils.findInHotbar(Items.MACE);
            if (!r.found()) return 0;
            mace = mc.player.getInventory().getStack(r.slot());
        }
        for (var entry : mace.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesId(Identifier.ofVanilla("wind_burst"))) return entry.getIntValue();
        }
        return 0;
    }

    private void sendToggle() {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private void unglide() {
        if (serverGliding != Boolean.FALSE) sendToggle();
    }

    private void reglide() {
        if (serverGliding != Boolean.TRUE) sendToggle();
    }

    private void attackWithMace(Entity t) {
        int prevSlot = -1;
        boolean swapped = false;
        if (!mc.player.getMainHandStack().isOf(Items.MACE)) {
            FindItemResult mace = InvUtils.findInHotbar(Items.MACE);
            if (mace.found()) { prevSlot = mc.player.getInventory().getSelectedSlot(); InvUtils.swap(mace.slot(), false); swapped = true; }
        }
        mc.interactionManager.attackEntity(mc.player, t);
        mc.player.swingHand(Hand.MAIN_HAND);
        if (swapped && swapBack.get() && prevSlot != -1) InvUtils.swap(prevSlot, false);
    }

    private void rewriteOwnMove(PacketEvent.Send event) {
        if (correcting || !sameSession()) return;
        if (!(event.packet instanceof PlayerMoveC2SPacket p) || !p.changesPosition()) return;
        if (((IPlayerMoveC2SPacket) p).meteor$getTag() == TAG) return;
        posSentThisTick = true;
        if (!desynced()) return;
        ((PlayerMoveC2SPacketAccessor) p).quiettee$setY(p.getY(mc.player.getY()) + offset);
        ((PlayerMoveC2SPacketAccessor) p).quiettee$setOnGround(false);
    }

    private void recordWirePosition(PacketEvent.Sent event) {
        if (!sameSession() || event.connection != stateConnection) return;
        if (event.packet instanceof PlayerMoveC2SPacket p && p.changesPosition()) {
            lastWirePos = new Vec3d(p.getX(mc.player.getX()), p.getY(mc.player.getY()), p.getZ(mc.player.getZ()));
        }
    }

    private double ghostY() {
        return lastWirePos != null ? lastWirePos.y : MaceMovementMath.ghostY(serverAtDive, altDiveY, mc.player.getY(), offset);
    }

    private boolean walkOffsetToward(double want) {
        double realY = mc.player.getY();
        double realDY = havePrevRealY ? realY - prevRealY : 0;
        prevRealY = realY;
        havePrevRealY = true;

        double up = Math.max(riseStep.get(), realDY), down = Math.max(step.get(), -realDY);
        double lo = -down - realDY, hi = up - realDY;
        if (lo > hi) lo = hi = -realDY;
        double d = MathHelper.clamp(want - offset, lo, hi);
        offset += d;
        if (Math.abs(offset - want) < 1e-9) offset = want;
        serverDY = realDY + d;
        return Math.abs(serverDY) > 1e-9;
    }

    private boolean homing;
    private final Homer homer = new Homer();

    private class Homer {
        @EventHandler
        private void onSendPre(SendMovementPacketsEvent.Pre event) {
            if (!sameSession()) { offset = 0; lastWirePos = null; stopHoming(); return; }
            posSentThisTick = false;
            walkOffsetToward(0);
        }

        @EventHandler
        private void onSend(PacketEvent.Send event) {
            rewriteOwnMove(event);
        }

        @EventHandler
        private void onSent(PacketEvent.Sent event) {
            recordWirePosition(event);
        }

        @EventHandler
        private void onSendPost(SendMovementPacketsEvent.Post event) {
            if (!sameSession()) { offset = 0; lastWirePos = null; stopHoming(); return; }
            if (!posSentThisTick) sendPos(offset);
            if (offset == 0) stopHoming();
        }

        @EventHandler
        private void onCorrectionBefore(PlayerPositionLookEvent.Before event) {
            if (!sameSession()) return;
            correcting = true;
        }

        @EventHandler
        private void onCorrectionAfter(PlayerPositionLookEvent.After event) {
            offset = 0;
            lastWirePos = sameSession() ? mc.player.getEntityPos() : null;
            correcting = false;
            mc.send(() -> { if (!isActive()) stopHoming(); });
        }
    }

    private void startHoming() {
        if (homing) return;
        homing = true;
        havePrevRealY = false;
        MeteorClient.EVENT_BUS.subscribe(homer);
    }

    private void stopHoming() {
        if (!homing) return;
        homing = false;
        MeteorClient.EVENT_BUS.unsubscribe(homer);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onReceiveFirst(PacketEvent.Receive event) {

        if (event.packet instanceof EntityVelocityUpdateS2CPacket p && sameSession() && p.getEntityId() == mc.player.getId() && isLandedMarker(p.getVelocity().y)) {
            observations.add(new ServerObservation(event.connection, mc.player, null, true));
        }
    }

    private static boolean isLandedMarker(double y) {
        return y > 0.0 && y < 0.02 && Math.abs(y - 0.01) < 2.5e-4;
    }

    private void drainObservations() {
        ServerObservation observation;
        while ((observation = observations.poll()) != null) {
            if (mc.player == null || mc.getNetworkHandler() == null
                || observation.connection != mc.getNetworkHandler().getConnection() || observation.player != mc.player) continue;
            if (observation.gliding != null) serverGliding = observation.gliding;
            if (observation.landed) telLanded++;
        }
    }

    @EventHandler
    private void onCorrectionBefore(PlayerPositionLookEvent.Before event) {
        if (mc.player == null) return;
        if (!sameSession()) resetForSession();
        drainObservations();
        correcting = true;
        correctionWasDesynced = desynced();
        correctionFrom = mc.player.getEntityPos();
        correctionGhostY = ghostY();
        correctionTrace = String.join(" → ", oTrace);
        if (correctionWasDesynced && keepCamera.get()) keepLookArmed = true;
    }

    @EventHandler
    private void onCorrectionAfter(PlayerPositionLookEvent.After event) {
        if (mc.player == null) { correcting = correctionWasDesynced = false; return; }
        lastWirePos = mc.player.getEntityPos();
        havePrevRealY = false;
        correcting = false;
        if (!correctionWasDesynced) return;
        correctionWasDesynced = false;
        setbacks++;
        double ddx = mc.player.getX() - correctionFrom.x, ddy = mc.player.getY() - correctionFrom.y, ddz = mc.player.getZ() - correctionFrom.z;

        if (serverOff) { reglide(); serverOff = false; }
        serverAtDive = false;
        offset = 0;
        restSet = false;
        resetCycle();
        oReset();
        havePrevRealY = false;
        if (occam()) {
            cooldown = Math.max(cooldown, occamHold.get());
            status = "setback → holding " + cooldown + "t";
            if (chatDebug.get()) info("setback #%d · teleport Y %.1f = %+.1f from client Y %.1f (our server-side Y was %.1f, %.1f sideways) · last moves: %s",
                setbacks, mc.player.getY(), ddy, correctionFrom.y, correctionGhostY, Math.sqrt(ddx * ddx + ddz * ddz), correctionTrace.isEmpty() ? "-" : correctionTrace);
        } else {
            recentSetbacks = Math.min(recentSetbacks + 1, 6);
            setbackDecay = 200;
            cooldown = Math.max(cooldown, 20 + 15 * recentSetbacks);
            status = "setback → backing off " + cooldown + "t";
            if (chatDebug.get()) {
                if (recentSetbacks >= 3) info("setback #%d - auto-gentling (hop -%.1f). If it keeps happening: lower dive-packet, then chain-mode Alternate, then engine Hover.", setbacks, gentlePenalty());
                else info("server teleported us (setback #%d, %+.1f up/down) - holding %dt so ElytraFly settles.", setbacks, ddy, cooldown);
            }
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!sameSession()) return;

        if (event.packet instanceof EntityTrackerUpdateS2CPacket p && p.id() == mc.player.getId()) {
            for (DataTracker.SerializedEntry<?> en : p.trackedValues()) {
                if (en.id() == TRACKED_FLAGS_ID && en.value() instanceof Byte b) {
                    observations.add(new ServerObservation(event.connection, mc.player, (b & 0x80) != 0, false));
                }
            }
        }

        if (carryMode() && desynced() && event.packet instanceof EntityTrackerUpdateS2CPacket p && p.id() == mc.player.getId()) {
            List<DataTracker.SerializedEntry<?>> list = p.trackedValues();
            try {
                list.removeIf(en -> (en.id() == TRACKED_FLAGS_ID && Objects.equals(en.handler(), TrackedDataHandlerRegistry.BYTE))
                    || (en.id() == TRACKED_POSE_ID && Objects.equals(en.handler(), TrackedDataHandlerRegistry.ENTITY_POSE)));
                if (list.isEmpty()) event.cancel();
            } catch (UnsupportedOperationException e) {
                if (list.stream().allMatch(en -> en.id() == TRACKED_FLAGS_ID || en.id() == TRACKED_POSE_ID)) event.cancel();
            }
        }
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (!sameSession()) resetForSession();
        drainObservations();
        posSentThisTick = false;
        strikeNow = false;
        if (cooldown > 0) cooldown--;
        ladder++;
        if (sinceSmash < 1000) sinceSmash++;
        if (chargeTicks < 1000) chargeTicks++;
        if (recentSetbacks > 0 && --setbackDecay <= 0) { recentSetbacks--; setbackDecay = 200; }

        if ((serverOff || (carryMode() && phase == Phase.Descend)) && !mc.player.isGliding()) mc.player.startGliding();

        oTick++;
        if (occam()) {
            if (phase != Phase.Idle) resetCycle();
            if (serverAtDive) { sendPos(offset); serverAtDive = false; }
            restSet = false;
            if (target == null || !target.isAlive() || !valid(target, false)) {
                Entity t = TargetUtils.get(this::acquire, priority.get());
                if (t != target) { target = t; oRung = 0; oWindowTick = -1000; }
            }
        } else if (chain()) {
            if (phase != Phase.Idle) resetCycle();
            if (target == null || !target.isAlive() || !valid(target, false)) {
                Entity t = TargetUtils.get(this::acquire, priority.get());
                if (t != target) { target = t; restSet = false; ladder = 0; }
            }
        } else {
            if (serverAtDive) { sendPos(offset); serverAtDive = false; }
            restSet = false;
            hoverAdvance();
        }

        telemetryTick();
        followTick();
        cameraTick();

        boolean aim = !occam() && (chain() ? restSet : (phase != Phase.Idle || strikeNow));
        if (faceTarget.get() && target != null && aim) {
            Box b = target.getBoundingBox();
            double ex = mc.player.getX(), ez = mc.player.getZ();
            double ey = chain() ? restY - hopNow + EYE_GLIDING : mc.player.getY() + offset + eyeHeight();
            double dx = (b.minX + b.maxX) * 0.5 - ex, dy = (b.minY + b.maxY) * 0.5 - ey, dz = (b.minZ + b.maxZ) * 0.5 - ez;
            double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            Rotations.rotate(Rotations.getYaw(target), MathHelper.clamp(pitch, -90, 90), 100);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onSendPre(SendMovementPacketsEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (!sameSession()) resetForSession();
        drainObservations();
        if (occam()) {
            occamPrepare();
        } else if (chain()) {
            chainPrepare();
        } else if (carryMode() && serverOff) {
            reglide();
            serverOff = false;
        }
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        rewriteOwnMove(event);

        if (sameSession() && event.packet instanceof ClientCommandC2SPacket c && c.getMode() == ClientCommandC2SPacket.Mode.START_FALL_FLYING && serverGliding != null) serverGliding = !serverGliding;
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        recordWirePosition(event);
    }

    @EventHandler
    private void onSendPost(SendMovementPacketsEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (occam()) occamPost();
        else if (chain()) chainPost();
        else hoverPost();
        webTick();
    }

    private static float hp(LivingEntity le) {
        return le.getHealth() + le.getAbsorptionAmount();
    }

    private int rungs() {
        return chainMode.get() == ChainMode.SameTick ? 10 : 5;
    }

    private int swingPeriod() {
        return chainMode.get() == ChainMode.SameTick ? 1 : 2;
    }

    private void telemetryTick() {
        if (target != telTarget) {
            telTarget = target;
            lastHurtTime = target instanceof LivingEntity le ? le.hurtTime : 0;
            lastHealth = target instanceof LivingEntity le ? hp(le) : 0;
        }
        if (target instanceof LivingEntity le) {
            int ht = le.hurtTime;
            if (ht > lastHurtTime) {

                telFresh++;
                if (chain() && phaseLock.get()) ladder = (int) Math.round(PlayerUtils.getPing() / 50.0);
            }
            lastHurtTime = ht;
            float h = hp(le);
            if (h < lastHealth) telDmg += lastHealth - h;
            lastHealth = h;
        }
        if (++telTicks >= 20) {
            if (chatDebug.get() && (target != null || telSwings > 0 || telLanded > 0)) {
                info("%s %s · swings %d · smash-markers %d · observed-hurts %d · observed-hp-loss %.1f · ping %dms · setbacks %d · srv-glide %s%s%s",
                    occam() ? "OCCAM" : chain() ? "CHAIN" : "HOVER", target != null ? EntityUtils.getName(target) : "-",
                    telSwings, telLanded, telFresh, telDmg, PlayerUtils.getPing(), setbacks,
                    serverGliding == null ? "?" : serverGliding ? "on" : "OFF",
                    webEnabled.get() ? " · webs " + telWebs : "",
                    telSwings == 0 ? " · " + status + (occam() && !oDiag.isEmpty() ? " · " + oDiag : "") : "");
            }
            telTicks = 0;
            telSwings = telLanded = telFresh = 0;
            telWebs = 0;
            telDmg = 0;
        }
    }

    private void chainPrepare() {
        smashThisTick = false;
        dumpThisTick = false;
        restMoved = false;
        double realY = mc.player.getY();

        Band band = (target != null && mc.player.isGliding()) ? band(target, EYE_GLIDING) : null;
        if (band == null) {

            if (restSet && ++lostTicks <= (fMoving ? 60 : 20)) {
                restMoved = walkOffsetToward(restY - realY);
            } else {
                restSet = false;
                restMoved = walkOffsetToward(0);
            }
            if (target == null) report("no target in reach");
            else status = !mc.player.isGliding() ? "not gliding" : fMoving ? "following · closing in" : "target too far sideways";
            return;
        }
        lostTicks = 0;

        double base = baseHop();
        double esc = Math.max(0, Math.min(escalate.get(), (band.top - band.bot) - 0.6));
        double climb = ((ladder / swingPeriod()) % rungs()) * esc / rungs();
        hopNow = base + climb;
        if (chainMode.get() == ChainMode.SameTick) hopNow = Math.min(hopNow, 2 * divePacket.get());
        hopNow = Math.max(1.6, hopNow);

        if (restSet && !restValid(band, realY, base, esc)) restSet = false;
        if (!restSet && !planRest(band, realY, base, esc)) {
            restMoved = walkOffsetToward(0);
            return;
        }

        double want = restY - realY;
        restMoved = walkOffsetToward(want);
        boolean settled = Math.abs(offset - want) < 1e-6;
        if (!settled) { status = String.format("moving into position · %.1f to go", Math.abs(offset - want)); return; }

        if (chainMode.get() == ChainMode.Alternate && serverAtDive) {

            double back = realY + offset;
            double sub = Math.max(0.1, divePacket.get());
            int n = (int) Math.ceil((back - altDiveY) / sub - 1e-9);
            for (int i = 1; i < n; i++) sendPosRaw(altDiveY + sub * i);
            status = "returning";
            return;
        }
        if (restMoved && Math.abs(serverDY) > divePacket.get() + 1e-9) { status = "parked · adjusting"; return; }
        if (cooldown > 0) { status = "cooldown " + cooldown + "t"; return; }
        if (sinceSmash < smashPeriod()) { status = "pacing (" + maxRate.get() + "/s)"; return; }
        if (!haveMace()) { report("no mace in hotbar"); return; }
        if (!charged()) { status = "charging"; return; }
        if (!columnFree(restY - hopNow, restY, 0.6)) { status = "dive blocked"; return; }

        dumpThisTick = mc.player.getAttackCooldownProgress(0) > 0.9;
        smashThisTick = true;
    }

    private boolean restValid(Band band, double realY, double base, double esc) {
        double d0 = restY - base;
        return Math.abs(restY - realY) <= maxDive.get() + 1e-6
            && d0 <= band.top - 0.05
            && d0 >= band.top - 0.3 - 0.5
            && d0 - esc >= band.bot + 0.2
            && columnFree(restY - base - esc, restY, 0.6);
    }

    private boolean planRest(Band band, double realY, double base, double esc) {
        double top = band.top - 0.3;
        double rest = top + base;
        if (Math.abs(rest - realY) > maxDive.get()) {
            report(rest < realY ? String.format("too high: hover lower or raise max-dive (need %.0f)", realY - rest) : String.format("too low: hover higher or raise max-dive (need %.0f)", rest - realY));
            return false;
        }
        if (top - esc < band.bot + 0.2) { report("reach band too short for this ladder - lower escalate"); return false; }
        double lim = Math.min(band.top + base - 0.05, realY + maxDive.get());
        for (double r = rest; r <= lim + 1e-9; r += 0.5) {
            if (columnFree(r - base - esc, r, 0.6)) {
                restY = r;
                restSet = true;
                return true;
            }
        }
        report("blocked above the victim");
        return false;
    }

    private void chainPost() {
        if (restMoved && !posSentThisTick) sendPos(offset);
        if (smashThisTick) { chainStrike(); return; }
        if (serverAtDive) {
            if (!posSentThisTick) sendPos(offset);
            serverAtDive = false;
        }
    }

    private void chainStrike() {
        if (target == null) return;
        double rest = mc.player.getY() + offset;
        boolean smash = !dumpThisTick;
        boolean sameTick = chainMode.get() == ChainMode.SameTick;
        double sub = Math.max(0.1, divePacket.get());
        int n = Math.max(1, (int) Math.ceil(hopNow / sub - 1e-9));

        for (int i = 1; i <= n; i++) sendPosAbs(rest - Math.min(hopNow, sub * i));

        if (smash) { unglide(); serverOff = true; }
        attackWithMace(target);
        if (smash) { reglide(); serverOff = false; }

        if (sameTick) {
            for (int i = n - 1; i >= 1; i--) sendPosAbs(rest - sub * i);
            sendPosAbs(rest);
        } else {
            serverAtDive = true;
            altDiveY = rest - hopNow;
        }

        sinceSmash = 0;
        telSwings++;
        int rung = (ladder / swingPeriod()) % rungs();
        status = String.format("CHAIN %s · rung %d/%d · hop %.2f · parked %+.1f%s", EntityUtils.getName(target), rung, rungs(), hopNow, offset, dumpThisTick ? " · charge dump" : "");
    }

    private boolean occam() {
        return engine.get() == Engine.Occam;
    }

    private int oUnit() {
        return 1 + (int) Math.ceil(1.0 / occamDive.get() - 1e-9);
    }

    private int oRungs() {
        return Math.max(1, 10 / oUnit());
    }

    private double oStrikeDip(int rung) {
        return 0.5 + (rung + 1) * (occamDive.get() - 0.5) / oRungs();
    }

    private void oReset() {
        oPhase = OPhase.Descend;
        oPlanned = false;
        oMove = false;
        oDy = 0;
        oSwing = false;
        oFill = 0;
        oRung = 0;
        oWindowTick = -1000;
        oKind = "idle";
        oTrace.clear();
        pPhase = PPhase.Charge;
        pDiveTicks = 0;
        pFdEst = 0;
        pNoToggle = false;
        pReglideIn = 0;
    }

    private record OPlan(double top, double bot, Band band, Box box) {}

    private Box leadBox(Entity e) {
        double lead = Math.min(5.0, PlayerUtils.getPing() / 50.0);
        return e.getBoundingBox().offset((e.getX() - e.lastX) * lead, (e.getY() - e.lastY) * lead, (e.getZ() - e.lastZ) * lead);
    }

    private int oRungsFit(OPlan p) {
        double room = Math.min(p.top, p.band.top) - p.bot;
        double used = 0;
        int fit = 0;
        while (fit < oRungs()) {
            used += 1.0 + oStrikeDip(fit);
            if (used > room + 1e-6) break;
            fit++;
        }
        return Math.max(1, fit);
    }

    private double oRiseTarget(OPlan p, int nextRung) {
        if (occamRiseTo.get() != OccamRise.Bounce) return p.top;
        return Math.min(p.top, p.bot + 1.0 + oStrikeDip(nextRung) + 0.1);
    }

    private OPlan occamPlan(Entity e, double realY, boolean quiet) {
        Box b = leadBox(e);
        Band band = bandFor(b, EYE_GLIDING);
        if (band == null) { if (!quiet) status = "target too far sideways"; return null; }
        if (realY - band.top > maxDive.get()) { if (!quiet) report(String.format("too high: hover lower or raise max-dive (need %.0f)", realY - band.top)); return null; }
        double top = occamRiseTo.get() == OccamRise.RealPosition ? realY : Math.min(realY, band.top);

        double floor = Math.max(b.minY + occamClearance.get() - EYE_GLIDING, webFloorY(b));
        double bot = Math.max(Math.max(band.bot + 0.2, floor), realY - maxDive.get());
        if (Math.min(top, band.top) - bot < 1.0 + oStrikeDip(0) + 0.05) { if (!quiet) report("too low: hover higher / closer"); return null; }
        return new OPlan(top, bot, band, b);
    }

    private boolean oColumnFree(double lo, double hi) {
        double x1 = mc.player.getX(), z1 = mc.player.getZ();
        double x0 = havePrevRealY ? sweepX0 : x1, z0 = havePrevRealY ? sweepZ0 : z1;

        var sweep = MaceMovementMath.sweptBody(x0, lo, z0, x1, hi, z1, 0.3, 0.6);
        return mc.world.isSpaceEmpty(mc.player, new Box(sweep.minX(), sweep.minY(), sweep.minZ(), sweep.maxX(), sweep.maxY(), sweep.maxZ()));
    }

    private void occamPrepare() {
        double realY = mc.player.getY(), realX = mc.player.getX(), realZ = mc.player.getZ();
        double realDY = havePrevRealY ? realY - prevRealY : 0;
        double realDH = havePrevRealY ? Math.sqrt((realX - prevRealX) * (realX - prevRealX) + (realZ - prevRealZ) * (realZ - prevRealZ)) : 0;
        oStartY = lastWirePos != null ? lastWirePos.y : (havePrevRealY ? prevRealY : realY) + offset;
        sweepX0 = lastWirePos != null ? lastWirePos.x : realX;
        sweepZ0 = lastWirePos != null ? lastWirePos.z : realZ;
        prevRealY = realY;
        prevRealX = realX;
        prevRealZ = realZ;
        havePrevRealY = true;

        oMove = false;
        oDy = 0;
        oSwing = false;
        oPlanned = false;
        oKind = "hold";
        OPlan p = null;
        if (target == null || !mc.player.isGliding()) {
            status = target == null ? "no target in reach" : "not gliding";
            oIdle();
        } else if ((p = occamPlan(target, realY, false)) == null) {
            oIdle();
        } else {
            oPlanned = true;
            lostTicks = 0;
            occamDecide(p, realY);
        }
        oLast = p;

        if (oMove) {
            offset = MaceMovementMath.offsetAfterMove(oStartY, oDy, realY);
            if (Math.abs(offset) < 1e-9) offset = 0;
            serverDY = oDy;
        } else {
            serverDY = realDY;
            if (realDY > 1e-6) oFill = 0;
            else if (realDY < -1e-6) oFill = Math.min(1.0, oFill - realDY);
        }

        oTrace.addLast(String.format("%s srv%+.2f cl%+.2f h%.1f @%.1f", oKind, serverDY, realDY, realDH, realY + offset));
        while (oTrace.size() > 8) oTrace.removeFirst();

        if (p != null) {
            double head = p.box.maxY, srvY = realY + offset;
            oDiag = String.format("%s srv %+.1f/head · band.top %+.1f · bot %+.1f · room %.1f · fit %d · rung %d · fill %.2f · wait %d · cool %d · mace %s · glide %s · off %+.1f",
                oPhase, srvY - head, p.band.top - head, p.bot - head, Math.min(p.top, p.band.top) - p.bot, oRungsFit(p), oRung, oFill,
                Math.max(0, 11 - (oTick - oWindowTick)), cooldown, haveMace() ? "y" : "NO", serverGliding == null ? "?" : serverGliding ? "on" : "OFF", offset);
        } else oDiag = "";

        if (oPlanned && !oSwing && power.get() == Power.Off && stallKick.get() > 0 && sinceSmash >= stallKick.get()) {
            if (chatDebug.get()) info("stall %.1fs · %s · %s → kicking the engine", sinceSmash / 20.0, status, oDiag);
            oReset();
            serverGliding = null;
            cooldown = 0;
            lostTicks = 0;
            sinceSmash = 0;
            havePrevRealY = false;
        }

        if (faceTarget.get() && p != null) aimAt(p.box, realY + offset + EYE_GLIDING);
    }

    private void aimAt(Box b, double eyeY) {
        double dx = (b.minX + b.maxX) * 0.5 - mc.player.getX(), dy = (b.minY + b.maxY) * 0.5 - eyeY, dz = (b.minZ + b.maxZ) * 0.5 - mc.player.getZ();
        double yaw = MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90);
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        Rotations.rotate(yaw, MathHelper.clamp(pitch, -90, 90), 100);
    }

    private void occamDecide(OPlan p, double realY) {
        if (power.get() != Power.Off) { occamDecidePower(p, realY); return; }
        double serverY = oStartY;
        String name = EntityUtils.getName(target);
        if (cooldown > 0) { oKind = "cool"; status = "cooldown " + cooldown + "t"; return; }
        if (!haveMace()) { report("no mace in hotbar"); return; }

        int fit = oRungsFit(p);
        int next = oRung >= fit ? 0 : oRung;

        if (oPhase == OPhase.Rise) {
            double to = oRiseTarget(p, next);
            if (serverY < to - 1e-6) {
                oRise(serverY, to, name, "rising");
                return;
            }
            oPhase = OPhase.Descend;
        }

        double s = occamDive.get();
        if (serverY > p.band.top + 1e-6) {

            double dip = Math.min(Math.max(s, 1.5), serverY - p.band.top);
            if (!oDip(p, serverY, dip, next, name)) return;
            oFill = Math.min(1.0, oFill + dip);
            oKind = "approach";
            status = String.format("OCCAM %s · approaching %.1f · parked %+.1f", name, serverY - dip - p.band.top, offset + oDy);
            return;
        }

        if (next == 0) {

            int wait = 11 - (oTick - oWindowTick);
            if (wait > 0) {
                oFill = 0;
                double to = oRiseTarget(p, 0);
                if (serverY < to - 1e-6) oRise(serverY, to, name, String.format("rising (i-frames %dt)", wait));
                else { oKind = "wait"; status = String.format("OCCAM %s · waiting for i-frames (%dt) · parked %+.1f", name, wait, offset); }
                return;
            }
            oRung = 0;
        }

        if (oFill < 1.0 - 1e-9) {
            double dip = Math.min(s, 1.0 - oFill);
            if (!oDip(p, serverY, dip, next, name)) return;
            oFill += dip;
            oKind = "fill";
            status = String.format("OCCAM %s · filler · rung %d/%d · fd≈%.2f · parked %+.1f", name, next, fit, Math.min(oFill, 1.0), offset + oDy);
            return;
        }

        double dip = oStrikeDip(next);
        if (!oDip(p, serverY, dip, next, name)) return;
        oSwing = true;
        oFill = 0;
        oKind = "SMASH r" + next;
        status = String.format("OCCAM %s · SMASH · rung %d/%d · fd≈%.2f · parked %+.1f", name, next, fit, 1.0 + dip, offset + oDy);
    }

    private static final double POWER_MAX_DIP = 1.5;

    private double safeDipDown(double serverY, double cap) {
        double d = Math.min(cap, POWER_MAX_DIP);
        while (d > 0.05 && !oColumnFree(serverY - d, serverY)) d -= 0.1;
        return d > 0.05 ? d : 0;
    }

    private void pRise(double serverY, double to, String name, String what) {
        double dy = Math.min(Math.min(occamRise.get(), POWER_MAX_DIP), to - serverY);
        if (dy <= 0 || !oColumnFree(serverY, serverY + dy)) { oMove = false; oKind = "hold"; status = String.format("%s · blocked above", name); return; }
        oMove = true;
        oDy = dy;
        oKind = "rise";
        status = String.format("GUILLOTINE %s · %s · %.1f to go · parked %+.1f", name, what, to - serverY - dy, offset + dy);
    }

    private void pBankFd(double dip, double step) {
        pDiveTicks++;

        pFdEst = Math.min(1.0 + dip, 1.0 + Math.min(step, POWER_MAX_DIP));
    }

    private void pStrike(double charge, String tag, String name) {
        oSwing = true;
        oRung = 0;
        pStrikeBig = true;
        pStrikeCharge = charge;
        pStrikeFd = pFdEst;
        oKind = "SMASH*";
        status = String.format("%s %s · SMASH · charge %.0f%% · fd≈%.1f · parked %+.1f", tag, name, charge * 100, pFdEst, offset + oDy);
        pPhase = PPhase.Charge;
    }

    private void occamDecidePower(OPlan p, double realY) {
        if (power.get() == Power.Guillotine) { occamDecideGuillotine(p, realY); return; }
        double serverY = oStartY;
        String name = EntityUtils.getName(target);
        String tag = "HAYMAKER";
        if (cooldown > 0) { oKind = "cool"; status = "cooldown " + cooldown + "t"; return; }
        if (!haveMace()) { report("no mace in hotbar"); return; }

        double step = Math.min(occamDive.get(), POWER_MAX_DIP);
        double charge = Math.min(1.0, chargeTicks / MACE_FULL_CHARGE_TICKS);

        boolean chargeReady = (windBurst.get() && windBurstCadence.get()) || charge >= minCharge.get() - 1e-6;
        int ticksToCharge = chargeReady ? 0 : (int) Math.ceil((minCharge.get() - charge) * MACE_FULL_CHARGE_TICKS);
        double top = p.band.top(), bot = p.band.bot();
        double reachInset = Math.min(2.0, (top - bot) * 0.4);

        double diveTargetY = top - reachInset;

        if (pPhase == PPhase.Charge) {
            int ticksToDive = (int) Math.ceil(Math.max(0, realY - diveTargetY) / Math.max(0.1, step)) + 2;
            if (!chargeReady && ticksToCharge > ticksToDive) {
                pDiveTicks = 0; pFdEst = 0;
                double gap = serverY - realY;
                if (gap > 0.05) { oMove = true; oDy = -Math.min(step, gap); }
                else if (gap < -0.05) { oMove = true; oDy = Math.min(Math.min(occamRise.get(), POWER_MAX_DIP), -gap); }
                else oMove = false;
                oKind = "charge";
                status = String.format("%s %s · charging %.0f%% (%dt) · parked %+.1f", tag, name, charge * 100, ticksToCharge, offset);
                return;
            }
            pPhase = PPhase.Dive; pDiveTicks = 0; pFdEst = 0;
        }

        boolean atSpot = serverY <= diveTargetY + 1e-6;
        boolean roomBelow = oColumnFree(serverY - POWER_MAX_DIP, serverY);
        if (!(atSpot && roomBelow)) {
            double dip = safeDipDown(serverY, Math.min(step, serverY - bot));
            if (dip >= 0.3) {
                oMove = true; oDy = -dip; pBankFd(dip, step);
                oKind = "closing";
                status = String.format("%s %s · closing in · charge %.0f%% · parked %+.1f", tag, name, charge * 100, offset + oDy);
                return;
            }
            if (!roomBelow) {
                oMove = true; oDy = Math.min(occamRise.get(), 1.0);
                oKind = "reposition";
                status = String.format("%s %s · no dip room, rising for a clean smash", tag, name);
                return;
            }

        }

        if (!chargeReady) {
            oMove = false; oKind = "wait";
            status = String.format("%s %s · in reach, charging %.0f%% · parked %+.1f", tag, name, charge * 100, offset);
            return;
        }

        double sdip = safeDipDown(serverY, POWER_MAX_DIP);
        if (sdip < 0.6) {
            oMove = true; oDy = Math.min(occamRise.get(), 1.0);
            oKind = "reposition";
            status = String.format("%s %s · no dip room, rising", tag, name);
            return;
        }
        oMove = true; oDy = -sdip; pBankFd(sdip, step);
        pStrike(charge, tag, name);
    }

    private static final int CLAMP_RELEASE_TICKS = 6;

    private void occamDecideGuillotine(OPlan p, double realY) {
        double serverY = oStartY;
        String name = EntityUtils.getName(target);
        if (cooldown > 0) { oKind = "cool"; status = "cooldown " + cooldown + "t"; return; }
        if (!haveMace()) { report("no mace in hotbar"); return; }

        double step = Math.min(powerDive.get(), POWER_MAX_DIP);
        double charge = Math.min(1.0, chargeTicks / MACE_FULL_CHARGE_TICKS);

        boolean chargeReady = (windBurst.get() && windBurstCadence.get()) || charge >= minCharge.get() - 1e-6;
        int chargeWait = chargeReady ? 0 : (int) Math.ceil((minCharge.get() - charge) * MACE_FULL_CHARGE_TICKS);
        int windowWait = Math.max(0, 11 - (oTick - oWindowTick));

        boolean sneak = stealth.get();
        double depthNeeded = sneak ? bankFd.get() + 0.5 : CLAMP_RELEASE_TICKS * step + Math.max(0, bankFd.get() - 1.0 - step);
        double diveStart = Math.min(realY, p.bot + depthNeeded);

        if (pPhase == PPhase.Charge) {

            if (serverOff && pReglideIn > 0 && --pReglideIn == 0) {
                serverGliding = Boolean.FALSE;
                reglide();
                serverOff = false;
            }
            int diveTicks = (int) Math.ceil(Math.max(0, serverY - p.bot) / step) + 1;
            int need = Math.max(windowWait, chargeWait);
            boolean woundUp = serverY >= diveStart - 0.05;
            if (!woundUp) {
                pRise(serverY, diveStart, name, String.format("winding up (win %dt · chg %.0f%%)", windowWait, charge * 100));
                return;
            }
            if (need > diveTicks) {
                oMove = false; oKind = "wound";
                status = String.format("GUILLOTINE %s · wound up · window %dt · charge %.0f%% · parked %+.1f", name, windowWait, charge * 100, offset);
                return;
            }
            pPhase = PPhase.Dive; pDiveTicks = 0; pFdEst = 0;
            if (sneak && !serverOff) {
                serverGliding = Boolean.TRUE;
                unglide();
                serverOff = true;
            }
        }

        double dip = safeDipDown(serverY, Math.min(step, serverY - p.bot));
        if (dip < 0.3) {
            pPhase = PPhase.Charge; pDiveTicks = 0; pFdEst = 0;
            if (serverOff) pReglideIn = Math.max(pReglideIn, 2);
            oKind = "recycle";
            status = String.format("GUILLOTINE %s · dive ended early (blocked/floor) - winding up again", name);
            return;
        }
        oMove = true; oDy = -dip;
        pDiveTicks++;
        pFdEst = sneak ? pFdEst + dip
            : (pDiveTicks <= CLAMP_RELEASE_TICKS ? Math.min(1.0 + dip, 2.5) : pFdEst + dip);

        double afterY = serverY - dip;
        boolean inBand = afterY <= p.band.top() + 1e-6 && afterY >= p.band.bot() - 1e-6;
        boolean atFloor = afterY <= p.bot + 0.05;
        boolean banked = pFdEst >= bankFd.get() - 1e-6;

        if (inBand && (banked || atFloor) && windowWait == 0 && chargeReady && pFdEst > 1.5 + 1e-6) {
            pNoToggle = sneak;
            if (sneak) pReglideIn = 4;
            pStrike(charge, "GUILLOTINE", name);
            return;
        }
        if (atFloor) {
            pPhase = PPhase.Charge; pDiveTicks = 0; pFdEst = 0;
            if (serverOff) pReglideIn = Math.max(pReglideIn, 2);
            oKind = "recycle";
            status = String.format("GUILLOTINE %s · bottom (win %dt · chg %.0f%%) - winding up again", name, windowWait, charge * 100);
            return;
        }
        oKind = "dive";
        status = String.format("GUILLOTINE %s · diving%s · fd≈%.1f/%.0f · charge %.0f%% · parked %+.1f", name, sneak ? " (unglided)" : "", pFdEst, bankFd.get(), charge * 100, offset + oDy);
    }

    private boolean oDip(OPlan p, double serverY, double dip, int next, String name) {
        double ny = serverY - dip;
        boolean bottom = ny < p.bot - 1e-6;
        if (bottom || !oColumnFree(ny, serverY)) {
            oPhase = OPhase.Rise;
            oFill = 0;
            double to = bottom ? oRiseTarget(p, next) : p.top;
            if (serverY < to - 1e-6) oRise(serverY, to, name, bottom ? "bottom · rising" : "blocked · rising");
            else { oKind = "hold"; status = String.format("OCCAM %s · %s", name, bottom ? "bottom" : "dive blocked"); }
            return false;
        }
        oMove = true;
        oDy = -dip;
        return true;
    }

    private void oRise(double serverY, double to, String name, String what) {
        double dy = Math.min(occamRise.get(), to - serverY);
        if (!oColumnFree(serverY, serverY + dy)) { oKind = "hold"; status = String.format("OCCAM %s · blocked above", name); return; }
        oMove = true;
        oDy = dy;
        oKind = "rise";
        status = String.format("OCCAM %s · %s %.1f · parked %+.1f", name, what, to - serverY - dy, offset + dy);
    }

    private void oIdle() {
        oPhase = OPhase.Descend;
        oFill = 0;
        pPhase = PPhase.Charge;
        pDiveTicks = 0;
        pFdEst = 0;
        pNoToggle = false;
        if (serverOff && occam()) { serverGliding = Boolean.FALSE; reglide(); serverOff = false; pReglideIn = 0; }
        if (Math.abs(offset) < 1e-9) { oKind = "idle"; return; }
        if (++lostTicks <= (fMoving ? 60 : 20)) { oKind = "hold"; return; }
        oMove = true;
        oKind = "home";
        double homeDelta = mc.player.getY() - oStartY;
        oDy = MathHelper.clamp(homeDelta, -occamDive.get(), occamRise.get());
    }

    private void occamPost() {
        if (oMove && !posSentThisTick) sendPos(offset);
        if (!oSwing || target == null) return;
        if (oRung == 0) oWindowTick = oTick;
        oRung++;
        if (pNoToggle) {

            attackWithMace(target);
            pNoToggle = false;
        } else {

            if (power.get() != Power.Off) serverGliding = Boolean.TRUE;
            unglide();
            attackWithMace(target);
            reglide();
        }
        telSwings++;
        sinceSmash = 0;
        chargeTicks = 0;
        if (pStrikeBig) {
            if (chatDebug.get()) info("%s SMASH · charge %.0f%% · fd≈%.1f (watch dmg) · +bonus≈%d", power.get() == Power.Guillotine ? "GUILLOTINE" : "HAYMAKER", pStrikeCharge * 100, pStrikeFd, bonusFor(pStrikeFd));
            pStrikeBig = false;
        }
    }

    private int bonusFor(double fd) {
        if (fd <= 3) return (int) Math.round(4 * fd);
        if (fd <= 8) return (int) Math.round(12 + 2 * (fd - 3));
        return (int) Math.round(fd + 14);
    }

    private void webTick() {
        if (!webEnabled.get() || target == null) return;
        if (webPlayersOnly.get() && !(target instanceof PlayerEntity)) return;
        if (webDelayLeft > 0) { webDelayLeft--; return; }
        if (webWhen.get() == WebWhen.Airborne && target.isOnGround()) return;
        if (webWhen.get() == WebWhen.Gliding && !(target instanceof LivingEntity le && le.isGliding())) return;

        FindItemResult webs = InvUtils.findInHotbar(Items.COBWEB);
        if (!webs.found()) {
            if (chatDebug.get() && webNoItemThrottle++ % 200 == 0) info("webbing: no cobwebs in hotbar");
            return;
        }

        double lead = webLead.get();
        int bx = (int) Math.floor(target.getX() + (target.getX() - target.lastX) * lead);
        int by = (int) Math.floor(target.getY() + (target.getY() - target.lastY) * lead);
        int bz = (int) Math.floor(target.getZ() + (target.getZ() - target.lastZ) * lead);

        int placed;
        if (webCells.get() == WebCells.Cube) {

            int budget = Math.max(websPerTick.get(), 6);
            double mvx = target.getX() - target.lastX, mvz = target.getZ() - target.lastZ;
            placed = cubeWeb(bx, by, bz, mvx, mvz, webs, budget);
        } else {
            int budget = websPerTick.get();
            placed = tryWeb(bx, by, bz, webs, budget);
            if (webCells.get() == WebCells.FeetAndHead) placed += tryWeb(bx, by + 1, bz, webs, budget - placed);
        }

        if (placed > 0) {
            telWebs += placed;
            webDelayLeft = webDelay.get();
        }
    }

    private int cubeWeb(int bx, int by, int bz, double mvx, double mvz, FindItemResult webs, int budget) {
        double mh = Math.sqrt(mvx * mvx + mvz * mvz);
        double fx = mh > 1e-3 ? mvx / mh : 0, fz = mh > 1e-3 ? mvz / mh : 0;
        List<int[]> cols = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) cols.add(new int[]{dx, dz});
        cols.sort(Comparator.comparingDouble(o -> o[0] == 0 && o[1] == 0 ? -100 : -(o[0] * fx + o[1] * fz)));
        int placed = 0;
        for (int[] o : cols) {
            for (int dy = 0; dy <= 1 && placed < budget; dy++) placed += tryWeb(bx + o[0], by + dy, bz + o[1], webs, budget - placed);
            if (placed >= budget) break;
        }
        return placed;
    }

    private int tryWeb(int x, int y, int z, FindItemResult webs, int budget) {
        if (budget <= 0) return 0;
        BlockPos pos = new BlockPos(x, y, z);
        if (!mc.world.getBlockState(pos).isReplaceable()) return 0;

        double ex = lastWirePos != null ? lastWirePos.x : mc.player.getX();
        double ey = ghostY() + EYE_GLIDING;
        double ez = lastWirePos != null ? lastWirePos.z : mc.player.getZ();
        double dx = Math.max(Math.max(x - ex, ex - (x + 1)), 0);
        double dy = Math.max(Math.max(y - ey, ey - (y + 1)), 0);
        double dz = Math.max(Math.max(z - ez, ez - (z + 1)), 0);
        if (dx * dx + dy * dy + dz * dz > webRange.get() * webRange.get()) return 0;

        Box cell = new Box(x, y, z, x + 1, y + 1, z + 1);
        double m = webSelfClearance.get();
        if (cell.intersects(mc.player.getBoundingBox().expand(m))) return 0;
        if (cell.intersects(mc.player.getBoundingBox().offset(ex - mc.player.getX(), ghostY() - mc.player.getY(), ez - mc.player.getZ()).expand(m))) return 0;

        if (!webAirPlace.get() && !hasSupport(pos)) return 0;

        return BlockUtils.place(pos, webs, false, 0, false, false, true) ? 1 : 0;
    }

    private boolean hasSupport(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState s = mc.world.getBlockState(webScanPos.set(pos, d));
            if (!s.isAir() && s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private double webFloorY(Box victim) {
        double res = Double.NEGATIVE_INFINITY;
        int yLo = (int) Math.floor(victim.minY) - 3, yHi = (int) Math.floor(victim.maxY) + 1;
        double x1 = mc.player.getX(), z1 = mc.player.getZ();
        double x0 = havePrevRealY ? sweepX0 : x1, z0 = havePrevRealY ? sweepZ0 : z1;
        for (int cx = (int) Math.floor(Math.min(x0, x1) - 0.31); cx <= (int) Math.floor(Math.max(x0, x1) + 0.31); cx++) {
            for (int cz = (int) Math.floor(Math.min(z0, z1) - 0.31); cz <= (int) Math.floor(Math.max(z0, z1) + 0.31); cz++) {
                for (int cy = yHi; cy >= yLo; cy--) {
                    double need = cy + 1 + 0.05;
                    if (need <= res) break;
                    if (mc.world.getBlockState(webScanPos.set(cx, cy, cz)).isOf(Blocks.COBWEB)) { res = need; break; }
                }
            }
        }
        return res;
    }

    private void goHome(String why) {
        phase = Phase.Ascend;
        riseCeil = 0;
        status = why + " → rising home";
    }

    private void abort() {
        strikeNow = false;
        cooldown = Math.max(cooldown, 5);
        if (offset < 0) goHome("abort");
        else { phase = Phase.Idle; status = "abort"; }
    }

    private void hoverAdvance() {
        switch (phase) {
            case Idle -> {
                if (cooldown > 0) { status = "cooldown " + cooldown + "t"; return; }
                if (!(lockTarget.get() && target != null && valid(target, false))) target = TargetUtils.get(this::acquire, priority.get());
                if (target == null) { if (offset < 0) goHome("no target"); else report("no target in reach below/near you"); return; }
                if (!haveMace()) { if (offset < 0) goHome("no mace"); else report("no mace in hotbar"); return; }
                if (!charged()) { report("charging"); return; }

                if (!mc.player.isGliding()) {

                    Plan p = plan(target, 0);
                    if (p != null && mc.player.fallDistance > 1.5 && p.botOff <= 0 && 0 <= p.topOff) { strikeNow = true; status = "falling strike"; }
                    else report("not gliding: fall onto them (fd " + String.format("%.1f", mc.player.fallDistance) + ")");
                    return;
                }

                Plan p = plan(target, offset);
                if (p == null) { report("can't reach (hover 2-" + (int) maxDive.get().doubleValue() + " blocks above them, within " + reach.get() + " sideways)"); return; }
                phase = Phase.Descend;
                cycleTop = offset;
                strikeOffset = p.strikeOff;
                swings = 0;
                descend(p);
            }
            case Descend -> {
                if (target == null || !target.isAlive() || (target instanceof LivingEntity le && le.isDead())) { abort(); return; }
                Plan p = plan(target, cycleTop);
                if (p == null) { abort(); return; }
                strikeOffset = p.strikeOff;
                descend(p);
            }
            case Ascend -> {
                if (riseTo.get() == RiseTo.ReachTop && riseCeil < 0) {

                    Plan p = (target != null && target.isAlive()) ? plan(target, offset) : null;
                    riseCeil = p == null ? 0 : Math.min(0, p.topOff);
                }
                offset = Math.min(riseCeil, offset + riseStep.get());
                status = String.format("rising %.1f", -offset);
            }
        }
    }

    private void descend(Plan p) {
        double next = Math.max(strikeOffset, offset - step.get());
        boolean blocked = false;
        if (!free(next)) {

            blocked = true;
            double o = offset;
            while (o - 0.1 >= next && free(o - 0.1)) o -= 0.1;
            next = o;
        }
        if (next > offset - 0.55) {
            abort();
            status = "blocked below";
            return;
        }
        offset = next;

        double dropped = cycleTop - offset;
        boolean inBand = offset <= p.topOff + 1e-6 && offset >= p.botOff - 1e-6;
        boolean reached = offset <= strikeOffset + 1e-6 || blocked;
        boolean banked = dropped > 1.5 + 1e-6 && dropped >= minDrop() - 1e-6;
        if (inBand && banked && (!fullChargeOnly.get() || reached)) { strikeNow = true; swings++; }
        status = String.format("diving %.1f/%.1f · %d swings", dropped, cycleTop - strikeOffset, swings);

        if (reached) {
            phase = Phase.Ascend;
            riseCeil = riseTo.get() == RiseTo.ReachTop ? Math.min(0, p.topOff) : 0;
            summaryPending = true;
        }
    }

    private void hoverPost() {
        if (phase != Phase.Idle && !posSentThisTick) sendPos(offset);
        if (strikeNow) { hoverStrike(); strikeNow = false; }
        if (carryMode() && phase == Phase.Descend && mc.player.isGliding() && !serverOff) {
            unglide();
            serverOff = true;
        }
        if (phase == Phase.Ascend) {
            if (serverOff) { reglide(); serverOff = false; }
            if (offset >= riseCeil - 1e-6) {
                if (summaryPending) { summary(); summaryPending = false; }
                phase = Phase.Idle;
                cooldown = Math.max(cooldown, cycleDelay.get());
                status = offset < 0 ? "holding at reach top" : "idle";
            }
        }
    }

    private void hoverStrike() {
        if (target == null) return;
        boolean gliding = mc.player.isGliding();
        boolean carry = carryMode();

        if (gliding && !carry) unglide();
        else if (gliding && !serverOff) { unglide(); serverOff = true; }

        attackWithMace(target);

        if (gliding && !carry) reglide();
        telSwings++;

        if (!gliding && chatDebug.get()) info("strike %s · real fall %.1f ≈ +%.0f", EntityUtils.getName(target), mc.player.fallDistance, bonus(mc.player.fallDistance));
    }

    private void summary() {
        if (!chatDebug.get() || target == null) return;
        double drop = cycleTop - strikeOffset;
        info("SMASH×%d %s · drop %.1f · %s", swings, EntityUtils.getName(target), drop, carryMode() ? "carry (un-glided entity ticks)" : "glide-toggle per swing (clamped)");
    }

    private void report(String s) {
        status = s;
        if (chatDebug.get() && debugThrottle++ % 40 == 0) info(s);
    }

    private static double bonus(double fd) {
        if (fd <= 1.5) return 0;
        if (fd <= 3) return 4 * fd;
        if (fd <= 8) return 12 + 2 * (fd - 3);
        return fd + 14;
    }

    @Override
    public String getInfoString() {
        return fMoving ? status + " · following" : status;
    }

    public static boolean keepLookArmed() {
        return keepLookArmed;
    }

    public static boolean consumeKeepLook() {
        boolean a = keepLookArmed;
        keepLookArmed = false;
        return a;
    }
}
