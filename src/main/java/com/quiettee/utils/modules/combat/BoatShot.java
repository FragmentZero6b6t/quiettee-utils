package com.quiettee.utils.modules.combat;

import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.mixin.PlayerMoveC2SPacketAccessor;
import com.quiettee.utils.modules.movement.BoatPhase;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.mixin.ProjectileInGroundAccessor;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BoatShot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Target Lock");
    private final SettingGroup sgFollow = settings.createGroup("Follow and Fire");
    private final SettingGroup sgWeb = settings.createGroup("Web Capture");
    private final Setting<Boolean> autoCombat = sgFollow.add(new BoolSetting.Builder()
        .name("auto-combat").description("Lock, follow, web and fire until the target dies.")
        .defaultValue(true).build());
    private final Setting<Boolean> quickCapture = sgWeb.add(new BoolSetting.Builder()
        .name("quick-capture").description("Retreat once the target is stuck in webs.")
        .defaultValue(true).build());
    private final Setting<Boolean> followInventory = sgFollow.add(new BoolSetting.Builder()
        .name("follow-in-inventory").description("Keep following with your inventory open.")
        .defaultValue(true).build());
    private final Setting<Boolean> seekCoverAngle = sgFollow.add(new BoolSetting.Builder()
        .name("seek-clear-angle").description("Find a clear firing angle around cover.")
        .defaultValue(true).build());
    private final Setting<Boolean> shootAfterCaptureMiss = sgWeb.add(new BoolSetting.Builder()
        .name("shoot-after-capture-miss").description("Still shoot when a web capture fails.")
        .defaultValue(true).build());
    private final Setting<Boolean> combatStatus = sgFollow.add(new BoolSetting.Builder()
        .name("combat-status").description("Show combat status above the hotbar.")
        .defaultValue(true).build());
    private final Setting<Boolean> lockTarget = sgAim.add(new BoolSetting.Builder()
        .name("lock-target").description("Keep the target until you unlock it.")
        .defaultValue(true).build());
    private final Setting<Keybind> lockTargetKey = sgAim.add(new KeybindSetting.Builder()
        .name("lock-target-key").description("Lock or unlock the target nearest the crosshair.")
        .defaultValue(Keybind.none()).action(this::toggleTargetLock).build());
    private final Setting<Boolean> autoFire = sgFollow.add(new BoolSetting.Builder()
        .name("auto-fire").description("Draw and fire automatically at the locked target.")
        .defaultValue(true).build());
    private final Setting<Boolean> waitForWeb = sgWeb.add(new BoolSetting.Builder()
        .name("wait-for-web-stop").description("Wait until the target is slowed by webs.")
        .defaultValue(true).build());
    private final Setting<Boolean> adaptivePrediction = sgAim.add(new BoolSetting.Builder()
        .name("adaptive-prediction").description("Predict movement from ping and server TPS.")
        .defaultValue(true).build());
    private final Setting<Boolean> powerShots = sgGeneral.add(new BoolSetting.Builder()
        .name("power-shots").description("Try a stronger single burst per shot.")
        .defaultValue(true).build());
    private final Setting<Double> powerShotSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("power-shot-speed").description("Vertical burst for power shots.")
        .defaultValue(19.5).range(9.9,19.5).sliderRange(9.9,19.5).visible(powerShots::get).build());
    private final Setting<Boolean> combatCrystalGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("crystal-guard-only-in-combat").description("Only avoid crystals while fighting.")
        .defaultValue(true).build());
    private final Setting<Boolean> webCube = sgWeb.add(new BoolSetting.Builder()
        .name("web-cube").description("Web the target while following.")
        .defaultValue(true).build());
    private final Setting<BoatShotWebShape.Pattern> webPattern = sgWeb.add(new EnumSetting.Builder<BoatShotWebShape.Pattern>()
        .name("web-pattern").description("Flight Net or the full Cube.")
        .defaultValue(BoatShotWebShape.Pattern.FlightNet).visible(webCube::get).build());
    private final Setting<Boolean> webAirPlace = sgWeb.add(new BoolSetting.Builder()
        .name("web-air-place").description("Allow placing webs in mid air.")
        .defaultValue(true).visible(webCube::get).build());
    private final Setting<Boolean> autoRelease = sgFollow.add(new BoolSetting.Builder()
        .name("auto-release").description("Hold Use for continuous aimed shots.")
        .defaultValue(false).build());
    private final Setting<Boolean> rapidFire = sgFollow.add(new BoolSetting.Builder()
        .name("rapid-fire").description("Shorter draws when a hit is possible.")
        .defaultValue(true).visible(autoRelease::get).build());
    private final Setting<Integer> rapidDrawTicks = sgFollow.add(new IntSetting.Builder()
        .name("rapid-draw-ticks").description("Minimum short draw in ticks.")
        .defaultValue(8).range(3,20).sliderRange(3,20).visible(() -> autoRelease.get() && rapidFire.get()).build());
    private final Setting<Integer> chargeTicks = sgFollow.add(new IntSetting.Builder()
        .name("charge-ticks").description("Minimum automatic draw in ticks.")
        .defaultValue(23).min(20).max(80).sliderRange(20,40).visible(() -> autoRelease.get() && !rapidFire.get()).build());
    private final Setting<Boolean> follow = sgFollow.add(new BoolSetting.Builder()
        .name("follow-target").description("Follow above the locked target.")
        .defaultValue(false).build());
    private final Setting<Double> followHeight = sgFollow.add(new DoubleSetting.Builder()
        .name("follow-height").description("Boat height above the target.")
        .defaultValue(36).min(12).max(160).sliderRange(12,100).visible(() -> autoCombat.get() || follow.get()).build());
    private final Setting<Boolean> autoAim = sgAim.add(new BoolSetting.Builder()
        .name("auto-aim").description("Aim shots for you. Camera stays free.")
        .defaultValue(true).build());
    private final Setting<Boolean> preferPlayers = sgAim.add(new BoolSetting.Builder()
        .name("prefer-players").description("Prefer players over mobs.")
        .defaultValue(true).build());
    private final Setting<Boolean> crosshairSelection = sgAim.add(new BoolSetting.Builder()
        .name("crosshair-selection").description("Lock the entity nearest your crosshair.")
        .defaultValue(true).build());
    private final Setting<Double> crosshairAngle = sgAim.add(new DoubleSetting.Builder()
        .name("crosshair-angle").description("Max crosshair angle for a new lock.")
        .defaultValue(30).range(1,89).sliderRange(1,60).visible(crosshairSelection::get).build());
    private final Setting<Double> targetRange = sgAim.add(new DoubleSetting.Builder()
        .name("target-range").description("Max distance for a new target.")
        .defaultValue(160).min(8).max(256).sliderRange(16, 200).build());
    private final Setting<Double> targetAngle = sgAim.add(new DoubleSetting.Builder()
        .name("target-angle").description("Max angle from your crosshair.")
        .defaultValue(100).min(5).max(180).sliderRange(10, 180).build());
    private final Setting<Double> extraLead = sgAim.add(new DoubleSetting.Builder()
        .name("extra-lead-ticks").description("Manual lead ticks for network delay.")
        .defaultValue(2).min(0).max(10).sliderRange(0, 6).build());
    private final Setting<Boolean> automaticLead = sgAim.add(new BoolSetting.Builder()
        .name("automatic-latency-lead").description("Estimate lead from your ping.")
        .defaultValue(true).build());
    private final Setting<Double> leadAdjustment = sgAim.add(new DoubleSetting.Builder()
        .name("lead-adjustment").description("Extra lead in ticks, positive or negative.")
        .defaultValue(0).range(-3,3).sliderRange(-3,3).build());
    private final Setting<Boolean> showTarget = sgAim.add(new BoolSetting.Builder()
        .name("show-target").description("Outline the locked target.")
        .defaultValue(true).build());
    private final Setting<meteordevelopment.meteorclient.utils.render.color.SettingColor> targetColor = sgAim.add(new ColorSetting.Builder()
        .name("target-color").description("Target outline colour.")
        .defaultValue(new meteordevelopment.meteorclient.utils.render.color.SettingColor(255,205,40,255)).visible(showTarget::get).build());
    private final Setting<Integer> targetFill = sgAim.add(new IntSetting.Builder()
        .name("target-fill").description("Target fill opacity.")
        .defaultValue(55).range(0,180).sliderRange(0,120).visible(showTarget::get).build());
    private final Setting<Boolean> hitInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("hit-info").description("Report confirmed arrow hits in chat.")
        .defaultValue(true).build());
    private final Setting<Double> burstSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("burst-speed").description("Vertical burst per shot. 9.9 is the limit.")
        .defaultValue(9.9).min(0.1).max(BoatShotCycle.MAX_BURST).sliderRange(1, BoatShotCycle.MAX_BURST).build());
    private final Setting<Double> minimumPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("minimum-pitch").description("Minimum manual aim angle.")
        .defaultValue(60).min(15).max(89).sliderRange(15, 89).build());
    private final Setting<Boolean> returnHome = sgGeneral.add(new BoolSetting.Builder()
        .name("return-to-height").description("Return to height after the arrow clears.")
        .defaultValue(true).build());
    private final Setting<Boolean> fullDraw = sgGeneral.add(new BoolSetting.Builder()
        .name("full-draw-only").description("Only fire manual shots at full draw.")
        .defaultValue(true).build());
    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("shot-info").description("Report bursts and arrow speed in chat.")
        .defaultValue(true).build());
    private final Setting<Boolean> debugFile = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-file").description("Write a log to .minecraft/boat-shot.")
        .defaultValue(true).build());

    private static final String[] CONFLICTS = { "shot-boost", "bow-spam", "bow-aimbot", "quiver", "fusillade" };
    private final List<Module> paused = new ArrayList<>();
    private final BoatShotCycle cycle = new BoatShotCycle();
    private final BoatShotMount mount = new BoatShotMount();
    private final BoatShotEvents eventGate = new BoatShotEvents();
    private final BoatShotFeedback feedback = new BoatShotFeedback();
    private final BoatShotGuard guard = new BoatShotGuard();
    private final BoatShotUseBudget useBudget = new BoatShotUseBudget();
    private final BoatShotWeb web = new BoatShotWeb(guard,useBudget);
    private final BoatShotPursuit.Navigator navigator = new BoatShotPursuit.Navigator();
    private final BoatShotPrediction prediction = new BoatShotPrediction();
    private final BoatShotDrawLock drawLock = new BoatShotDrawLock();
    private final AtomicBoolean corrected = new AtomicBoolean();
    private final ConcurrentLinkedQueue<EntitySpawnS2CPacket> spawns = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Integer> arrows = new LinkedHashMap<>();
    private final Map<Integer,Boolean> seenArrows = new LinkedHashMap<>();
    private ClientPlayerEntity player;
    private ClientWorld world;
    private ClientPlayNetworkHandler network;
    private volatile ClientConnection connection;
    private AbstractBoatEntity boat;
    private BoatPhase phase;
    private boolean enabledPhase, sendingRelease, lookSent, releaseSent, automaticReleasing, queuedAutomatic;
    private Vec3d shotEyeOffset = Vec3d.ZERO;
    private int queuedAt;
    private int tick, slot, drawTicks, releaseTick, shotArrow = -1;
    private int lastBoostTick = -100;
    private double lastBoost;
    private volatile int playerId = -1;
    private float shotYaw, shotPitch;
    private Vec3d lastWire, shotOrigin;
    private LivingEntity target;
    private Vec3d targetPosition, targetVelocity = Vec3d.ZERO;
    private PlayerActionC2SPacket pending;
    private PlayerMoveC2SPacket primePacket, lookPacket;
    private ItemStack shotStack = ItemStack.EMPTY;
    private String status = "enable before boarding";
    private PrintWriter log;
    private int nextAutoTick, lastWaitLog = -100;
    private String aimFailure = "", obstruction = "";
    private String travelBlockReason = "movement blocked";
    private double predictedLead = 2;
    private boolean crystalsProtected;
    private boolean ownedAutoDraw, suppressAcquire;
    private boolean powerRejected;
    private double escapeY=Double.NaN;
    private Vec3d escapeAway=Vec3d.ZERO;
    private int escapeUntil, chargeRequired;
    private String lastFlow="";
    private boolean coverMode;
    private BoatShotCover.Route coverRoute;
    private int coverIndex, nextCoverSearch;
    private record Aim(double delta, float yaw, float pitch) {}

    public BoatShot() {
        super(QuietteeUtils.CATEGORY, "boat-shot", "Fire a bow during a vertical boat burst.");
    }

    public static BoatShot active() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        BoatShot module = modules.get(BoatShot.class);
        return module != null && module.isActive() ? module : null;
    }

    public boolean singleUpdateMovement() { return pending!=null && !web.busy(); }
    private boolean automaticFiring() { return autoCombat.get() || autoFire.get(); }
    private boolean follows() { return autoCombat.get() || follow.get(); }
    private boolean aimEnabled() { return autoCombat.get() || autoAim.get(); }
    private boolean persistentLock() { return autoCombat.get() || lockTarget.get(); }
    public boolean retainAutomaticDraw(PlayerEntity owner) {
        return BoatShotAutomation.retainDraw(ownedAutoDraw, owner==player && player!=null && player.isUsingItem()
            && player.getActiveHand()==Hand.MAIN_HAND && player.getActiveItem().getItem() instanceof BowItem,
            driving() && automaticFiring() && mc.currentScreen==null && !manualMovement() && !web.busy()
                && !Double.isFinite(escapeY) && validTarget(target), automaticReleasing || sendingRelease);
    }
    private void acquiredTarget() {
        if(target==null)return;
        web.rearm(); navigator.reset();
        if(autoCombat.get() && !(player.getMainHandStack().getItem() instanceof BowItem)) {
            var bow=InvUtils.findInHotbar(stack->stack.getItem() instanceof BowItem);
            if(bow.isHotbar())InvUtils.swap(bow.slot(),false);
        }
        status="approaching target";
    }
    private double aimBurst() {
        double requested=coverMode?Math.min(2,shotBurst()):shotBurst();
        if(aimEnabled() && targetPosition!=null && target!=null && player!=null && observedTargetBox().maxY<player.getEyeY())
            return BoatShotAutomation.downwardBurst(requested,player.getEyeY(),observedTargetBox().maxY);
        return requested;
    }
    private boolean webEligible() {
        return targetPosition!=null && world!=null && phase!=null
            && BoatShotAutomation.cubeFits((int)Math.floor(targetPosition.y),world.getBottomY(),world.getTopYInclusive())
            && Math.floor(targetPosition.y)+BoatShotWebShape.HOVER<=phase.captureCeiling(boat);
    }
    private int bowTicks(int clientTicks) {
        return adaptivePrediction.get() || automaticFiring()
            ? Math.max(0,(int)Math.floor(clientTicks*BoatShotPrediction.serverTicksPerClientTick(serverTps()))) : clientTicks;
    }
    private double aimLead() {
        double lead=predictedLead+leadAdjustment.get();
        return adaptivePrediction.get()?Math.clamp(lead,0,10):BoatShotPrediction.collisionLead(lead);
    }
    private double shotBurst() {
        return powerRejected?Math.min(9.9,burstSpeed.get()):powerShots.get()?Math.max(burstSpeed.get(),powerShotSpeed.get()):burstSpeed.get();
    }
    private void stopAutomaticDraw() {
        if(ownedAutoDraw && sameSession() && player.isUsingItem() && player.getActiveItem().getItem() instanceof BowItem) {
            cancelDraw(); player.clearActiveItem();
        }
        ownedAutoDraw=false;
    }
    private void unlockTarget() {
        if(!isActive())return;
        stopAutomaticDraw(); abort("target unlocked",true);
        if(sameSession() && player.isUsingItem() && player.getActiveItem().getItem() instanceof BowItem) { cancelDraw(); player.clearActiveItem(); }
        target=null; targetPosition=null; targetVelocity=Vec3d.ZERO; prediction.resetMotion();
        web.retreat(); navigator.reset(); resetCover(); suppressAcquire=true;
        escapeY=Double.NaN;
        info("Target unlocked; automatic firing stopped.");
    }
    private void toggleTargetLock() {
        if(!isActive() || !driving())return;
        if(target!=null) { unlockTarget(); return; }
        if(web.busy() || cycle.stage()!=BoatShotCycle.Stage.IDLE || Double.isFinite(escapeY))return;
        target=chooseTarget(); targetPosition=target==null?null:observedPosition(target);
        targetVelocity=Vec3d.ZERO; prediction.resetMotion();
        acquiredTarget();
    }

    @Override public void onActivate() {
        eventGate.reset();
        resetSession();
        phase = Modules.get().get(BoatPhase.class);
        enabledPhase = phase != null && !phase.isActive();
        if (enabledPhase) phase.toggle();
        info("Lock once with lock-target-key or a bow draw. Auto combat approaches, webs and fires hands-free. Unlock to stop; steering pauses it.");
    }

    @Override public void onDeactivate() {
        stopAutomaticDraw();
        abort("disabled", true);
        resetSession();
        restoreConflicts();
        if (enabledPhase && phase != null && phase.isActive()) phase.toggle();
        enabledPhase = false;
    }

    @EventHandler private void onGameLeft(GameLeftEvent event) { resetSession(); restoreConflicts(); }

    private boolean sameSession() {
        return player != null && player == mc.player && world == mc.world && network == mc.getNetworkHandler()
            && network.getConnection() == connection;
    }

    private boolean driving() {
        return sameSession() && player.isAlive() && boat != null && !boat.isRemoved()
            && player.getRootVehicle() == boat && boat.getControllingPassenger() == player;
    }

    private boolean sameBow() {
        return sameSession() && player.isAlive() && player.getInventory().getSelectedSlot() == slot
            && ItemStack.areItemsAndComponentsEqual(shotStack, player.getMainHandStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST - 1)
    private void onTick(TickEvent.Pre event) {
        if (mc.player != null && !eventGate.tick(mc.player, mc.player.age)) return;
        if (!sameSession()) {
            restoreConflicts();
            resetSession();
            if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
            player = mc.player;
            world = mc.world;
            network = mc.getNetworkHandler();
            connection = network.getConnection();
            playerId = player.getId();
            openLog();
        }
        tick++;
        if (corrected.getAndSet(false)) {
            stopAutomaticDraw();
            if (boat != null && lastBoost >= 10 && tick - lastBoostTick <= 12 && !Input.isPressed(mc.options.sneakKey)) {
                powerRejected=true;
                warning("Correction after a %.1f-block burst. Shot power reduced to 9.9 until reboarding.", lastBoost);
                record("BURST-CORRECTION tick=%d attempted=%.3f", tick, lastBoost);
                lastBoost = 0;
            }
            abort("server correction", true);
            web.cancel(); navigator.reset(); resetCover();
            nextAutoTick = tick + 20;
            if (boat != null) record("CORRECTION tick=%d seat=%s; the rider's ground flag is untouched by corrections", tick, boat.getId());
            lastWire = null;
        }
        AbstractBoatEntity current = player.getRootVehicle() instanceof AbstractBoatEntity b
            && b.getControllingPassenger() == player ? b : null;
        if (current != boat) {
            stopAutomaticDraw();
            abort("seat changed", true);
            web.cancel(); navigator.reset(); resetCover();
            target = null;
            targetPosition = null;
            targetVelocity = Vec3d.ZERO;
            prediction.resetMotion();
            boat = current;
            escapeY=Double.NaN;
            if(current!=null)powerRejected=false;
            lastWire = null;
            mount.seat(boat == null ? -1 : boat.getId(), tick);
            record("SEAT boat=%s primed=%b", boat == null ? "none" : boat.getId(), mount.ready());
        }
        observeArrows();
        feedback.tick(world, playerId, tick, message -> { if (hitInfo.get()) info("%s", message); }, message -> record("%s", message));
        if (!driving()) { abort("not driving", true); status = "board the driver's seat"; restoreConflicts(); return; }
        updateTarget();
        predictedLead = adaptivePrediction.get() || automaticLead.get() ? prediction.updateLead(PlayerUtils.getPing(),serverTps(),prediction.sampleAge(tick)) : extraLead.get();
        crystalsProtected = protectCrystals();
        guard.refresh(world,player,boat,target,crystalsProtected);
        checkCeiling();
        updateCover();
        pauseConflicts();
        if (cycle.stage() != BoatShotCycle.Stage.IDLE && (phase == null || !phase.shotReady(boat))) abort("Boat Phase paused", true);
        if (pending != null && (!sameBow() || cycle.expired(tick) || mc.currentScreen != null
            || Input.isPressed(mc.options.sneakKey) || queuedAutomatic && manualMovement() || phase == null || !phase.shotReady(boat))) abort("shot interrupted", true);
        if (cycle.stage() == BoatShotCycle.Stage.RETURNING && (manualMovement() || mc.currentScreen != null)) abort("return canceled", false);
        if (cycle.stage() == BoatShotCycle.Stage.IDLE) status = phase == null || !phase.isActive()
            ? "enable Boat Phase" : mount.ready() ? target == null ? "ready" : "locked: " + target.getName().getString() : "reboard with Boat Shot enabled";
        String capturePause = Double.isFinite(escapeY) ? "retreating from height limit" : !follows() ? "follow is off" : !aimEnabled() ? "auto aim is off"
            : !(player.getMainHandStack().getItem() instanceof BowItem) ? "select the bow"
            : manualMovement() ? "manual steering" : mc.currentScreen != null ? "screen open"
            : cycle.stage()!=BoatShotCycle.Stage.IDLE ? "shot in progress"
            : phase==null || !phase.shotReady(boat) ? "Boat Phase paused" : !mount.ready() ? "reboard to prime"
            : tick<nextAutoTick ? "shot delay" : null;
        web.configure(quickCapture.get(),autoCombat.get()?4:12);
        web.pattern(webPattern.get());
        web.shootingFallback(shootAfterCaptureMiss.get());
        web.tick(tick,boat,target,targetPosition,targetVelocity,predictedLead+leadAdjustment.get(),webCube.get() && webEligible() && !coverMode,capturePause,webAirPlace.get(),phase,
            this::cancelDraw,message -> record("%s",message));
        if(web.busy()) status=web.status();
        else automaticDraw();
        if(Double.isFinite(escapeY) && !status.startsWith("disengaged:"))status="disengaging: descending from height limit";
        if(!status.equals(lastFlow)) {record("FLOW tick=%d state=%s",tick,status);lastFlow=status;}
    }

    private boolean manualMovement() {
        if(inventoryFollowAllowed())return false;
        return Input.isPressed(mc.options.forwardKey) || Input.isPressed(mc.options.backKey)
            || Input.isPressed(mc.options.leftKey) || Input.isPressed(mc.options.rightKey)
            || Input.isPressed(mc.options.jumpKey) || Input.isPressed(mc.options.sprintKey)
            || Input.isPressed(mc.options.sneakKey);
    }

    public boolean inventoryFollowAllowed() {
        return followInventory.get() && mc.currentScreen instanceof InventoryScreen
            && follows() && aimEnabled() && driving() && validTarget(target);
    }

    private void checkCeiling() {
        if(!follows() || !validTarget(target) || phase==null || !phase.shotReady(boat) || manualMovement() || mc.currentScreen!=null)return;
        Box body=observedTargetBox();
        if(!BoatShotAutomation.ceilingDanger(boat.getY(),body.minY,body.maxY,phase.captureCeiling(boat),shotBurst()+6))return;
        stopAutomaticDraw(); abort("enemy above at height limit",true); web.cancel(); navigator.reset();
        escapeY=Math.max(world.getBottomY()+2,boat.getY()-45);escapeUntil=tick+100;
        escapeAway=boat.getEntityPos().subtract(targetPosition).multiply(1,0,1).normalize();
        target=null;targetPosition=null;targetVelocity=Vec3d.ZERO;prediction.resetMotion();suppressAcquire=true;
        record("DISENGAGE tick=%d reason=enemy above at height limit",tick);
        info("Disengaging: enemy above at the height limit. Descending; lock again to resume.");
    }

    private Vec3d escapeMovement() {
        if(!Double.isFinite(escapeY))return null;
        if(manualMovement() || tick>escapeUntil || boat.getY()<=escapeY+.1) {escapeY=Double.NaN;return Vec3d.ZERO;}
        double dy=-Math.min(phase.captureVerticalLimit(),boat.getY()-escapeY);
        for(Vec3d step:new Vec3d[]{new Vec3d(0,dy,0),escapeAway.multiply(4.75).add(0,dy,0),escapeAway.multiply(Math.min(9,phase.travelSpeed())).add(0,Math.max(-9,dy),0)})
            if(phase.clearTravel(boat,step)&&safeTravel(boat,step))return step;
        status="disengaged: descent obstructed";return Vec3d.ZERO;
    }

    @EventHandler(priority = EventPriority.LOWEST - 1000)
    private void onSend(PacketEvent.Send event) {
        if (!mc.isOnThread() || !sameSession() || event.connection != connection || event.isCancelled()) return;

        if (!player.hasVehicle() && event.packet instanceof PlayerInteractEntityC2SPacket interaction) {
            IPlayerInteractEntityC2SPacket access = (IPlayerInteractEntityC2SPacket) interaction;
            boolean[] interact = { false };
            interaction.handle(new PlayerInteractEntityC2SPacket.Handler() {
                @Override public void interact(Hand hand) { interact[0] = true; }
                @Override public void interactAt(Hand hand, Vec3d position) { interact[0] = true; }
                @Override public void attack() {}
            });
            if (interact[0] && access.meteor$getEntity() instanceof AbstractBoatEntity target) {
                mount.begin(target.getId(), tick);
                primePacket = new PlayerMoveC2SPacket.OnGroundOnly(false, player.horizontalCollision);
                network.sendPacket(primePacket);
                primePacket = null;
                record("PRIME tick=%d boat=%d", tick, target.getId());
            }
        }
        if (event.packet instanceof PlayerMoveC2SPacket movement && !player.hasVehicle()
            && (movement == primePacket || mount.window(tick))) {
            ((PlayerMoveC2SPacketAccessor) movement).quiettee$setOnGround(false);
        }
        if (sendingRelease) return;
        if (web.busy() && (event.packet instanceof PlayerInteractItemC2SPacket
            || event.packet instanceof PlayerActionC2SPacket a && a.getAction()==PlayerActionC2SPacket.Action.RELEASE_USE_ITEM)) {
            event.cancel();
            if(player.isUsingItem()) { cancelDraw(); player.clearActiveItem(); }
            return;
        }

        if (pending != null && BoatShotPackets.preserveQueuedRelease(event.packet,queuedAutomatic,sameBow(),slot)) {
            event.cancel(); record("DRAW-RESTART-SUPPRESSED tick=%d packet=%s",tick,event.packet.getClass().getSimpleName()); return;
        }

        if (pending != null && (event.packet instanceof PlayerInteractItemC2SPacket
            || event.packet instanceof UpdateSelectedSlotC2SPacket
            || event.packet instanceof PlayerActionC2SPacket action && action.getAction() != PlayerActionC2SPacket.Action.RELEASE_USE_ITEM)) abort("new item action", true);
        if (!(event.packet instanceof PlayerActionC2SPacket action)
            || action.getAction() != PlayerActionC2SPacket.Action.RELEASE_USE_ITEM) return;
        if (pending != null) { event.cancel(); return; }
        if (!driving() || player.getActiveHand() != Hand.MAIN_HAND
            || !(player.getActiveItem().getItem() instanceof BowItem)) return;
        if (!mount.ready()) { reject(event, "reboard with Boat Shot enabled"); return; }
        if (phase == null || !phase.shotReady(boat) || lastWire == null || corrected.get() || mc.currentScreen != null) {
            reject(event, "wait for Boat Phase to be ready"); return;
        }
        if (cycle.stage() != BoatShotCycle.Stage.IDLE) { reject(event, "wait for the previous arrow"); return; }
        int age = player.getItemUseTime();
        if (age < 3) { reject(event,"bow draw too short to spawn an arrow"); return; }
        if (fullDraw.get() && !(automaticReleasing && (automaticFiring() || rapidFire.get())) && age < 20) { reject(event, "charge the bow fully"); return; }
        float pitch = player.getPitch(), yaw = player.getYaw();
        double deltaY;
        if (aimEnabled()) {
            if (!validTarget(target)) { target = persistentLock() ? null : chooseTarget(); targetPosition = target == null ? null : observedPosition(target); targetVelocity = Vec3d.ZERO; prediction.resetMotion(); }
            if (target == null) { reject(event, "no living target in range near the crosshair"); return; }
            Aim aim = aimedShot(age);
            if (aim == null) { reject(event, aimFailure); return; }
            deltaY = aim.delta(); pitch = aim.pitch(); yaw = aim.yaw();
            record("AIM tick=%d target=%d name=%s yaw=%.2f pitch=%.2f velocity=%s lead=%.2f automatic=%b ping=%d", tick, target.getId(), target.getName().getString(), yaw, pitch, targetVelocity, predictedLead, adaptivePrediction.get() || automaticLead.get(), PlayerUtils.getPing());
        } else deltaY = BoatShotCycle.burst(pitch, shotBurst(), minimumPitch.get());
        Vec3d delta = new Vec3d(0, deltaY, 0);
        if (Math.abs(deltaY) < 0.1 || lastWire.distanceTo(boat.getEntityPos()) > 1e-4
            || !phase.shotPathClear(boat, delta) || !clearMuzzle(delta, yaw, pitch) || !safeTravel(boat, delta)) {
            reject(event, "aim steeply and leave clear space for the burst");
            return;
        }
        if (!cycle.queue(tick, boat.getY(), deltaY)) return;
        pending = action;
        queuedAutomatic = automaticReleasing;
        queuedAt = tick;
        shotEyeOffset = player.getEyePos().subtract(boat.getEntityPos()).add(0,-.1,0);
        shotStack = player.getMainHandStack().copy();
        slot = player.getInventory().getSelectedSlot();
        shotYaw = yaw;
        shotPitch = pitch;
        drawTicks = age;
        shotOrigin = lastWire;
        event.cancel();
        status = "burst queued";
        record("QUEUE tick=%d from=%s deltaY=%.3f yaw=%.2f pitch=%.2f draw=%d bow=%s targetHealth=%s", tick, shotOrigin, deltaY, shotYaw, shotPitch, drawTicks, shotStack, target == null ? "none" : target.getHealth());
    }

    public Vec3d movement(AbstractBoatEntity candidate) {
        if(candidate==boat && driving() && phase!=null && Double.isFinite(escapeY))return escapeMovement();
        if (candidate == boat && driving() && web.busy() && phase != null && phase.shotReady(boat)) {
            Vec3d step=web.movement(boat,phase.travelSpeed(),phase.captureVerticalLimit(),phase,message -> record("%s",message));
            return step != null && safeTravel(boat,step) ? step : Vec3d.ZERO;
        }
        if (candidate != boat || !driving() || cycle.stage() == BoatShotCycle.Stage.IDLE) return null;
        if (corrected.get() || !mount.ready() || phase == null || !phase.shotReady(boat)) { abort("movement interrupted", true); return null; }
        if (pending != null && (!sameBow() || cycle.expired(tick))) { abort("stale release", true); return null; }
        if (cycle.stage() == BoatShotCycle.Stage.RETURNING && manualMovement()) { abort("manual return cancel", false); return null; }
        if (pending != null && cycle.stage() == BoatShotCycle.Stage.QUEUED && aimEnabled()) {
            if (!validTarget(target)) { abort("target lost before burst",true); return Vec3d.ZERO; }
            int effectiveDraw = drawTicks + Math.max(0,tick-queuedAt);
            Aim refreshed = aimedShot(effectiveDraw,shotOrigin.add(0,cycle.destinationY()-shotOrigin.y,0).add(shotEyeOffset),cycle.destinationY()-shotOrigin.y);
            if (refreshed == null || Math.signum(refreshed.delta()) != Math.signum(cycle.destinationY()-shotOrigin.y)) {
                abort("intercept changed before burst",true); return Vec3d.ZERO;
            }
            shotYaw=refreshed.yaw();shotPitch=refreshed.pitch();drawTicks=effectiveDraw;
            record("AIM-FIRE tick=%d target=%d position=%s velocity=%s lead=%.2f yaw=%.3f pitch=%.3f",tick,target.getId(),targetPosition,targetVelocity,
                aimLead(),shotYaw,shotPitch);
        }
        double dy = cycle.plan(tick, boat.getY(), phase.shotVerticalLimit());
        Vec3d delta = new Vec3d(0, dy, 0);
        if (pending == null) {
            Vec3d pursuit = followMovement(candidate, phase.travelSpeed(), phase.shotVerticalLimit());
            if (pursuit != null) delta = new Vec3d(pursuit.x,dy,pursuit.z);
        }
        if (pending == null && (!phase.clearTravel(boat,delta) || !safeTravel(boat,delta))) {
            Vec3d vertical=new Vec3d(0,dy,0), horizontal=new Vec3d(delta.x,0,delta.z);
            if(phase.clearTravel(boat,vertical)&&safeTravel(boat,vertical)) delta=vertical;
            else if(phase.clearTravel(boat,horizontal)&&safeTravel(boat,horizontal)) delta=horizontal;
        }
        if (!(pending == null ? phase.clearTravel(boat,delta) : phase.shotPathClear(boat, delta)) || !safeTravel(boat, delta) || pending != null && !clearMuzzle(delta, shotYaw, shotPitch)) {
            abort("blocked shot path", true);
            return null;
        }
        status = pending != null ? "burst" : cycle.stage() == BoatShotCycle.Stage.WAIT_CLEAR ? "waiting for arrow" : "returning";
        return delta;
    }

    @EventHandler(priority = EventPriority.LOWEST - 1000)
    private void onSent(PacketEvent.Sent event) {
        if (!mc.isOnThread() || !sameSession() || event.connection != connection) return;
        if(event.packet instanceof PlayerInteractItemC2SPacket || event.packet instanceof PlayerInteractBlockC2SPacket)
            useBudget.sent(event.packet,System.nanoTime());
        if (event.packet instanceof PlayerMoveC2SPacket movement) {
            if (!player.hasVehicle()) {
                mount.sentGround(movement.isOnGround(), tick);
            }
            if (movement == lookPacket) lookSent = Math.abs(movement.getYaw(Float.NaN) - shotYaw) < 1e-4
                && Math.abs(movement.getPitch(Float.NaN) - shotPitch) < 1e-4;
        }
        if (event.packet == pending && sendingRelease) releaseSent = true;
        if (!(event.packet instanceof VehicleMoveC2SPacket packet) || !driving()) return;
        if (!eventGate.vehicle(packet)) return;
        Vec3d previous = lastWire;
        lastWire = packet.position();
        if (pending == null || previous == null || cycle.stage() != BoatShotCycle.Stage.MOVED) return;
        Vec3d actual = lastWire.subtract(previous);
        if (corrected.get() || !sameBow() || !cycle.sent(tick, previous.y, lastWire.y, actual.horizontalLength())
            || Math.abs(lastWire.x - shotOrigin.x) > 1e-4 || Math.abs(lastWire.z - shotOrigin.z) > 1e-4) {
            abort("wire movement mismatch", true);
            return;
        }

        lookSent = false;
        lookPacket = new PlayerMoveC2SPacket.LookAndOnGround(shotYaw, shotPitch, false, player.horizontalCollision);
        network.sendPacket(lookPacket);
        lookPacket = null;
        if (!lookSent || corrected.get()) { abort("aim packet interrupted", true); return; }
        double launch = BoatShotCycle.launchSpeed(shotPitch, actual.y, bowTicks(drawTicks), false);
        releaseSent = false;
        sendingRelease = true;
        try { network.sendPacket(pending); }
        finally { sendingRelease = false; pending = null; shotStack = ItemStack.EMPTY; }
        if (queuedAutomatic) player.clearActiveItem();
        queuedAutomatic = false;
        if (releaseSent) {
            ownedAutoDraw=false;
            cycle.released(returnHome.get());
            releaseTick = tick;
            shotArrow = -1;
            lastBoostTick = tick;
            lastBoost = Math.abs(actual.y);
        } else cycle.reset();
        status = releaseSent ? "shot sent" : "release intercepted";
        record("RELEASE tick=%d delta=%s idealSpeed=%.3f sent=%b", tick, actual, launch, releaseSent);
        if (chatInfo.get() && releaseSent) info("Sent %.2f-block burst; waiting for server arrow speed (ideal %.2f b/t).", Math.abs(actual.y), launch);
    }

    private boolean validTarget(LivingEntity entity) {
        if (entity == null || entity == player || !entity.isAlive() || entity.isRemoved()
            || entity instanceof ArmorStandEntity || entity.getRootVehicle() == boat
            || world.getEntityById(entity.getId()) != entity || !persistentLock() && player.distanceTo(entity) > targetRange.get()) return false;
        if (entity instanceof PlayerEntity other) {
            if (other.isSpectator() || other.getAbilities().creativeMode || !Friends.get().shouldAttack(other)) return false;
        }
        return true;
    }

    private LivingEntity chooseTarget() {
        if (crosshairSelection.get()) return chooseCrosshairTarget();
        Vec3d eye = player.getEyePos(), view = Vec3d.fromPolar(player.getPitch(), player.getYaw());
        LivingEntity best = null;
        double bestScore = -2, minimum = Math.cos(Math.toRadians(targetAngle.get()));
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || !validTarget(living) || player.distanceTo(living)>targetRange.get()) continue;
            Vec3d offset = living.getBoundingBox().getCenter().subtract(eye);
            double score = view.dotProduct(offset.normalize());
            if (score < minimum) continue;
            boolean betterGroup = preferPlayers.get() && living instanceof PlayerEntity && !(best instanceof PlayerEntity);
            boolean sameGroup = !preferPlayers.get() || (living instanceof PlayerEntity) == (best instanceof PlayerEntity);
            if (best == null || betterGroup || sameGroup && score > bestScore) { best = living; bestScore = score; }
        }
        if (best != null) {
            record("LOCK tick=%d id=%d name=%s cosine=%.4f", tick, best.getId(), best.getName().getString(), bestScore);
            if (chatInfo.get()) info("Locked: %s.", best.getName().getString());
        }
        return best;
    }

    private LivingEntity chooseCrosshairTarget() {
        var camera = mc.gameRenderer.getCamera();
        Vec3d eye = camera.getCameraPos(), view = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        LivingEntity best = null;
        BoatShotCrosshair.Score bestScore = null;
        double minimum = Math.cos(Math.toRadians(crosshairAngle.get()));
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || !validTarget(living) || player.distanceTo(living)>targetRange.get()) continue;

            float partial = mc.getRenderTickCounter().getTickProgress(false);
            Box body = living.getBoundingBox().offset(living.getLerpedPos(partial).subtract(living.getEntityPos()));
            var score = BoatShotCrosshair.score(body, eye, view, targetRange.get());
            if (score == null || score.cosine() < minimum || !score.betterThan(bestScore)) continue;
            if (world.raycast(new RaycastContext(eye, score.point(), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player)).getType() != HitResult.Type.MISS) continue;
            best = living;
            bestScore = score;
        }
        if (best != null) {
            record("LOCK-CROSSHAIR tick=%d id=%d cosine=%.6f distance=%.2f", tick, best.getId(), bestScore.cosine(), bestScore.distance());
            if (chatInfo.get()) info("Locked: %s.", best.getName().getString());
        }
        return best;
    }

    private static double serverTps() { return TickRate.INSTANCE == null ? 20 : TickRate.INSTANCE.getTickRate(); }

    private static Vec3d observedPosition(LivingEntity entity) {
        var interpolation=entity.getInterpolator();
        return interpolation == null ? entity.getEntityPos() : interpolation.getLerpedPos();
    }

    private Box observedTargetBox() {
        return target.getBoundingBox().offset(targetPosition.subtract(target.getEntityPos()));
    }

    private void updateTarget() {
        boolean drawing = player.isUsingItem() && player.getActiveHand() == Hand.MAIN_HAND
            && player.getActiveItem().getItem() instanceof BowItem;
        boolean keep = persistentLock() || automaticFiring() || follows() && player.getMainHandStack().getItem() instanceof BowItem
            || autoRelease.get() && Input.isPressed(mc.options.useKey);
        boolean held=Input.isPressed(mc.options.useKey);
        if(!held)suppressAcquire=false;
        boolean acquire = drawLock.update(drawing, held, autoRelease.get() || automaticFiring(),
            pending != null || cycle.stage() != BoatShotCycle.Stage.IDLE || web.busy(),persistentLock(),validTarget(target)) && !suppressAcquire;
        if (!aimEnabled() || !drawing && !keep && pending == null && cycle.stage() == BoatShotCycle.Stage.IDLE) {
            target = null; targetPosition = null; targetVelocity = Vec3d.ZERO; prediction.resetMotion(); return;
        }
        if (!validTarget(target) || acquire) {
            navigator.reset(); web.retreat();
            if(target!=null && !validTarget(target)) stopAutomaticDraw();
            target = drawing && (acquire || !persistentLock() && !automaticFiring()) ? chooseTarget() : null;
            targetPosition = target == null ? null : observedPosition(target);
            targetVelocity = Vec3d.ZERO;
            prediction.resetMotion();
            acquiredTarget();
        }
        if (target != null) {
            targetPosition=observedPosition(target);
            double ratio=BoatShotPrediction.serverTicksPerClientTick(serverTps());
            var velocity=prediction.observePosition(tick,targetPosition.x,targetPosition.y,targetPosition.z,
                adaptivePrediction.get()?(int)Math.ceil(3/ratio):5);
            targetVelocity=new Vec3d(velocity.x(),velocity.y(),velocity.z()).multiply(1/ratio);
        }
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (showTarget.get() && driving() && validTarget(target)) {
            Color line = targetColor.get();
            Box box = target.getBoundingBox().expand(.08);
            event.renderer.box(box, new Color(line.r,line.g,line.b,targetFill.get()), line, ShapeMode.Both, 0);
            event.renderer.box(box.expand(.045), new Color(0,0,0,0), new Color(255,255,255,220), ShapeMode.Lines, 0);

            event.renderer.box(new Box(box.minX-.12,box.maxY+.15,box.minZ-.12,box.maxX+.12,box.maxY+.23,box.maxZ+.12),
                new Color(line.r,line.g,line.b,180), line, ShapeMode.Both, 0);
        }
    }

    @EventHandler private void onRenderStatus(Render2DEvent event) {
        if(!combatStatus.get() || !driving() || mc.options.hudHidden)return;
        TextRenderer text=TextRenderer.get();if(text.isBuilding())return;
        String label="BoatShot | "+status;
        text.begin(1.2);
        double width=text.getWidth(label,true),height=text.getHeight();
        double x=Math.max(4,(event.screenWidth-width)/2),y=Math.max(4,event.screenHeight-100-height);
        Renderer2D.COLOR.begin();Renderer2D.COLOR.quad(x-8,y-5,width+16,height+10,new Color(10,14,20,215));Renderer2D.COLOR.render();
        text.render(label,x,y,new Color(255,220,110),true);text.end();
    }

    private void resetCover() {coverMode=false;coverRoute=null;coverIndex=0;nextCoverSearch=0;}
    private static BoatShotCover.Point point(Vec3d v) {return new BoatShotCover.Point(v.x,v.y,v.z);}
    private static Vec3d vector(BoatShotCover.Point p) {return new Vec3d(p.x(),p.y(),p.z());}
    private void updateCover() {
        if(!seekCoverAngle.get() || !follows() || !aimEnabled() || !validTarget(target) || phase==null) {resetCover();return;}
        if(manualMovement() || mc.currentScreen!=null || pending!=null || cycle.stage()!=BoatShotCycle.Stage.IDLE
            || !phase.shotReady(boat) || !(player.getMainHandStack().getItem() instanceof BowItem) || tick<nextCoverSearch)return;
        nextCoverSearch=tick+20;
        Box body=observedTargetBox();
        Vec3d overhead=new Vec3d(body.getCenter().x,Math.min(phase.captureCeiling(boat),body.maxY+Math.max(followHeight.get(),shotBurst()+6)),body.getCenter().z);
        boolean covered=world.raycast(new RaycastContext(overhead,body.getCenter(),RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,player)).getType()!=HitResult.Type.MISS;
        if(!covered) {coverMode=false;coverRoute=null;return;}
        coverMode=true;

        if(web.busy())return;
        String previousFailure=aimFailure,previousObstruction=obstruction;
        try {
            coverRoute=BoatShotCover.find(point(boat.getEntityPos()),new BoatShotCover.Point(body.getCenter().x,body.maxY,body.getCenter().z),
                phase.captureCeiling(boat),this::coverFiringStation,(a,b)->coverTravel(vector(a),vector(b)));
            coverIndex=0;
            record("COVER tick=%d route=%s gliding=%b",tick,coverRoute==null?"none":coverRoute.points(),target.isGliding());
        } finally {aimFailure=previousFailure;obstruction=previousObstruction;}
    }
    private boolean coverFiringStation(BoatShotCover.Point p) {
        Vec3d station=vector(p),eye=player.getEyePos().add(station.subtract(boat.getEntityPos()));
        if(eye.y<=observedTargetBox().maxY+2)return false;
        double burst=-BoatShotAutomation.downwardBurst(Math.min(2,shotBurst()),eye.y,observedTargetBox().maxY);
        if(!coverTravel(station,station.add(0,burst,0)))return false;
        Aim aim=aimedShot(BoatShotDamage.clientTicks(20,serverTps()),eye.add(0,burst-.1,0),burst);
        if(aim==null)return false;
        Vec3d direction=Vec3d.fromPolar(aim.pitch(),aim.yaw());
        for(Vec3d start:new Vec3d[]{eye.add(0,-.1,0),eye.add(0,burst-.1,0)})
            if(world.raycast(new RaycastContext(start,start.add(direction.multiply(2)),RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,player)).getType()!=HitResult.Type.MISS)return false;
        return true;
    }
    private boolean coverTravel(Vec3d from,Vec3d to) {
        Vec3d delta=to.subtract(from);
        if(!Double.isFinite(delta.lengthSquared()) || delta.length()>96)return false;

        int sections=Math.max(1,(int)Math.ceil(delta.length()/3));
        Vec3d step=delta.multiply(1.0/sections);
        for(int i=0;i<sections;i++) {
            Vec3d offset=from.add(step.multiply(i)).subtract(boat.getEntityPos());
            for(Entity passenger:boat.streamSelfAndPassengers().toList()) {
                Box path=passenger.getBoundingBox().offset(offset).stretch(step).contract(1e-7);
                if(path.minY<world.getBottomY()+2 || path.maxY>world.getTopYInclusive()+1 || !world.getWorldBorder().contains(path))return false;
                for(int x=(int)Math.floor(path.minX)>>4;x<=(int)Math.floor(path.maxX)>>4;x++)
                    for(int z=(int)Math.floor(path.minZ)>>4;z<=(int)Math.floor(path.maxZ)>>4;z++)
                        if(!world.getChunkManager().isChunkLoaded(x,z))return false;
                if(!world.isSpaceEmpty(passenger,path))return false;
                for(var pos:net.minecraft.util.math.BlockPos.iterate(net.minecraft.util.math.BlockPos.ofFloored(path.minX,path.minY,path.minZ),
                    net.minecraft.util.math.BlockPos.ofFloored(path.maxX,path.maxY,path.maxZ)))if(!world.getFluidState(pos).isEmpty())return false;
            }
            if(!guard.clearAt(world,boat,offset,step,protectCrystals()))return false;
        }
        return true;
    }

    public Vec3d followMovement(AbstractBoatEntity candidate, double speed, double vertical) {
        if (!follows() || !aimEnabled() || candidate != boat || !driving() || !validTarget(target)
            || !inventoryFollowAllowed() && !(player.getMainHandStack().getItem() instanceof BowItem)
            || manualMovement() || mc.currentScreen != null && !inventoryFollowAllowed()
            || pending != null) return null;
        if(coverMode) {
            if(coverRoute==null)return Vec3d.ZERO;
            while(coverIndex<coverRoute.points().size()-1 && boat.getEntityPos().distanceTo(vector(coverRoute.points().get(coverIndex)))<.15)coverIndex++;
            Vec3d goal=vector(coverRoute.points().get(coverIndex)).subtract(boat.getEntityPos());

            var step=BoatShotCover.step(point(goal),speed,phase.captureVerticalLimit());
            Vec3d delta=new Vec3d(step.x(),step.y(),step.z());
            if(phase.clearTravel(candidate,delta)&&safeTravel(candidate,delta))return delta;
            coverRoute=null;nextCoverSearch=tick+10;return Vec3d.ZERO;
        }
        double height = Math.max(followHeight.get(), shotBurst()+6);
        double lead = predictedLead + Math.max(0, height-shotBurst())/(shotBurst()+3);
        Vec3d goal = targetPosition.add(targetVelocity.multiply(lead+leadAdjustment.get()));
        boolean capture=webCube.get() && webEligible() && target instanceof PlayerEntity && !web.caught(target) && !web.shotWindow(tick);
        if(autoCombat.get() && capture) {
            Vec3d interception=web.interception(boat,targetPosition,targetVelocity,predictedLead+leadAdjustment.get(),phase);
            if(interception!=null)goal=interception;
        }
        double goalY=Math.min(phase.captureCeiling(boat),observedTargetBox().maxY+height);
        Vec3d waterExit=phase.waterDeparture(candidate,goalY-boat.getY());
        if(waterExit!=null)return safeTravel(candidate,waterExit)?waterExit:Vec3d.ZERO;
        if(autoCombat.get()) {
            var fast=BoatShotAutomation.approach(goal.x-boat.getX(),goalY-boat.getY(),goal.z-boat.getZ(),speed,phase.captureVerticalLimit());
            Vec3d delta=new Vec3d(fast.x(),fast.y(),fast.z());
            if(phase.clearTravel(candidate,delta)&&safeTravel(candidate,delta))return delta;
        }
        BoatShotPursuit.Step step = navigator.next(goal.x-boat.getX(), goalY-boat.getY(), goal.z-boat.getZ(),
            targetVelocity.x*BoatShotPrediction.serverTicksPerClientTick(serverTps()),targetVelocity.z*BoatShotPrediction.serverTicksPerClientTick(serverTps()),speed,vertical,
            p -> phase.clearTravel(candidate,new Vec3d(p.x(),p.y(),p.z())) && safeTravel(candidate,new Vec3d(p.x(),p.y(),p.z())));
        return new Vec3d(step.x(),step.y(),step.z());
    }

    private void automaticDraw() {
        boolean autonomous=automaticFiring();
        boolean rapid=autonomous || rapidFire.get();
        String pause=Double.isFinite(escapeY)?"disengaging":!validTarget(target)?"lock a target to begin":mc.currentScreen!=null?"paused: screen open"
            :manualMovement()?"paused: manual steering":!mount.ready()?"reboard to prime bow":phase==null||!phase.shotReady(boat)?"waiting for BoatPhase"
            :!(player.getMainHandStack().getItem() instanceof BowItem)?"paused: select a bow":null;
        if(pause!=null || (!autonomous && (!autoRelease.get() || !Input.isPressed(mc.options.useKey))) || !aimEnabled() || mc.interactionManager==null) {
            stopAutomaticDraw();if(pause!=null)status=pause;return;
        }
        boolean captureFallback=shootAfterCaptureMiss.get() && (web.shotWindow(tick)||web.unavailable());
        if(webCube.get() && webEligible() && !coverMode && !captureFallback && (autoCombat.get() || waitForWeb.get()) && target instanceof PlayerEntity && !web.caught(target)) {
            stopAutomaticDraw(); status="approaching for webs: "+web.waitReason(); return;
        }
        if(pending!=null || !BoatShotCadence.mayDraw(cycle.stage(),rapid) || tick<nextAutoTick)return;
        if (!player.isUsingItem()) {
            if(player.getProjectileType(player.getMainHandStack()).isEmpty() && !player.getAbilities().creativeMode) {status="out of arrows";return;}
            if(useBudget.available(System.nanoTime())==0) {status="waiting for item-use allowance";return;}
            mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
            ownedAutoDraw=player.isUsingItem();
            status=ownedAutoDraw?"drawing bow":"bow draw did not start";
            record("AUTO-DRAW tick=%d started=%b",tick,ownedAutoDraw);
            nextAutoTick = tick + 1;
            return;
        }
        if(autonomous && player.getActiveHand()==Hand.MAIN_HAND && player.getActiveItem().getItem() instanceof BowItem)ownedAutoDraw=true;
        int required=autonomous ? BoatShotDamage.clientTicks(BoatShotDefense.drawTicks(target,player.getMainHandStack(),aimBurst()),serverTps())
            : BoatShotCadence.minimumDraw(rapidFire.get(),rapidDrawTicks.get(),chargeTicks.get());
        chargeRequired=required;
        status="drawing "+player.getItemUseTime()+"/"+required+" | HP "+String.format(Locale.ROOT,"%.1f",target.getHealth());

        if (!BoatShotCadence.mayRelease(cycle.stage(),rapid,tick-releaseTick)
            || rapid && tick-releaseTick<BoatShotDamage.clientTicks(10,serverTps())) return;
        if (player.getActiveHand() != Hand.MAIN_HAND || !(player.getActiveItem().getItem() instanceof BowItem)
            || player.getItemUseTime() < required || lastWire == null || corrected.get()) return;
        Aim aim = validTarget(target) ? aimedShot(player.getItemUseTime()) : null;
        if (aim == null) {
            status = target == null ? "waiting for target" : coverMode ? coverRoute==null ? "covered: no reachable firing angle" : "moving to clear firing angle" : aimFailure;
            if (tick-lastWaitLog >= 40) { record("AUTO-WAIT tick=%d reason=%s detail=%s", tick,status,obstruction); lastWaitLog=tick; }
            return;
        }
        Vec3d delta = new Vec3d(0,aim.delta(),0);
        if (lastWire.distanceTo(boat.getEntityPos()) > 1e-4 || !phase.shotPathClear(boat,delta)
            || !clearMuzzle(delta,aim.yaw(),aim.pitch()) || !safeTravel(boat,delta)) { status="waiting for burst clearance"; return; }
        nextAutoTick = tick + 1;
        record("AUTO-RELEASE tick=%d rapid=%b adaptive=%b draw=%d required=%d health=%.2f absorption=%.2f gliding=%b bodyHeight=%.3f velocity=%s",tick,rapid,autonomous,player.getItemUseTime(),required,target.getHealth(),target.getAbsorptionAmount(),target.isGliding(),target.getBoundingBox().getLengthY(),targetVelocity);
        automaticReleasing = true;
        try { mc.interactionManager.stopUsingItem(player); }
        finally { automaticReleasing = false; }
    }

    private Aim aimedShot(int age) {
        double burst = observedTargetBox().getCenter().y < player.getEyeY() ? -aimBurst() : aimBurst();
        return aimedShot(age,player.getEyePos().add(0,burst-.1,0),burst);
    }

    private Aim aimedShot(int age,Vec3d muzzle,double burst) {
        age=bowTicks(age);
        Box box = observedTargetBox();
        boolean solved = false;
        obstruction = "";

        for (double fraction : new double[] {.5,.65,.35}) {
            Vec3d point = new Vec3d((box.minX+box.maxX)/2, box.minY+(box.maxY-box.minY)*fraction,(box.minZ+box.maxZ)/2);
            Vec3d offset = point.add(targetVelocity.multiply(aimLead())).subtract(muzzle);
            BoatShotAim.Solution solution = BoatShotAim.solveDiscrete(offset.x,offset.y,offset.z,targetVelocity.x,targetVelocity.y,targetVelocity.z,
                BoatShotAim.bowSpeed(age),burst,.01);
            if (solution == null) continue;
            solved = true;
            if (clearTrajectory(muzzle,solution,burst,age)) return new Aim(burst,solution.yaw(),solution.pitch());
        }
        aimFailure = solved ? "arrow path blocked before target contact" : "move farther above or more directly over the target";
        return null;
    }

    private boolean clearTrajectory(Vec3d muzzle, BoatShotAim.Solution aim, double burst, int age) {
        Vec3d velocity = Vec3d.fromPolar(aim.pitch(),aim.yaw()).multiply(BoatShotAim.bowSpeed(age)).add(0,burst,0);
        return BoatShotTrajectory.clearDiscrete(muzzle,velocity,aim.ticks(),observedTargetBox(),targetVelocity,aimLead(),(from,to) -> {
            var hit = world.raycast(new RaycastContext(from,to,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,player));
            if (hit.getType() == HitResult.Type.MISS) return true;
            obstruction = "block="+hit.getBlockPos()+" state="+world.getBlockState(hit.getBlockPos())+" at="+hit.getPos()+" target="+target.getBoundingBox();
            return false;
        });
    }
    private boolean clearMuzzle(Vec3d delta, float yaw, float pitch) {
        Vec3d direction = Vec3d.fromPolar(pitch, yaw);

        Vec3d eye = player.getEyePos().add(0, -0.1, 0);
        for (Vec3d start : new Vec3d[] { eye, eye.add(delta) }) {
            if (world.raycast(new RaycastContext(start, start.add(direction.multiply(2)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player)).getType() != HitResult.Type.MISS) return false;
        }
        return true;
    }

    @EventHandler private void onReceive(PacketEvent.Receive event) {
        if (connection != null && event.connection == connection && !event.isCancelled()) web.offer(event.packet);
        if (connection != null && event.connection == connection && !event.isCancelled()) feedback.offer(event.packet);
        if (connection != null && event.connection == connection && !event.isCancelled()
            && event.packet instanceof EntitySpawnS2CPacket spawn && spawn.getEntityData() == playerId
            && (spawn.getEntityType() == EntityType.ARROW || spawn.getEntityType() == EntityType.SPECTRAL_ARROW)) {
            if (spawns.size() >= 32) spawns.poll();
            spawns.offer(spawn);
        }
        if (connection != null && event.connection == connection
            && !event.isCancelled() && (event.packet instanceof VehicleMoveS2CPacket || event.packet instanceof PlayerPositionLookS2CPacket)) corrected.set(true);
    }

    private void observeArrows() {
        EntitySpawnS2CPacket spawn;
        while ((spawn = spawns.poll()) != null) {
            if (seenArrows.put(spawn.getEntityId(),true)!=null) continue;
            while(seenArrows.size()>4096)seenArrows.remove(seenArrows.keySet().iterator().next());
            arrows.put(spawn.getEntityId(), tick + 200);
            while (arrows.size() > 32) arrows.remove(arrows.keySet().iterator().next());
            record("ARROW tick=%d id=%d velocity=%s speed=%.3f position=(%.5f,%.5f,%.5f)", tick, spawn.getEntityId(), spawn.getVelocity(), spawn.getVelocity().length(),spawn.getX(),spawn.getY(),spawn.getZ());
            if (cycle.stage() == BoatShotCycle.Stage.WAIT_CLEAR && shotArrow == -1 && tick-releaseTick<=20
                && spawn.getVelocity().length()>1 && shotOrigin!=null
                && new Vec3d(spawn.getX(),spawn.getY(),spawn.getZ()).distanceTo(shotOrigin)<lastBoost+8) {
                shotArrow = spawn.getEntityId();
                if (chatInfo.get()) info("Server arrow spawn speed: %.2f b/t.", spawn.getVelocity().length());
            }
        }
        arrows.entrySet().removeIf(entry -> tick > entry.getValue());
        if (targetPosition != null) for(var entry:arrows.entrySet()) {
            if(tick-entry.getValue()+200>8)continue;
            Entity tracked=world.getEntityById(entry.getKey());
            if(tracked!=null)record("ARROW-TRACK tick=%d id=%d position=%s target=%d targetPosition=%s velocity=%s",tick,entry.getKey(),tracked.getEntityPos(),target.getId(),targetPosition,targetVelocity);
        }
        if (cycle.stage() != BoatShotCycle.Stage.WAIT_CLEAR || !driving()) return;
        if (feedback.hitConfirmed(shotArrow)) {
            record("CLEAR-HIT tick=%d id=%d",tick,shotArrow);
            cycle.arrowClear();
            return;
        }
        Entity entity = world.getEntityById(shotArrow);
        if (entity instanceof PersistentProjectileEntity arrow && !arrow.getBoundingBox().intersects(boat.getBoundingBox().expand(2))) {
            Vec3d separation = arrow.getEntityPos().subtract(boat.getBoundingBox().getCenter());
            if (separation.dotProduct(arrow.getVelocity()) > 0 || ((ProjectileInGroundAccessor) arrow).meteor$invokeIsInGround()) {
                record("CLEAR tick=%d id=%d", tick, shotArrow);
                cycle.arrowClear();
                return;
            }
        }
        if (tick - releaseTick > 40) {
            abort("arrow clearance timeout; return canceled", false);
            warning("No arrow clearance confirmed. Return canceled; check the Boat Shot log.");
        }
    }

    public boolean safeTravel(AbstractBoatEntity candidate, Vec3d step) {
        if (candidate != boat || !driving()) return true;
        boolean crystals = protectCrystals();
        if (crystals != crystalsProtected) {
            crystalsProtected=crystals;
            guard.refresh(world,player,boat,target,crystals);
        }
        if (!guard.clear(world,candidate,step,crystals)) {
            travelBlockReason=crystals?"crystal or web danger":"web or hazardous block";
            return false;
        }
        if (step.lengthSquared() < 1e-10) return true;
        Box hull = candidate.getBoundingBox().expand(0.4);
        for (int id : arrows.keySet()) {
            if (!(world.getEntityById(id) instanceof PersistentProjectileEntity arrow)
                || ((ProjectileInGroundAccessor) arrow).meteor$invokeIsInGround()) continue;
            Vec3d start = arrow.getEntityPos();

            if (hull.contains(start)) continue;
            Vec3d end = start.add(arrow.getVelocity()).subtract(step);
            if (hull.contains(end) || hull.raycast(start, end).isPresent()) {
                travelBlockReason="own arrow ahead";
                record("TRAVEL-BLOCK tick=%d arrow=%d step=%s", tick, id, step);
                return false;
            }
        }
        return true;
    }

    public String travelBlockReason() { return travelBlockReason; }

    private boolean protectCrystals() {
        if (!combatCrystalGuard.get()) return true;
        return web.busy() || pending!=null || cycle.stage()!=BoatShotCycle.Stage.IDLE
            || inventoryFollowAllowed()
            || player.isUsingItem() && player.getActiveItem().getItem() instanceof BowItem
            || follows() && validTarget(target) && player.getMainHandStack().getItem() instanceof BowItem;
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        WTable table=theme.table();
        table.add(theme.button("Unlock target / stop auto-fire")).expandX().widget().action=this::unlockTarget;
        table.row();
        table.add(theme.button("Toggle boat speedometer")).expandX().widget().action=()->{
            BoatPhase controller=Modules.get().get(BoatPhase.class);
            if(controller!=null) controller.toggleSpeedometer();
        };
        table.row();
        table.add(theme.button("Enable automatic combat")).expandX().widget().action=()->{
            autoCombat.set(true);
            info("Automatic combat enabled. Lock with the target key or draw once. No held right-click needed.");
        };
        return table;
    }

    private void reject(PacketEvent.Send event, String reason) {
        event.cancel();
        cancelDraw();
        status = reason;
        record("REJECT tick=%d reason=%s detail=%s", tick, reason, obstruction);
        if (chatInfo.get()) warning("Shot canceled: %s.", reason);
    }

    private void cancelDraw() {
        if (!sameSession()) return;
        int selected = player.getInventory().getSelectedSlot();
        sendingRelease = true;
        try {

            network.sendPacket(new UpdateSelectedSlotC2SPacket((selected + 1) % 9));
            network.sendPacket(new UpdateSelectedSlotC2SPacket(selected));
        } finally { sendingRelease = false; }
    }

    private void abort(String reason, boolean release) {
        if (pending != null || cycle.stage() != BoatShotCycle.Stage.IDLE) record("ABORT tick=%d reason=%s stage=%s", tick, reason, cycle.stage());
        if (release && pending != null && sameBow()) {
            cancelDraw();
        }
        pending = null;
        queuedAutomatic = false;
        shotStack = ItemStack.EMPTY;
        cycle.reset();
        status = reason;
    }

    private void resetSession() {
        pending = null;
        cycle.reset();
        connection = null;
        player = null;
        world = null;
        network = null;
        boat = null;
        lastWire = shotOrigin = null;
        primePacket = lookPacket = null;
        shotStack = ItemStack.EMPTY;
        corrected.set(false);
        sendingRelease = false;
        mount.reset();
        spawns.clear();
        arrows.clear();
        seenArrows.clear(); useBudget.reset(); ownedAutoDraw=false; suppressAcquire=false; powerRejected=false;
        escapeY=Double.NaN;escapeAway=Vec3d.ZERO;lastFlow="";chargeRequired=0;
        feedback.reset();
        web.reset(); guard.reset(); navigator.reset(); resetCover();
        prediction.reset(); predictedLead=2; crystalsProtected=false;
        drawLock.reset();
        target = null;
        targetPosition = null;
        targetVelocity = Vec3d.ZERO;
        playerId = shotArrow = -1;
        lastBoostTick = -100;
        lastBoost = 0;
        tick = 0;
        releaseTick = -100;
        automaticReleasing = false;
        nextAutoTick = 0;
        lastWaitLog = -100;
        if (log != null) { log.close(); log = null; }
    }

    private void pauseConflicts() {
        for (String name : CONFLICTS) {
            Module module = Modules.get().get(name);
            if (module != null && module.isActive()) {
                module.toggle();
                if (!paused.contains(module)) paused.add(module);
            }
        }
    }

    private void restoreConflicts() {
        for (Module module : paused) if (!module.isActive()) module.toggle();
        paused.clear();
    }

    private void openLog() {
        if (!debugFile.get()) return;
        try {
            Path directory = mc.runDirectory.toPath().resolve("boat-shot");
            Files.createDirectories(directory);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            log = new PrintWriter(Files.newBufferedWriter(directory.resolve("shot-" + time + ".log"), StandardCharsets.UTF_8));
            record("START BoatShot v16; adaptive flight net, inventory following and water ascent");
            for (SettingGroup group : settings) for (Setting<?> setting : group) record("cfg %s = %s", setting.name, setting.get());
        } catch (IOException e) { warning("Could not open Boat Shot log: %s", e.getMessage()); }
    }

    private void record(String format, Object... args) {
        if (log != null) { log.printf(Locale.ROOT, format + "%n", args); log.flush(); }
    }

    @Override public String getInfoString() {
        return status+(webCube.get() && follows() && target instanceof PlayerEntity && !web.busy()?" | web: "+(webEligible()?web.waitReason():"above capture height; shooting only"):"");
    }
}
