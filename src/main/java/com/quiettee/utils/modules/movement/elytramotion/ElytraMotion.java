package com.quiettee.utils.modules.movement.elytramotion;

import com.quiettee.utils.QuietteeUtils;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IRaycastContext;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public abstract class ElytraMotion extends Module {
    protected final SettingGroup sgEnvelope = settings.createGroup("Speed Envelope");

    public final Setting<Double> maxHorizontal = sgEnvelope.add(new DoubleSetting.Builder()
        .name("max-horizontal-speed")
        .description("Horizontal speed cap in blocks per tick.")
        .defaultValue(3.0)
        .min(0.1)
        .sliderRange(0.1, 6)
        .build()
    );

    public final Setting<Double> maxVertical = sgEnvelope.add(new DoubleSetting.Builder()
        .name("max-vertical-speed")
        .description("Vertical speed cap in blocks per tick.")
        .defaultValue(1.5)
        .min(0.1)
        .sliderRange(0.1, 4)
        .build()
    );

    public final Setting<Boolean> autoTakeoff = sgEnvelope.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Jump and open the elytra automatically.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> chunkGuard = sgEnvelope.add(new BoolSetting.Builder()
        .name("chunk-guard")
        .description("Never fly into unloaded chunks.")
        .defaultValue(true)
        .build()
    );

    protected final Vec3d desired = new Vec3d(0, 0, 0);
    protected boolean hasDesired;
    protected boolean additive;

    protected static ElytraMotion priorityOwner;

    protected double maxDownOverride = -1;

    private RaycastContext raycastContext;
    private final Vec3d rayFrom = new Vec3d(0, 0, 0);
    private final Vec3d rayTo = new Vec3d(0, 0, 0);
    private int takeoffTimer;

    public ElytraMotion(String name, String description) {
        super(QuietteeUtils.CATEGORY, name, description);
    }

    @Override
    public void onActivate() {
        hasDesired = false;
        additive = false;
        takeoffTimer = 0;
        raycastContext = null;
        onStart();
    }

    private RaycastContext raycastContext() {
        if (raycastContext == null) {
            raycastContext = new RaycastContext(new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        }
        return raycastContext;
    }

    @Override
    public void onDeactivate() {
        hasDesired = false;
        maxDownOverride = -1;
        if (priorityOwner == this) priorityOwner = null;
        onStop();
    }

    protected void onStart() {
    }

    protected void onStop() {
    }

    protected abstract void fly();

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        hasDesired = false;
        additive = false;

        if (!mc.player.isGliding()) {
            if (autoTakeoff.get()) handleTakeoff();
        }

        fly();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (!hasDesired || !mc.player.isGliding()) return;
        if (priorityOwner != null && priorityOwner != this && priorityOwner.isActive()) return;

        double vx = desired.x, vy = desired.y, vz = desired.z;
        if (additive) {
            vx += event.movement.x;
            vy += event.movement.y;
            vz += event.movement.z;
        }

        double h = Math.sqrt(vx * vx + vz * vz);
        double maxH = maxHorizontal.get();
        if (h > maxH) {
            vx = vx / h * maxH;
            vz = vz / h * maxH;
        }
        double maxV = maxVertical.get();
        double maxDown = maxDownOverride > 0 ? maxDownOverride : maxV;
        if (vy > maxV) vy = maxV;
        if (vy < -maxDown) vy = -maxDown;

        if (chunkGuard.get()) {
            int chunkX = (int) Math.floor((mc.player.getX() + vx) / 16);
            int chunkZ = (int) Math.floor((mc.player.getZ() + vz) / 16);
            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                vx = 0;
                vz = 0;
            }
        }

        ((IVec3d) event.movement).meteor$set(vx, vy, vz);
    }

    private void handleTakeoff() {
        if (mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) return;

        if (mc.player.isOnGround()) {
            if (takeoffTimer++ % 10 == 0) {
                mc.player.setSprinting(true);
                mc.player.jump();
            }
        } else if (mc.player.getVelocity().y < 0) {

            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    protected void setDesired(double vx, double vy, double vz) {
        ((IVec3d) desired).meteor$set(vx, vy, vz);
        hasDesired = true;
        additive = false;
    }

    protected void addDesired(double vx, double vy, double vz) {
        ((IVec3d) desired).meteor$set(vx, vy, vz);
        hasDesired = true;
        additive = true;
    }

    protected void clearDesired() {
        hasDesired = false;
    }

    protected void steerTo(double x, double y, double z) {
        double dx = x - mc.player.getX();
        double dy = y - mc.player.getY();
        double dz = z - mc.player.getZ();

        double h = Math.sqrt(dx * dx + dz * dz);
        double maxH = maxHorizontal.get();
        double vx = 0, vz = 0;
        if (h > 1e-6) {
            double step = Math.min(h, maxH);
            vx = dx / h * step;
            vz = dz / h * step;
        }

        double maxV = maxVertical.get();
        double vy = Math.max(-maxV, Math.min(maxV, dy));

        setDesired(vx, vy, vz);
    }

    protected boolean blocked(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        ((IVec3d) rayFrom).meteor$set(fromX, fromY, fromZ);
        ((IVec3d) rayTo).meteor$set(toX, toY, toZ);
        RaycastContext context = raycastContext();
        ((IRaycastContext) context).meteor$set(rayFrom, rayTo, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        return mc.world.raycast(context).getType() == HitResult.Type.BLOCK;
    }

    protected boolean spaceFree(double x, double y, double z) {
        net.minecraft.util.math.Box box = mc.player.getBoundingBox().offset(x - mc.player.getX(), y - mc.player.getY(), z - mc.player.getZ());
        return mc.world.isSpaceEmpty(mc.player, box);
    }

    protected boolean pathClear(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(dist / 0.5));
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            if (!spaceFree(fromX + dx * t, fromY + dy * t, fromZ + dz * t)) return false;
        }
        return true;
    }

    protected boolean insideBlock() {
        return !spaceFree(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    protected double heightAboveGround(double maxDepth) {
        ((IVec3d) rayFrom).meteor$set(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        ((IVec3d) rayTo).meteor$set(mc.player.getX(), mc.player.getY() - maxDepth, mc.player.getZ());
        RaycastContext context = raycastContext();
        ((IRaycastContext) context).meteor$set(rayFrom, rayTo, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        var hit = mc.world.raycast(context);
        if (hit.getType() != HitResult.Type.BLOCK) return maxDepth;
        return mc.player.getY() - hit.getPos().y;
    }

    protected void lookAt(double x, double y, double z) {
        Rotations.rotate(Rotations.getYaw(new Vec3d(x, y, z)), Rotations.getPitch(new Vec3d(x, y, z)));
    }

    protected PlayerEntity findTarget(String name, double range, SortPriority priority) {
        if (name != null && !name.isBlank()) {
            for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                if (player.getGameProfile().name().equalsIgnoreCase(name.trim())) {
                    return player.isAlive() && mc.player.distanceTo(player) <= range ? player : null;
                }
            }
            return null;
        }

        PlayerEntity target = TargetUtils.getPlayerTarget(range, priority);
        return TargetUtils.isBadTarget(target, range) ? null : target;
    }

    protected boolean validTarget(PlayerEntity target, double range) {
        return target != null && target.isAlive() && !target.isRemoved() && mc.player.distanceTo(target) <= range;
    }
}
