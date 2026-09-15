package com.quiettee.utils.modules.movement;

import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.mixin.PlayerMoveC2SPacketAccessor;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerEntityAccessor;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class GliderWalk extends Module {
    private static final int TRACKED_FLAGS_ID = 0;
    private static final int GLIDING_BIT = 0x80;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgShow = settings.createGroup("Show");

    private final Setting<Boolean> groundFly = sgGeneral.add(new BoolSetting.Builder()
        .name("ground-fly")
        .description("Skate at elytra speed on the ground.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> flySpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("fly-speed")
        .description("Ground fly speed in blocks per tick.")
        .defaultValue(1.5)
        .min(0.0)
        .sliderRange(0.0, 3.0)
        .visible(groundFly::get)
        .build()
    );

    private final Setting<Boolean> hideNametag = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-nametag")
        .description("Hide your nametag.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-elytra")
        .description("Swap to an elytra automatically.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDurability = sgGeneral.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Drop the pose below this durability.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> showSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("show-self")
        .description("Show the pose on your own client.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> retryRate = sgGeneral.add(new IntSetting.Builder()
        .name("retry-rate")
        .description("Minimum ticks between pose retries.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Only claim while standing on something.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> holdStill = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-still")
        .description("Keep the pose while standing still.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> spin = sgShow.add(new BoolSetting.Builder()
        .name("spin")
        .description("Spin your yaw for onlookers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> spinSpeed = sgShow.add(new DoubleSetting.Builder()
        .name("spin-speed")
        .description("Degrees of yaw per tick.")
        .defaultValue(18.0)
        .min(-90.0)
        .sliderRange(-90.0, 90.0)
        .visible(spin::get)
        .build()
    );

    private final Setting<Double> pitchLock = sgShow.add(new DoubleSetting.Builder()
        .name("pitch-lock")
        .description("Report a fixed pitch.")
        .defaultValue(-181.0)
        .min(-181.0)
        .sliderRange(-181.0, 90.0)
        .build()
    );

    private final Setting<Boolean> bob = sgShow.add(new BoolSetting.Builder()
        .name("bob")
        .description("Bob your pitch on a sine wave.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> bobRange = sgShow.add(new DoubleSetting.Builder()
        .name("bob-range")
        .description("Bob degrees either side of level.")
        .defaultValue(35.0)
        .min(0.0)
        .sliderRange(0.0, 90.0)
        .visible(bob::get)
        .build()
    );

    private final Setting<Double> bobSpeed = sgShow.add(new DoubleSetting.Builder()
        .name("bob-speed")
        .description("Bob speed in radians per tick.")
        .defaultValue(0.1)
        .min(0.01)
        .sliderRange(0.01, 0.5)
        .visible(bob::get)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Chat when the pose lights or fails.")
        .defaultValue(true)
        .build()
    );

    private int timer;
    private int chestSlot = -1;
    private boolean rewritingInput;
    private boolean rewritingMove;
    private float spinYaw;
    private int showTicks;
    private boolean serverGliding;
    private boolean announced;
    private int attempts;

    private int pending;
    private boolean airRelight;
    private boolean sawMove;
    private int airRelights;

    public GliderWalk() {
        super(QuietteeUtils.CATEGORY, "glider-walk", "Walk on the ground in the elytra flight pose.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        serverGliding = mc.player != null && mc.player.isGliding();
        announced = false;
        attempts = 0;
        pending = 0;
        airRelight = false;
        sawMove = false;
        airRelights = 0;

        chestSlot = -1;
        spinYaw = mc.player == null ? 0.0F : mc.player.getYaw();
        showTicks = 0;
        if (autoElytra.get() && !hasElytra()) equipElytra();

        if (!hasElytra()) warning("no elytra equipped - canGlide() checks your chest slot, so the server will refuse the pose.");
        else if (mc.player != null && !mc.player.isOnGround()) info("you are in the air - land first. This is a walking trick; it does nothing while you are really flying.");
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        boolean clientFlying = mc.player.isGliding() && !mc.player.isOnGround() && !mc.player.isTouchingWater();

        if (serverGliding && !clientFlying) mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        if (mc.player.isGliding() && !clientFlying) mc.player.stopGliding();
        serverGliding = clientFlying && serverGliding;
        pending = 0;
        airRelight = false;

        if (spin.get() || bob.get() || pitchLock.get() > -180.5) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
        }

        if (hideNametag.get()) sendInput(false);
        if (autoElytra.get() && chestSlot != -1) restoreChest();
    }

    private int echoWait() {
        int rtt = (int) Math.ceil(PlayerUtils.getPing() / 50.0) + 3;
        return Math.min(60, Math.max(retryRate.get(), rtt));
    }

    private boolean hasElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER);
    }

    private boolean claiming() {
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        if (!hasElytra()) return false;
        if (mc.player.isTouchingWater()) return false;
        if (mc.player.hasVehicle()) return false;
        return true;
    }

    private boolean engaged() {
        return claiming() && (!onlyOnGround.get() || mc.player.isOnGround());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        sawMove = false;
        airRelight = false;
        if (pending > 0) pending--;

        if (!claiming()) return;
        if (holdStill.get()) ((ClientPlayerEntityAccessor) mc.player).meteor$setTicksSinceLastPositionPacketSent(20);
        if (!engaged()) {

            if (showSelf.get() && mc.player.isGliding() && !mc.player.isOnGround() && !serverGliding && pending == 0) {
                airRelight = true;
                ((ClientPlayerEntityAccessor) mc.player).meteor$setTicksSinceLastPositionPacketSent(20);
            }
            return;
        }

        if (!serverGliding && pending == 0 && --timer <= 0) {
            timer = retryRate.get();
            light();
        }

        if (minDurability.get() > 0) {
            ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (chest.isDamageable() && chest.getMaxDamage() - chest.getDamage() <= minDurability.get()) {
                warning("elytra down to %d durability - dropping the pose before it breaks.", chest.getMaxDamage() - chest.getDamage());
                toggle();
                return;
            }
        }

        if (holdStill.get()) ((ClientPlayerEntityAccessor) mc.player).meteor$setTicksSinceLastPositionPacketSent(20);

        showTicks++;
        if (spin.get()) spinYaw = MathHelper.wrapDegrees(spinYaw + spinSpeed.get().floatValue());

        if (hideNametag.get() && timer % 10 == 0) sendInput(true);

        if (!showSelf.get() && mc.player.isGliding()) mc.player.stopGliding();
    }

    private void light() {
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.player.getYaw(), mc.player.getPitch(), false, mc.player.horizontalCollision));

        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));

        if (++attempts == 8 && !serverGliding && notify.get()) {
            warning("the server keeps refusing the pose - check you have an elytra on, are not in water or a vehicle, and that NoFall's ground spoof is off (it claims the opposite flag).");
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null || !(event.packet instanceof EntityTrackerUpdateS2CPacket p) || p.id() != mc.player.getId()) return;

        for (DataTracker.SerializedEntry<?> entry : p.trackedValues()) {
            if (entry.id() == TRACKED_FLAGS_ID && entry.value() instanceof Byte b) {
                boolean now = (b & GLIDING_BIT) != 0;
                if (now && !serverGliding && !announced && notify.get()) {
                    announced = true;
                    info("pose lit - everyone can see you soaring.");
                }

                if (now || now != serverGliding) pending = 0;
                serverGliding = now;
            }
        }
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (!airRelight || mc.player == null || mc.getNetworkHandler() == null) return;
        airRelight = false;
        if (!sawMove) return;

        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        airRelights++;
        if (notify.get() && airRelights <= 3) info("the server dropped your wings mid-air - re-opened them (#%d).", airRelights);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (!groundFly.get() || event.type != MovementType.SELF || !engaged()) return;
        if (mc.currentScreen != null) return;

        Vec3d fwd = Vec3d.fromPolar(0, mc.player.getYaw());
        Vec3d rgt = Vec3d.fromPolar(0, mc.player.getYaw() + 90);

        double fx = 0, fz = 0;
        if (mc.options.forwardKey.isPressed()) { fx += fwd.x; fz += fwd.z; }
        if (mc.options.backKey.isPressed())    { fx -= fwd.x; fz -= fwd.z; }
        if (mc.options.rightKey.isPressed())   { fx += rgt.x; fz += rgt.z; }
        if (mc.options.leftKey.isPressed())    { fx -= rgt.x; fz -= rgt.z; }

        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1e-6) return;

        double speed = flySpeed.get();
        ((IVec3d) event.movement).meteor$set(fx / len * speed, event.movement.y, fz / len * speed);
    }

    private void sendInput(boolean sneak) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        rewritingInput = true;
        try {
            mc.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(new PlayerInput(
                mc.options.forwardKey.isPressed(), mc.options.backKey.isPressed(),
                mc.options.leftKey.isPressed(), mc.options.rightKey.isPressed(),
                mc.options.jumpKey.isPressed(), sneak, mc.options.sprintKey.isPressed())));
        }
        finally {
            rewritingInput = false;
        }
    }

    private void equipElytra() {
        for (int i = 0; i < mc.player.getInventory().getMainStacks().size(); i++) {
            if (mc.player.getInventory().getMainStacks().get(i).contains(DataComponentTypes.GLIDER)) {
                chestSlot = i;
                InvUtils.move().from(i).toArmor(2);
                mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(0));
                return;
            }
        }
        warning("no elytra in the inventory to swap to.");
    }

    private void restoreChest() {
        if (chestSlot < 0 || chestSlot >= mc.player.getInventory().getMainStacks().size()) return;
        ItemStack back = mc.player.getInventory().getMainStacks().get(chestSlot);
        if (back.isEmpty() || back.contains(DataComponentTypes.GLIDER)) return;

        InvUtils.move().from(chestSlot).toArmor(2);
        mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(0));
        chestSlot = -1;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {

        if (event.packet instanceof ClientCommandC2SPacket c && c.getMode() == ClientCommandC2SPacket.Mode.START_FALL_FLYING) {
            pending = echoWait();
            return;
        }

        if (hideNametag.get() && !rewritingInput && event.packet instanceof PlayerInputC2SPacket in && claiming()) {
            PlayerInput i = in.input();
            if (!i.sneak()) {
                event.cancel();
                sendInput(true);
            }
            return;
        }

        if (!(event.packet instanceof PlayerMoveC2SPacket p) || !claiming()) return;
        ((PlayerMoveC2SPacketAccessor) p).quiettee$setOnGround(false);
        if (p.changesPosition()) sawMove = true;

        boolean lockPitch = pitchLock.get() > -180.5;
        if ((spin.get() || bob.get() || lockPitch) && !rewritingMove) {
            float yaw = spin.get() ? spinYaw : mc.player.getYaw();
            float pitch;
            if (bob.get()) pitch = (float) (Math.sin(showTicks * bobSpeed.get()) * bobRange.get());
            else if (lockPitch) pitch = pitchLock.get().floatValue();
            else pitch = mc.player.getPitch();

            if (p.changesLook()) {
                ((PlayerMoveC2SPacketAccessor) p).quiettee$setYaw(yaw);
                ((PlayerMoveC2SPacketAccessor) p).quiettee$setPitch(pitch);
            }
            else {
                event.cancel();
                rewritingMove = true;
                try {
                    PlayerMoveC2SPacket full = new PlayerMoveC2SPacket.Full(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        yaw, pitch, false, mc.player.horizontalCollision);
                    mc.getNetworkHandler().sendPacket(full);
                }
                finally {
                    rewritingMove = false;
                }
            }
        }
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        if (!hasElytra()) return "no elytra";
        if (onlyOnGround.get() && !mc.player.isOnGround()) return "land first";
        return serverGliding ? "soaring" : "lighting";
    }
}
