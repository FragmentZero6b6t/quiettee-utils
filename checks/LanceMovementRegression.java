package com.quiettee.utils.modules.combat;

import java.util.Random;

public final class LanceMovementRegression {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {

        double scale = LanceMovementMath.capScale(10.98, 3.40, 0.02, 2.8, 1.2);
        check(Math.hypot(10.98 * scale, 0.02 * scale) <= 2.8 + 1e-12, "logged leash jump must obey H cap");
        check(Math.abs(3.40 * scale) <= 1.2 + 1e-12, "logged leash jump must obey V cap");
        check(LanceMovementMath.capScale(0, -1.6, 0, 2.8, 1.65) == 1, "unchanged valid strike dip");
        check(LanceMovementMath.capScale(0, 0, 0, 2.8, 1.2) == 1, "stationary claim");

        LanceMovementMath.Step approach = LanceMovementMath.approachStep(18, -1.6, 0, 2.8, 1.4);
        check(Math.abs(approach.y() + 1.4) < 1e-12, "capture closes the real height error despite a distant sideways target");
        check(Math.hypot(approach.x(), approach.y()) <= 2.8 + 1e-12, "capture spends the existing total budget rather than adding speed");
        approach = LanceMovementMath.approachStep(0.13, -0.05, 0, 2.8, 1.2);
        check(Math.abs(approach.x() - 0.13) < 1e-12 && Math.abs(approach.y() + 0.05) < 1e-12,
            "logged near-launch residue is resolved without overshoot");
        approach = LanceMovementMath.approachStep(0, 3, 0, 2.8, 1.2);
        check(approach.y() == 1.2 && approach.x() == 0 && approach.z() == 0, "pure ascent retains ordinary approach cap");
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            approach = LanceMovementMath.approachStep(bad, 1, 2, 2.8, 1.2);
            check(approach.equals(new LanceMovementMath.Step(0, 0, 0)), "malformed pursuit displacement fails closed");
        }

        check(LanceMovementMath.followFraction(0, 20, 0, 0, 1.4, 0, 20) == 0, "blocked body must not climb farther away");
        check(LanceMovementMath.followFraction(0, 20, 0, 0, -1.4, 0, 20) == 1, "blocked body may close the gap");
        check(LanceMovementMath.followFraction(40, 0, 0, 3, 0, 0, 28.5) == 0, "overrun cannot grow");
        check(LanceMovementMath.followFraction(40, 0, 0, -3, 0, 0, 28.5) == 1, "overrun may converge");

        Random random = new Random(121);
        for (int i = 0; i < 10000; i++) {
            double dx = random.nextDouble() * 100 - 50, dy = random.nextDouble() * 100 - 50, dz = random.nextDouble() * 100 - 50;
            double maxH = 0.1 + random.nextDouble() * 3, maxV = 0.1 + random.nextDouble() * 2;
            scale = LanceMovementMath.capScale(dx, dy, dz, maxH, maxV);
            check(Double.isFinite(scale) && scale >= 0 && scale <= 1, "finite bounded scale");
            check(Math.hypot(dx * scale, dz * scale) <= maxH + 1e-10, "random H cap");
            check(Math.abs(dy * scale) <= maxV + 1e-10, "random V cap");
            approach = LanceMovementMath.approachStep(dx, dy, dz, maxH, maxV);
            check(Math.sqrt(approach.x() * approach.x() + approach.y() * approach.y() + approach.z() * approach.z()) <= maxH + 1e-10,
                "pursuit complete displacement respects its unchanged total budget");
            check(Math.abs(approach.y()) <= maxV + 1e-10 && Math.abs(approach.y()) <= Math.abs(dy) + 1e-10,
                "pursuit respects vertical cap and cannot overshoot height");
            check(Math.hypot(approach.x(), approach.z()) <= Math.hypot(dx, dz) + 1e-10
                && approach.x() * dx >= 0 && approach.z() * dz >= 0 && approach.y() * dy >= 0,
                "pursuit never reverses or overshoots its target components");
            double rx = random.nextDouble() * 80 - 40, ry = random.nextDouble() * 80 - 40, rz = random.nextDouble() * 80 - 40;
            double leash = 1 + random.nextDouble() * 40;
            double fraction = LanceMovementMath.followFraction(rx, ry, rz, dx, dy, dz, leash);
            double oldSq = rx * rx + ry * ry + rz * rz;
            double nextSq = Math.pow(rx + dx * fraction, 2) + Math.pow(ry + dy * fraction, 2) + Math.pow(rz + dz * fraction, 2);
            check(Double.isFinite(fraction) && fraction >= 0 && fraction <= 1, "finite follow fraction");
            check(nextSq <= Math.max(oldSq, leash * leash) + 1e-8, "follow cannot create/worsen overrun");
        }

        double vy = 0;
        for (int i = 0; i < 60; i++) {
            vy = LanceMovementMath.nextRecoveryVelocity(vy, 0.08);
            check(vy < 0, "airborne wings-off recovery must descend without a real jump");
        }
        check(Math.abs(LanceMovementMath.nextRecoveryVelocity(0, 0.08) + 0.0784) < 1e-12, "ordinary gravity first step");
        check(Math.abs(LanceMovementMath.nextRecoveryVelocity(0, 0.01) + 0.0098) < 1e-12, "Slow Falling gravity first step");
        vy = 0.42;
        for (int i = 0; i < 6; i++) vy = LanceMovementMath.nextRecoveryVelocity(vy, 0.08);
        check(vy < 0, "single jump must turn into descent without server flight confirmation");
        check(!LanceMovementMath.plausibleStrikeAge(0, 2), "same-tick hurt is unrelated");
        check(!LanceMovementMath.plausibleStrikeAge(1, 2), "hurt before plausible RTT is unrelated");
        check(LanceMovementMath.plausibleStrikeAge(2, 2), "RTT-aged hurt is only plausible");
        check(!LanceMovementMath.plausibleStrikeAge(9, 2), "expired strike is unrelated");
        System.out.println("Lance movement regression passed: " + checks + " assertions");
    }
}
