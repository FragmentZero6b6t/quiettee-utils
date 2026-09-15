package com.quiettee.utils.modules.movement.elytramotion;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

public class ElytraFollow extends ElytraMotion {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> targetName = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Player name. Empty follows the nearest.")
        .defaultValue("")
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Max target distance.")
        .defaultValue(128)
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

    private final Setting<Slot> slot = sgGeneral.add(new EnumSetting.Builder<Slot>()
        .name("formation")
        .description("Behind, beside, or their exact position.")
        .defaultValue(Slot.Behind)
        .build()
    );

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Horizontal gap for behind and beside.")
        .defaultValue(3)
        .min(0)
        .sliderMax(16)
        .visible(() -> slot.get() != Slot.Shadow)
        .build()
    );

    private final Setting<Anchor> anchor = sgGeneral.add(new EnumSetting.Builder<Anchor>()
        .name("anchor")
        .description("Measure height from their head or feet.")
        .defaultValue(Anchor.Head)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("Height above the anchor.")
        .defaultValue(2)
        .min(-8)
        .sliderRange(-8, 16)
        .build()
    );

    private final Setting<Double> leadTicks = sgGeneral.add(new DoubleSetting.Builder()
        .name("lead-ticks")
        .description("Ticks to predict their movement ahead.")
        .defaultValue(2)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> faceTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("face-target")
        .description("Keep the server side look on the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lostTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("lost-timeout")
        .description("Ticks to search before turning off. 0 never.")
        .defaultValue(100)
        .min(0)
        .sliderMax(600)
        .build()
    );

    private PlayerEntity target;
    private int lostTicks;

    public ElytraFollow() {
        super("elytra-follow", "Follow a player in formation on the elytra.");
    }

    @Override
    protected void onStart() {
        target = null;
        lostTicks = 0;
    }

    @Override
    protected void fly() {
        if (!validTarget(target, range.get())) target = findTarget(targetName.get(), range.get(), priority.get());

        if (target == null) {
            clearDesired();
            if (lostTimeout.get() > 0 && ++lostTicks >= lostTimeout.get()) {
                info("Target lost, turning off.");
                toggle();
            }
            return;
        }
        lostTicks = 0;

        double vx = target.getX() - target.lastX;
        double vy = target.getY() - target.lastY;
        double vz = target.getZ() - target.lastZ;
        double lead = leadTicks.get();

        double ax = target.getX() + vx * lead;
        double ay = (anchor.get() == Anchor.Head ? target.getEyeY() : target.getY()) + vy * lead + height.get();
        double az = target.getZ() + vz * lead;

        if (slot.get() != Slot.Shadow) {
            Vec3d look = Vec3d.fromPolar(0, target.getYaw());
            double d = distance.get();
            if (slot.get() == Slot.Behind) {
                ax -= look.x * d;
                az -= look.z * d;
            } else if (slot.get() == Slot.Beside) {
                Vec3d right = Vec3d.fromPolar(0, target.getYaw() + 90);
                ax += right.x * d;
                az += right.z * d;
            }
        }

        if (insideBlock()) {
            setDesired(0, maxVertical.get(), 0);
            lift = Math.min(lift + 1, 8);
        } else {
            if (!pathClear(mc.player.getX(), mc.player.getY(), mc.player.getZ(), ax, ay + lift, az)) lift = Math.min(lift + 0.5, 8);
            else lift = Math.max(0, lift - 0.25);
            steerTo(ax, ay + lift, az);
        }

        if (faceTarget.get()) lookAt(target.getX(), target.getEyeY(), target.getZ());
    }

    private double lift;

    @Override
    public String getInfoString() {
        return target == null ? null : EntityUtils.getName(target);
    }

    public enum Slot {
        Behind,
        Beside,
        Above,
        Shadow
    }

    public enum Anchor {
        Head,
        Feet
    }
}
