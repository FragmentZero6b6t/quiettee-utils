package com.quiettee.utils.modules.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class BoatShotCrosshair {
    public record Score(double cosine, double distance, Vec3d point) {
        public boolean betterThan(Score other) {
            return other == null || cosine > other.cosine + 1e-10
                || Math.abs(cosine - other.cosine) <= 1e-10 && distance < other.distance;
        }
    }

    public static Score score(Box body, Vec3d eye, Vec3d direction, double range) {
        if (!Double.isFinite(range) || range <= 0 || !Double.isFinite(direction.lengthSquared())
            || direction.lengthSquared() < 1e-12) return null;
        Vec3d view = direction.normalize();
        if (body.contains(eye)) return new Score(1, 0, eye);
        var hit = body.raycast(eye, eye.add(view.multiply(range)));
        if (hit.isPresent()) return new Score(1, eye.distanceTo(hit.get()), hit.get());
        Vec3d[] corners = new Vec3d[8];
        for (int i = 0; i < 8; i++) corners[i] = new Vec3d((i & 1) == 0 ? body.minX : body.maxX,
            (i & 2) == 0 ? body.minY : body.maxY, (i & 4) == 0 ? body.minZ : body.maxZ).subtract(eye);
        Score best = null;
        for (int i = 0; i < 8; i++) {
            best = consider(best, corners[i], eye, view, range);
            for (int axis : new int[] {1, 2, 4}) {
                if ((i & axis) != 0) continue;
                Vec3d start = corners[i], edge = corners[i | axis].subtract(start);
                double a = view.dotProduct(start), b = view.dotProduct(edge);
                double c = start.lengthSquared(), d = start.dotProduct(edge), e = edge.lengthSquared();
                double denominator = b * d - a * e;
                if (Math.abs(denominator) < 1e-12) continue;
                double t = (a * d - b * c) / denominator;
                if (t > 0 && t < 1) best = consider(best, start.add(edge.multiply(t)), eye, view, range);
            }
        }
        return best;
    }

    private static Score consider(Score best, Vec3d offset, Vec3d eye, Vec3d view, double range) {
        double distance = offset.length();
        if (!Double.isFinite(distance) || distance < 1e-10 || distance > range) return best;
        double cosine = view.dotProduct(offset) / distance;
        if (cosine <= 0) return best;
        Score candidate = new Score(Math.min(1, cosine), distance, eye.add(offset));
        return candidate.betterThan(best) ? candidate : best;
    }
}
