package com.quiettee.utils.modules.movement.elytramotion;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import net.minecraft.entity.player.PlayerEntity;

public class ElytraDive extends ElytraMotion {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> targetName = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Player name. Empty dives on the nearest.")
        .defaultValue("")
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Max target distance.")
        .defaultValue(64)
        .min(4)
        .sliderMax(256)
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("target-priority")
        .description("How to pick a target without a name.")
        .defaultValue(SortPriority.LowestDistance)
        .build()
    );

    private final Setting<Double> perchHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("perch-height")
        .description("Climb height above the target before diving.")
        .defaultValue(18)
        .min(2)
        .sliderMax(60)
        .build()
    );

    private final Setting<Double> perchOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("perch-offset")
        .description("Horizontal perch offset. 0 is overhead.")
        .defaultValue(4)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> pullupHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("pull-up-height")
        .description("Pull up this close to the ground.")
        .defaultValue(3)
        .min(0.5)
        .sliderMax(12)
        .build()
    );

    private final Setting<Double> diveSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("dive-speed")
        .description("Descent speed in blocks per tick.")
        .defaultValue(1.5)
        .min(0.5)
        .sliderRange(0.5, 4)
        .build()
    );

    private final Setting<Integer> perchPause = sgGeneral.add(new IntSetting.Builder()
        .name("perch-pause")
        .description("Ticks to hover before dropping.")
        .defaultValue(0)
        .min(0)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat")
        .description("Keep diving until turned off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tightStrike = sgGeneral.add(new BoolSetting.Builder()
        .name("tight-strike")
        .description("Dive again right after pulling up.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> faceTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("face-target")
        .description("Keep the server side look on the target.")
        .defaultValue(true)
        .build()
    );

    private PlayerEntity target;
    private Phase phase;
    private int pauseTicks;

    public ElytraDive() {
        super("elytra-dive", "Perch above a player and dive on them.");
    }

    @Override
    protected void onStart() {
        target = null;
        phase = Phase.Climb;
        pauseTicks = 0;
    }

    @Override
    protected void fly() {
        if (!validTarget(target, range.get())) target = findTarget(targetName.get(), range.get(), priority.get());

        if (target == null) {
            clearDesired();
            return;
        }

        double tx = target.getX(), ty = target.getY(), tz = target.getZ();

        double dx = mc.player.getX() - tx, dz = mc.player.getZ() - tz;
        double h = Math.sqrt(dx * dx + dz * dz);
        double ox = h > 1e-6 ? dx / h * perchOffset.get() : perchOffset.get();
        double oz = h > 1e-6 ? dz / h * perchOffset.get() : 0;
        double perchX = tx + ox, perchY = ty + perchHeight.get(), perchZ = tz + oz;

        if (phase == Phase.Climb) {
            steerTo(perchX, perchY, perchZ);
            double gap = Math.abs(mc.player.getY() - perchY) + Math.hypot(mc.player.getX() - perchX, mc.player.getZ() - perchZ);
            if (gap < 1.5) {
                if (perchPause.get() > 0) {
                    phase = Phase.Perch;
                    pauseTicks = perchPause.get();
                } else {
                    phase = Phase.Dive;
                }
            }
        }

        if (phase == Phase.Perch) {
            steerTo(perchX, perchY, perchZ);
            if (--pauseTicks <= 0) phase = Phase.Dive;
        }

        if (phase == Phase.Dive) {

            maxDownOverride = diveSpeed.get();

            double ddx = tx - mc.player.getX(), ddz = tz - mc.player.getZ();
            double dh = Math.sqrt(ddx * ddx + ddz * ddz);
            double maxH = maxHorizontal.get();
            double vx = 0, vz = 0;
            if (dh > 1e-6) {
                double s = Math.min(dh, maxH);
                vx = ddx / dh * s;
                vz = ddz / dh * s;
            }
            setDesired(vx, -diveSpeed.get(), vz);

            double margin = Math.max(pullupHeight.get(), diveSpeed.get() * 1.1);
            double aboveGround = heightAboveGround(64);
            double aboveTarget = mc.player.getY() - ty;
            if (aboveGround <= margin || aboveTarget <= pullupHeight.get()) {
                phase = Phase.PullUp;
                maxDownOverride = -1;
            }
        }

        if (phase == Phase.PullUp) {

            maxDownOverride = -1;
            setDesired(0, maxVertical.get(), 0);

            double topY = tightStrike.get() ? ty + pullupHeight.get() + 1.0 : ty + perchHeight.get() * 0.6;
            if (mc.player.getY() >= topY) {
                if (repeat.get()) phase = tightStrike.get() ? Phase.Dive : Phase.Climb;
                else {
                    info("Strike complete.");
                    toggle();
                }
            }
        }

        if (faceTarget.get()) lookAt(tx, target.getEyeY(), tz);
    }

    @Override
    public String getInfoString() {
        return target == null ? null : EntityUtils.getName(target) + " · " + phase;
    }

    public enum Phase {
        Climb,
        Perch,
        Dive,
        PullUp
    }
}
