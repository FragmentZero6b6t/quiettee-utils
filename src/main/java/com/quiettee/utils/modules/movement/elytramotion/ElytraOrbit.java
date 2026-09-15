package com.quiettee.utils.modules.movement.elytramotion;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import net.minecraft.entity.player.PlayerEntity;

public class ElytraOrbit extends ElytraMotion {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAvoid = settings.createGroup("Obstacle Avoidance");

    private final Setting<String> targetName = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Player name. Empty orbits the nearest.")
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

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Orbit radius in blocks.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> degreesPerTick = sgGeneral.add(new DoubleSetting.Builder()
        .name("degrees-per-tick")
        .description("Angular speed, capped by the speed envelope.")
        .defaultValue(12)
        .min(0.5)
        .sliderMax(45)
        .build()
    );

    private final Setting<Boolean> clockwise = sgGeneral.add(new BoolSetting.Builder()
        .name("clockwise")
        .description("Orbit direction.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Plane> plane = sgGeneral.add(new EnumSetting.Builder<Plane>()
        .name("plane")
        .description("Horizontal circle or vertical loop.")
        .defaultValue(Plane.Horizontal)
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
        .description("Orbit centre height above the anchor.")
        .defaultValue(0)
        .min(-8)
        .sliderRange(-8, 20)
        .build()
    );

    private final Setting<Double> helix = sgGeneral.add(new DoubleSetting.Builder()
        .name("helix")
        .description("Climb per tick added to the orbit.")
        .defaultValue(0)
        .min(-1)
        .sliderRange(-1, 1)
        .visible(() -> plane.get() == Plane.Horizontal)
        .build()
    );

    private final Setting<Double> helixRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("helix-range")
        .description("Blocks climbed before the helix reverses.")
        .defaultValue(10)
        .min(1)
        .sliderMax(60)
        .visible(() -> plane.get() == Plane.Horizontal && helix.get() != 0)
        .build()
    );

