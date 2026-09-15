package com.quiettee.utils.util;

public final class MaceMovementMath {
    private MaceMovementMath() {}

    public static double offsetAfterMove(double previousClaimY, double plannedDy, double currentRealY) {
        return previousClaimY + plannedDy - currentRealY;
    }

    public static double ghostY(boolean atDive, double diveY, double realY, double offset) {
        return atDive ? diveY : realY + offset;
    }

    public record Sweep(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    public static Sweep sweptBody(double x0, double y0, double z0, double x1, double y1, double z1, double halfWidth, double height) {
        return new Sweep(Math.min(x0, x1) - halfWidth, Math.min(y0, y1), Math.min(z0, z1) - halfWidth,
            Math.max(x0, x1) + halfWidth, Math.max(y0, y1) + height, Math.max(z0, z1) + halfWidth);
    }
}
