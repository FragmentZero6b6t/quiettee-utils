package com.quiettee.utils.modules.combat;

import com.quiettee.utils.QuietteeUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Set;

public class Fusillade extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgCharge = settings.createGroup("Charging");

    private final SettingGroup sgAim = settings.createGroup("Aim");

    public enum ChargeWhen { Holding, Always }

    public enum Aim { Off, Direct, Ballistic }

    private final Setting<Keybind> fireKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("fire-key")
        .description("Fire the volley.")
        .defaultValue(Keybind.none())
        .action(() -> startVolley(false))
        .build()
    );

    private final Setting<Boolean> fireOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("fire-on-use")
        .description("Right click fires the whole hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> burst = sgGeneral.add(new IntSetting.Builder()
        .name("burst")
        .description("Max crossbows per volley.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("shots-per-tick")
        .description("Crossbows fired in the same tick.")
        .defaultValue(3)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> cadence = sgGeneral.add(new IntSetting.Builder()
        .name("cadence")
        .description("Ticks between shots. 0 fires all at once.")
        .defaultValue(0)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Return to your original slot afterwards.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info")
        .description("Report each volley in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoCharge = sgCharge.add(new BoolSetting.Builder()
        .name("auto-charge")
        .description("Keep every hotbar crossbow charged.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ChargeWhen> chargeWhen = sgCharge.add(new EnumSetting.Builder<ChargeWhen>()
        .name("charge-when")
        .description("Holding a crossbow, or always.")
        .defaultValue(ChargeWhen.Holding)
        .visible(autoCharge::get)
        .build()
    );

    private final Setting<Integer> chargeSlack = sgCharge.add(new IntSetting.Builder()
        .name("charge-slack")
        .description("Extra ticks held past the charge time.")
        .defaultValue(2)
        .range(0, 10)
        .sliderRange(0, 10)
        .visible(autoCharge::get)
        .build()
    );

    private final Setting<Integer> chargeGap = sgCharge.add(new IntSetting.Builder()
        .name("charge-gap")
        .description("Ticks between reloading each crossbow.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 20)
        .visible(autoCharge::get)
        .build()
    );

    private final Setting<Aim> aim = sgAim.add(new EnumSetting.Builder<Aim>()
        .name("aim")
        .description("Off, at the chest, or with drop compensation.")
        .defaultValue(Aim.Off)
        .build()
    );

    private final Setting<Double> range = sgAim.add(new DoubleSetting.Builder()
        .name("range")
        .description("Max target distance.")
        .defaultValue(24)
        .min(0)
        .sliderMax(64)
        .visible(() -> aim.get() != Aim.Off)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgAim.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("What to aim at.")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER)
        .visible(() -> aim.get() != Aim.Off)
        .build()
    );

    private final Setting<SortPriority> priority = sgAim.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("Which target to pick.")
        .defaultValue(SortPriority.LowestDistance)
        .visible(() -> aim.get() != Aim.Off)
        .build()
    );

    private enum Phase { IDLE, START, HOLD, VERIFY }
    private Phase phase = Phase.IDLE;
    private int phaseTicks, gapTicks, retries;
    private int chargeSlot = -1, rackSlot = -1;
    private final int[] fails = new int[9];
    private boolean keyHeld;

    private boolean firing;
    private final IntArrayList queue = new IntArrayList();
    private int returnSlot = -1, cadenceTimer, volleyTicks, shots, bolts, useCooldown;

    private final long[] spentUntil = new long[9];

    public Fusillade() {
        super(QuietteeUtils.CATEGORY, "fusillade", "Fire every charged crossbow in your hotbar at once.");
    }

    @Override
    public void onActivate() {
        phase = Phase.IDLE;
        firing = false;
        queue.clear();
        rackSlot = -1;
        returnSlot = -1;
        gapTicks = 0;
        useCooldown = 0;
        Arrays.fill(fails, 0);
        Arrays.fill(spentUntil, 0);
    }

    @Override
    public void onDeactivate() {
        stopCharging();
        firing = false;
        queue.clear();
        hold(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (useCooldown > 0) useCooldown--;

        if (firing) {
            tickVolley();
            return;
        }

        if (mc.currentScreen != null) {
            if (phase != Phase.IDLE) stopCharging();
            return;
        }

        if (fireOnUse.get() && useCooldown == 0 && phase == Phase.IDLE && mc.options.useKey.isPressed() && isLive(selectedSlot())) {
            startVolley(true);
            return;
        }

        if (autoCharge.get()) tickCharge();
        else if (phase != Phase.IDLE) stopCharging();
    }

    private void tickCharge() {
        switch (phase) {
            case IDLE -> {
                if (gapTicks > 0) {
                    gapTicks--;
                    return;
                }
                if (chargeWhen.get() == ChargeWhen.Holding && !isCrossbow(selectedSlot())) {
                    rackSlot = -1;
                    return;
                }
                if (mc.player.isUsingItem() || !hasAmmo()) return;

                int slot = nextToCharge();
                if (slot == -1) {
                    restoreRack();
                    return;
                }
                if (rackSlot == -1) rackSlot = selectedSlot();
                if (selectedSlot() != slot) InvUtils.swap(slot, false);
                chargeSlot = slot;
                phase = Phase.START;
                phaseTicks = 0;
                retries = 0;
            }
            case START -> {
                if (selectedSlot() != chargeSlot || !isCrossbow(chargeSlot)) {
                    stopCharging();
                    return;
                }
                hold(true);
                if (mc.player.isUsingItem()) {
                    phase = Phase.HOLD;
                    phaseTicks = 0;
                } else if (++phaseTicks > 10) {
                    fails[chargeSlot]++;
                    stopCharging();
                }
            }
            case HOLD -> {
                if (selectedSlot() != chargeSlot || !mc.player.isUsingItem()) {
                    stopCharging();
                    return;
                }
                hold(true);
                phaseTicks++;
                int need = CrossbowItem.getPullTime(stack(chargeSlot), mc.player) + chargeSlack.get();
                if (phaseTicks >= need) {
                    hold(false);
                    mc.interactionManager.stopUsingItem(mc.player);
                    phase = Phase.VERIFY;
                    phaseTicks = 0;
                }
            }
            case VERIFY -> {
                if (isCrossbow(chargeSlot) && CrossbowItem.isCharged(stack(chargeSlot))) {
                    fails[chargeSlot] = 0;
                    phase = Phase.IDLE;
                    gapTicks = chargeGap.get();
                    return;
                }
                if (++phaseTicks > 15) {
                    if (++retries <= 2) {
                        phase = Phase.START;
                        phaseTicks = 0;
                    } else {
                        fails[chargeSlot]++;
                        phase = Phase.IDLE;
                        gapTicks = chargeGap.get();
                    }
                }
            }
        }
    }

    private void stopCharging() {
        hold(false);
        if (phase == Phase.HOLD && mc.player != null && mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);
        phase = Phase.IDLE;
        gapTicks = chargeGap.get();
    }

    private void restoreRack() {
        if (rackSlot != -1 && swapBack.get() && selectedSlot() != rackSlot) InvUtils.swap(rackSlot, false);
        rackSlot = -1;
    }

    private int nextToCharge() {
        int sel = selectedSlot();
        if (chargeable(sel)) return sel;
        for (int i = 0; i < 9; i++) if (chargeable(i)) return i;
        return -1;
    }

    private boolean chargeable(int slot) {
        return isCrossbow(slot) && !CrossbowItem.isCharged(stack(slot)) && fails[slot] < 3;
    }

    private void hold(boolean pressed) {
        if (pressed == keyHeld) return;
        mc.options.useKey.setPressed(pressed);
        keyHeld = pressed;
    }

    private void startVolley(boolean fromUse) {
        if (firing || mc.player == null || mc.world == null) return;
        if (phase != Phase.IDLE) stopCharging();

        queue.clear();
        int sel = selectedSlot();

        if (!fromUse && isLive(sel)) queue.add(sel);
        for (int i = 0; i < 9 && queue.size() < burst.get(); i++) if (i != sel && isLive(i)) queue.add(i);
        if (fromUse) spentUntil[sel] = mc.world.getTime() + 20;

        if (queue.isEmpty()) {
            if (fromUse) useCooldown = 10;
            return;
        }

        firing = true;
        returnSlot = sel;
        cadenceTimer = 0;
        volleyTicks = 0;
        shots = 0;
        bolts = 0;
        useCooldown = 20;
        if (!fromUse) tickVolley();
    }

    private void tickVolley() {
        volleyTicks++;

        int n;
        if (cadence.get() > 0) {
            if (cadenceTimer > 0) {
                cadenceTimer--;
                return;
            }
            n = 1;
            cadenceTimer = cadence.get() - 1;
        } else {
            n = perTick.get();
        }

        if (aim.get() != Aim.Off) {
            Entity target = findTarget();
            if (target != null) {
                double[] look = solve(target);
                final int count = n;
                Rotations.rotate(look[0], look[1], 10, () -> fire(count));
                return;
            }
        }

        fire(n);
    }

    private void fire(int n) {
        if (mc.player == null || mc.world == null) {
            firing = false;
            queue.clear();
            return;
        }

        for (int k = 0; k < n && !queue.isEmpty(); ) {
            int slot = queue.removeInt(0);
            if (!isLive(slot)) continue;
            InvUtils.swap(slot, false);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            spentUntil[slot] = mc.world.getTime() + 20;
            shots++;
            bolts += boltCount(stack(slot));
            k++;
        }

        if (queue.isEmpty()) finishVolley();
    }

    private void finishVolley() {
        firing = false;
        if (swapBack.get() && returnSlot != -1 && selectedSlot() != returnSlot) InvUtils.swap(returnSlot, false);
        returnSlot = -1;
        gapTicks = chargeGap.get();
        if (chatInfo.get()) info("Volley: %d crossbow%s, %d bolt%s in %d tick%s.", shots, shots == 1 ? "" : "s", bolts, bolts == 1 ? "" : "s", volleyTicks, volleyTicks == 1 ? "" : "s");
    }

    private Entity findTarget() {
        return TargetUtils.get(entity -> {
            if (entity == mc.player || entity == mc.getCameraEntity()) return false;
            if (!entity.isAlive() || (entity instanceof LivingEntity living && living.isDead())) return false;
            if (!PlayerUtils.isWithin(entity, range.get())) return false;
            if (!entities.get().contains(entity.getType())) return false;
            if (!PlayerUtils.canSeeEntity(entity)) return false;
            if (entity instanceof PlayerEntity player) {
                if (player.isCreative()) return false;
                return Friends.get().shouldAttack(player);
            }
            return true;
        }, priority.get());
    }

    private double[] solve(Entity target) {
        Vec3d pos = target.getEntityPos().add(0, target.getHeight() * 0.5, 0);
        double dx = pos.x - mc.player.getX();
        double dz = pos.z - mc.player.getZ();
        double dy = pos.y - mc.player.getEyeY();
        double h = Math.sqrt(dx * dx + dz * dz);

        double yaw = Rotations.getYaw(pos);
        double pitch = -Math.toDegrees(Math.atan2(dy, h));

        if (aim.get() == Aim.Ballistic && h > 0.01) {
            boolean fireworks = false;
            for (int i = 0; i < queue.size(); i++) {
                ChargedProjectilesComponent c = stack(queue.getInt(i)).get(DataComponentTypes.CHARGED_PROJECTILES);
                if (c != null && !c.isEmpty()) {
                    fireworks = c.contains(Items.FIREWORK_ROCKET);
                    break;
                }
            }
            double v = fireworks ? 1.6 : 3.15;
            double g = 0.055;
            double v2 = v * v;
            double disc = v2 * v2 - g * (g * h * h + 2 * dy * v2);
            if (disc >= 0) pitch = -Math.toDegrees(Math.atan((v2 - Math.sqrt(disc)) / (g * h)));
        }

        return new double[]{yaw, pitch};
    }

    private int selectedSlot() {
        return mc.player.getInventory().getSelectedSlot();
    }

    private ItemStack stack(int slot) {
        return mc.player.getInventory().getStack(slot);
    }

    private boolean isCrossbow(int slot) {
        return slot >= 0 && slot < 9 && stack(slot).getItem() instanceof CrossbowItem;
    }

    private boolean isLive(int slot) {
        return isCrossbow(slot) && CrossbowItem.isCharged(stack(slot)) && mc.world.getTime() >= spentUntil[slot];
    }

    private boolean hasAmmo() {
        return mc.player.getAbilities().creativeMode
            || InvUtils.find(s -> s.getItem() instanceof ArrowItem || s.isOf(Items.FIREWORK_ROCKET)).found();
    }

    private static int boltCount(ItemStack crossbow) {
        ChargedProjectilesComponent c = crossbow.get(DataComponentTypes.CHARGED_PROJECTILES);
        return c == null ? 0 : c.getProjectiles().size();
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        if (firing) return "FIRING";
        int total = 0, charged = 0;
        for (int i = 0; i < 9; i++) {
            if (!isCrossbow(i)) continue;
            total++;
            if (isLive(i)) charged++;
        }
        if (total == 0) return "no crossbows";
        return charged + "/" + total + (phase != Phase.IDLE ? " reloading" : "");
    }
}