    private final Setting<Boolean> faceTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("face-target")
        .description("Keep the server side look on the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> avoid = sgAvoid.add(new BoolSetting.Builder()
        .name("avoid-obstacles")
        .description("Fly around terrain on the arc.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lookAhead = sgAvoid.add(new IntSetting.Builder()
        .name("look-ahead")
        .description("Ticks of arc checked for obstacles.")
        .defaultValue(6)
        .min(1)
        .sliderMax(20)
        .visible(avoid::get)
        .build()
    );

    private final Setting<Double> climbRate = sgAvoid.add(new DoubleSetting.Builder()
        .name("climb-rate")
        .description("Lift per tick when something is ahead.")
        .defaultValue(0.5)
        .min(0.1)
        .sliderMax(1.5)
        .visible(avoid::get)
        .build()
    );

    private final Setting<Integer> maxClimb = sgAvoid.add(new IntSetting.Builder()
        .name("max-climb")
        .description("Max lift before tightening or reversing.")
        .defaultValue(8)
        .min(1)
        .sliderMax(30)
        .visible(avoid::get)
        .build()
    );

    private PlayerEntity target;
    private double angle;
    private double helixOffset;
    private int helixDirection = 1;
    private double baseYaw;
    private boolean reversed;
    private int reverseCooldown;
    private double lift;
    private double radiusScale = 1;

    private final double[] point = new double[3];

    public ElytraOrbit() {
        super("elytra-orbit", "Circle a player on the elytra.");
    }

    @Override
    protected void onStart() {
        target = null;
        angle = 0;
        helixOffset = 0;
        helixDirection = 1;
        reversed = false;
        reverseCooldown = 0;
        lift = 0;
        radiusScale = 1;
    }

    @Override
    protected void fly() {
        if (!validTarget(target, range.get())) {
            target = findTarget(targetName.get(), range.get(), priority.get());
            if (target != null) {

                double dx = mc.player.getX() - target.getX();
                double dz = mc.player.getZ() - target.getZ();
                angle = Math.toDegrees(Math.atan2(dz, dx));
                baseYaw = angle;
            }
        }

        if (target == null) {
            clearDesired();
            return;
        }

        if (avoid.get() && insideBlock()) {
            setDesired(0, maxVertical.get(), 0);
            lift = Math.min(lift + 1, maxClimb.get());
            return;
        }

        double r = radius.get() * radiusScale;
        double maxStepDeg = Math.toDegrees(maxHorizontal.get() / r) * 0.9;
        if (plane.get() == Plane.Vertical) maxStepDeg = Math.min(maxStepDeg, Math.toDegrees(maxVertical.get() / r) * 0.9);
        double step = Math.min(degreesPerTick.get(), maxStepDeg);

        boolean cw = clockwise.get() ^ reversed;
        double signedStep = cw ? step : -step;
        angle += signedStep;
        if (reverseCooldown > 0) reverseCooldown--;

        if (plane.get() == Plane.Horizontal && helix.get() != 0) {
            helixOffset += helix.get() * helixDirection;
            if (Math.abs(helixOffset) >= helixRange.get()) helixDirection = -helixDirection;
        }

        double cx = target.getX();
        double cy = (anchor.get() == Anchor.Head ? target.getEyeY() : target.getY()) + height.get() + helixOffset;
        double cz = target.getZ();

        if (avoid.get()) {

            boolean obstacleAhead = false;
            double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
            for (int n = 0; n < lookAhead.get(); n++) {
                orbitPoint(angle + signedStep * n, cx, cy + lift, cz, r, point);
                if (!pathClear(px, py, pz, point[0], point[1], point[2])) {
                    obstacleAhead = true;
                    break;
                }
                px = point[0];
                py = point[1];
                pz = point[2];
            }

            if (obstacleAhead) lift = Math.min(lift + climbRate.get(), maxClimb.get());
            else lift = Math.max(0, lift - climbRate.get() * 0.5);

            orbitPoint(angle, cx, cy + lift, cz, r, point);
            if (!pathClear(mc.player.getX(), mc.player.getY(), mc.player.getZ(), point[0], point[1], point[2])) {
                boolean fixed = false;
                for (double f = 0.75; f >= 0.25 && !fixed; f -= 0.25) {
                    orbitPoint(angle, cx, cy + lift, cz, r * f, point);
                    if (pathClear(mc.player.getX(), mc.player.getY(), mc.player.getZ(), point[0], point[1], point[2])) {
                        radiusScale = f;
                        fixed = true;
                    }
                }

                if (!fixed) {
                    orbitPoint(angle, cx, cy + lift, cz, r, point);
                    if (reverseCooldown == 0) {
                        reversed = !reversed;
                        reverseCooldown = 20;
                        angle -= 2 * signedStep;
                        orbitPoint(angle, cx, cy + lift, cz, r, point);
                    }
                }
            } else {

                radiusScale = Math.min(1, radiusScale + 0.05);
            }
        } else {
            orbitPoint(angle, cx, cy, cz, r, point);
        }

        steerTo(point[0], point[1], point[2]);
        if (faceTarget.get()) lookAt(target.getX(), target.getEyeY(), target.getZ());
    }

    private void orbitPoint(double angleDeg, double cx, double cy, double cz, double r, double[] out) {
        double rad = Math.toRadians(angleDeg);
        if (plane.get() == Plane.Horizontal) {
            out[0] = cx + Math.cos(rad) * r;
            out[1] = cy;
            out[2] = cz + Math.sin(rad) * r;
        } else {
            double yawRad = Math.toRadians(baseYaw);
            double along = Math.cos(rad) * r;
            out[0] = cx + Math.cos(yawRad) * along;
            out[1] = cy + Math.sin(rad) * r;
            out[2] = cz + Math.sin(yawRad) * along;
        }
    }

    @Override
    public String getInfoString() {
        return target == null ? null : EntityUtils.getName(target);
    }

    public enum Plane {
        Horizontal,
        Vertical
    }

    public enum Anchor {
        Head,
        Feet
    }
}
