package com.quiettee.utils.modules.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.Optional;
import java.util.function.BiPredicate;

public final class BoatShotTrajectory {
    public static boolean clear(Vec3d muzzle, Vec3d velocity, double ticks, Box target,
                                Vec3d motion, double extraLead, BiPredicate<Vec3d,Vec3d> clearSegment) {
        return trace(muzzle,velocity,ticks,target,motion,extraLead,clearSegment,false);
    }
    public static boolean clearDiscrete(Vec3d muzzle, Vec3d velocity, double ticks, Box target,
                                Vec3d motion, double extraLead, BiPredicate<Vec3d,Vec3d> clearSegment) {
        return trace(muzzle,velocity,ticks,target,motion,extraLead,clearSegment,true);
    }
    private static boolean trace(Vec3d muzzle, Vec3d velocity, double ticks, Box target,
                                Vec3d motion, double extraLead, BiPredicate<Vec3d,Vec3d> clearSegment,boolean discrete) {
        if (!Double.isFinite(ticks) || ticks <= 0 || ticks > 80) return false;
        Vec3d from = muzzle;
        Box hitbox = target.expand(.1);
        for (int i=0;i<Math.ceil(ticks);i++) {
            double fraction = Math.min(1,ticks-i);
            Vec3d to = from.add(velocity.multiply(fraction));
            Vec3d shift = motion.multiply(extraLead+i+(discrete?1:0));
            Vec3d a = from.subtract(shift), b = to.subtract(shift).subtract(discrete?Vec3d.ZERO:motion.multiply(fraction));
            Optional<Vec3d> contact = hitbox.contains(a) ? Optional.of(a) : hitbox.raycast(a,b);
            if (contact.isPresent()) {
                double length = a.distanceTo(b), portion = length < 1e-9 ? 0 : a.distanceTo(contact.get())/length;
                to = from.lerp(to,Math.max(0,Math.min(1,portion)));
            }
            if (!clearSegment.test(from,to)) return false;
            if (contact.isPresent()) return true;
            from = to;
            velocity = velocity.multiply(.99).add(0,-.05,0);
        }
        return false;
    }
}
