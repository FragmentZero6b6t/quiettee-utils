package com.quiettee.utils.modules.movement;

import java.util.Random;
import static com.quiettee.utils.modules.movement.BoatPhaseMotion.Point;

public final class BoatPhaseMotionTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) < 1e-8; }

    private static Point move(Point p, Point d) { return new Point(p.x() + d.x(), p.y() + d.y(), p.z() + d.z()); }

    public static void main(String[] args) {
        BoatPhaseMotion flight = new BoatPhaseMotion();
        Point position = new Point(12, 90, -32);
        flight.dive(30);
        for (int i = 0; i < 10; i++) {
            flight.nextTick();
            Point d = flight.plan(position, 0, 0, false, false, 0, 2.8, 3, 2, 126, true);
            check(near(d.y(), -3), "quick dive must move three blocks each tick");
            Point next = move(position, d);
            double pending = flight.diveRemaining();
            check(near(pending, 30 - i * 3), "planning alone must not spend unsent dive distance");
            check(flight.sent(position, next) == (i == 9), "only the final sent step completes the dive");
            check(flight.alreadySent(), "a sent packet reserves the client tick");
            check(!flight.sent(position, next), "duplicate movement cannot complete or spend the dive twice");
            check(near(flight.diveRemaining(), Math.max(0, pending - 3)), "only actual sent descent advances dive");
            position = next;
        }
        check(near(position.y(), 60), "default quick dive reproduces thirty blocks in half a second at 20 TPS");
        check(flight.diveRemaining() == 0, "quick dive must stop at its target");
        flight.nextTick();
        check(flight.plan(position, 0, 0, false, false, 0, 2.8, 3, 2, 126, false).equals(Point.ZERO), "idle boat must hover");

        flight.reset();
        flight.dive(30);
        flight.nextTick();
        Point first = flight.plan(position, 0, 0, false, false, 0, 2.8, 3, 2, 126, false);
        flight.sent(position, move(position, first));
        flight.corrected(8);
        check(flight.diveRemaining() == 0, "server correction cancels outstanding dive, never retries a stale destination");
        for (int i = 0; i < 8; i++) {
            check(flight.plan(position, 1, 1, true, false, 32, 9, 9, 2, 126, true).equals(Point.ZERO), "correction hold owns all controls");
            flight.dive(30);
            check(flight.diveRemaining() == 0, "a quick dive cannot start during correction hold");
            flight.nextTick();
        }
        check(flight.holdTicks() == 0, "hold expires after the configured ticks");
        check(flight.plan(position, 1, 0, false, false, 0, 2.8, 3, 2, 126, false).z() > 0, "ordinary flight resumes after correction");

        flight.reset();
        flight.nextTick();
        flight.dive(30);
        Point up = flight.plan(position, 0, 0, true, false, 0, 2.8, 3, 2, 126, false);
        check(near(up.y(), 3) && flight.diveRemaining() == 0, "jump immediately cancels dive and ascends");
        Point south = flight.plan(position, 1, 0, false, false, 0, 2.8, 3, 2, 126, false);
        Point west = flight.plan(position, 1, 0, false, false, 90, 2.8, 3, 2, 126, false);
        Point left = flight.plan(position, 0, 1, false, false, 0, 2.8, 3, 2, 126, false);
        check(near(south.x(), 0) && near(south.z(), 2.8), "yaw zero forward is south");
        check(near(west.x(), -2.8) && near(west.z(), 0), "yaw ninety forward is west");
        check(near(left.x(), 2.8) && near(left.z(), 0), "left strafe uses the player's view");
        Point diagonal = flight.plan(position, 1, 1, false, false, 0, 2.8, 3, 2, 126, false);
        check(near(diagonal.length(), 2.8), "diagonal keys must not multiply horizontal speed");
        for (int yaw = 0; yaw < 360; yaw++) {
            for (double sideways : new double[] {-1, 0, 1}) {
                Point requested = flight.plan(position, 1, sideways, false, true, yaw, 2.8, 3, 2, 126, false);
                Point phased = BoatPhaseMotion.phaseStep(requested, 0.24);
                check(near(Math.hypot(phased.x(), phased.z()), 0.24), "solid-block movement caps the whole horizontal vector at every yaw");
                check(phased.x() * phased.x() + phased.z() * phased.z() < 0.0625, "fully blocked horizontal residual stays below the standard vehicle moved-wrongly threshold");
                check(near(phased.y(), -3), "horizontal phasing cap preserves the working three-block descent");
                check(near(phased.x() * requested.z() - phased.z() * requested.x(), 0), "horizontal cap preserves steering direction");
            }
        }
        check(BoatPhaseMotion.phaseStep(new Point(0.1, 3, 0), 0.24).equals(new Point(0.1, 3, 0)), "slower horizontal requests are not sped up");
        check(near(BoatPhaseMotion.phaseStep(new Point(2.8, -3, 0), 9).x(), 0.249), "configuration cannot exceed the phase residual cap");
        check(BoatPhaseMotion.MAX_PHASE_HORIZONTAL * BoatPhaseMotion.MAX_PHASE_HORIZONTAL < .0625, "experimental margin stays below vanilla residual rejection");
        check(BoatPhaseMotion.PROVEN_PHASE_HORIZONTAL == .24, "correction fallback retains the field-tested tolerance");
        check(BoatPhaseMotion.phaseStep(new Point(0, -3, 0), 0.24).equals(new Point(0, -3, 0)), "vertical-only dive is untouched");
        check(BoatPhaseMotion.phaseStep(new Point(1, 3, 1), 0).equals(new Point(0, 3, 0)), "zero horizontal cap preserves vertical movement");
        check(BoatPhaseMotion.phaseStep(new Point(1, 0, 1), Double.NaN).equals(Point.ZERO), "invalid phase limit cannot emit bad coordinates");
        Point collisionBudget = BoatPhaseMotion.collisionStep(new Point(3, -3, 0), 0.24,
            p -> new Point(Math.min(0.76, p.x()), 0, 0));
        check(near(collisionBudget.x(), 1) && near(collisionBudget.y(), -3), "adaptive phase adds permitted overlap to physical travel instead of capping all motion");
        check(BoatPhaseMotion.collisionStep(new Point(3, 3, 0), 0.24, p -> p).equals(new Point(3, 3, 0)), "open collision path retains requested speed");
        check(BoatPhaseMotion.collisionStep(new Point(3, 3, 0), 0.24, p -> null).equals(new Point(0.24, 3, 0)), "unusable prediction falls back to the proven step");
        Point air = Point.ZERO;
        for (int i = 1; i <= 28; i++) {
            Point previous = air;
            air = BoatPhaseMotion.airStep(new Point(2.8, 0, 0), previous, 0.1, i, false, false, 100);
            check(Math.hypot(air.x() - previous.x(), air.z() - previous.z()) <= 0.10000001, "air departure ramps velocity instead of jumping from phase speed");
        }
        check(near(air.x(), 2.8), "air acceleration reaches ordinary boatfly speed");
        Point turn = BoatPhaseMotion.airStep(new Point(-2.8, 0, 0), air, 0.1, 29, false, false, 100);
        check(near(turn.x(), 2.7), "reversal also observes the airborne acceleration limit");
        check(BoatPhaseMotion.airStep(Point.ZERO, air, 0.1, 29, false, false, 100).equals(Point.ZERO), "releasing directional keys stops cruise movement immediately");
        check(near(BoatPhaseMotion.airStep(Point.ZERO, Point.ZERO, 0.1, 10, true, false, 100).y(), -0.08), "air hover sends a real descent on the first pulse tick");
        check(near(BoatPhaseMotion.airStep(Point.ZERO, Point.ZERO, 0.1, 11, true, false, 100).y(), -0.08), "air hover descent persists for a second tick");
        check(BoatPhaseMotion.airStep(Point.ZERO, Point.ZERO, 0.1, 12, true, false, 100).equals(Point.ZERO), "hover pulse ends after two ticks");
        check(near(BoatPhaseMotion.airStep(new Point(0, 3, 0), Point.ZERO, 0.1, 10, true, true, 100).y(), 3), "air pulse cannot override manual or automatic ascent");
        check(near(BoatPhaseMotion.airStep(Point.ZERO, Point.ZERO, 0.1, 10, true, false, 0.02).y(), -0.02), "air pulse cannot cross the world floor");
        check(BoatPhaseMotion.airStep(new Point(0, 9, 0), new Point(9, 0, 0), 0.1, 12, false, true, 100).length() <= 9 + 1e-8, "air transition retains the combined movement budget");
        Point floor = new Point(0, 2.25, 0);
        Point floorStep = flight.plan(floor, 0, 0, false, true, 0, 2.8, 3, 2, 126, true);
        check(near(floorStep.y(), -0.25), "descent stops at the dimension floor");
        check(flight.plan(new Point(0, 1, 0), 1, 0, false, false, 0, 2.8, 3, 2, 126, false).equals(Point.ZERO), "outside-world position cannot produce a recovery teleport");
        check(near(flight.plan(new Point(0, 125.8, 0), 0, 0, true, false, 0, 2.8, 3, 2, 126, false).y(), 0.2), "ascent respects world ceiling");
        flight.reset();
        for (int i = 0; i < 40; i++) flight.nextTick();
        check(near(flight.plan(position, 0, 0, false, false, 0, 2.8, 3, 2, 126, true).y(), -0.04), "anti-kick is a single real descent");
        check(flight.plan(position, 0, 0, false, false, 0, 2.8, 3, 2, 126, false).equals(Point.ZERO), "anti-kick can be disabled");

        Random random = new Random(6122026);
        Point cappedAir = new Point(3, 0, 3);
        for (int i = 0; i < 10000; i++) {
            double limit = i % 2 == 0 ? 0.99 : 0.1;
            double angle = random.nextDouble() * Math.PI * 2;
            Point desired = BoatPhaseMotion.horizontalStep(new Point(Math.cos(angle) * 3, 3, Math.sin(angle) * 3), limit);
            Point previous = BoatPhaseMotion.horizontalStep(cappedAir, limit);
            cappedAir = BoatPhaseMotion.airStep(desired, previous, 0.01, i, true, true, 100);
            check(Math.hypot(cappedAir.x(), cappedAir.z()) <= limit + 1e-9, "air turns and a lowered limit cannot retain excessive phase momentum");
            check(near(cappedAir.y(), 3), "horizontal server limit leaves the accepted vertical speed intact");
            Point solid = BoatPhaseMotion.collisionStep(desired, 0.24,
                p -> new Point(Math.copySign(Math.min(Math.abs(p.x()), 0.76), p.x()), 0, Math.copySign(Math.min(Math.abs(p.z()), 0.76), p.z())));
            check(Math.hypot(solid.x(), solid.z()) <= limit + 1e-9, "collision prediction receives the capped request and cannot undo the limit");
            check(near(solid.y(), 3), "adaptive phase keeps three-block vertical movement");
        }
        check(BoatPhaseMotion.horizontalStep(new Point(3, -3, 0), 0.99).equals(new Point(0.99, -3, 0)), "server horizontal limit differs from the blocked residual limit");
        check(BoatPhaseMotion.horizontalStep(new Point(1, 0, 1), Double.NaN).equals(Point.ZERO), "invalid horizontal limit cannot produce invalid movement");
        for (int i = 0; i < 10000; i++) {
            Point origin = new Point(random.nextDouble() * 100, 2 + random.nextDouble() * 124, random.nextDouble() * 100);
            double h = random.nextDouble() * 20, v = random.nextDouble() * 20;
            Point delta = flight.plan(origin, random.nextInt(3) - 1, random.nextInt(3) - 1, random.nextBoolean(), random.nextBoolean(),
                random.nextDouble() * 10000 - 5000, h, v, 2, 126, true);
            check(delta.finite(), "all normal inputs produce finite coordinates");
            int packets = Math.max(BoatPhaseVertical.count(delta.y()), BoatPhaseAirSteps.count(Math.hypot(delta.x(), delta.z()), 10));
            check(packets > 0 && delta.length() / packets < 9.9, "combined movement can be split into bounded vehicle updates");
            check(origin.y() + delta.y() >= 2 - 1e-8 && origin.y() + delta.y() <= 126 + 1e-8, "combined-vector cap cannot violate floor or ceiling");
        }
        for (double bad : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            check(flight.plan(new Point(0, bad, 0), 0, 0, false, false, 0, 3, 3, 2, 126, true).equals(Point.ZERO), "invalid origin is rejected");
            check(flight.plan(position, 1, 0, false, false, bad, 3, 3, 2, 126, true).equals(Point.ZERO), "invalid yaw is rejected");
            check(flight.plan(position, 1, 0, false, false, 0, bad, 3, 2, 126, true).equals(Point.ZERO), "invalid speed is rejected");
            flight.cancelDive();
            flight.dive(bad);
            check(flight.diveRemaining() == 0, "nonfinite dive distance is rejected");
        }
        flight.dive(30);
        flight.acknowledged();
        check(flight.alreadySent() && flight.diveRemaining() == 30, "correction acknowledgement reserves a packet without advancing dive");
        flight.nextTick();
        check(!flight.alreadySent(), "correction acknowledgement only suppresses the same tick's extra packet");
        flight.reset();
        check(flight.diveRemaining() == 0 && flight.holdTicks() == 0 && !flight.alreadySent(), "boat/session handoff must discard all motion state");
        position = new Point(12, 60, -32);
        flight.rise(10);
        for (int i = 0; i < 4; i++) {
            flight.nextTick();
            Point riseStep = flight.plan(position, 0, 0, false, false, 0, 3, 3, 2, 126, false);
            check(near(riseStep.y(), i == 3 ? 1 : 3), "clear-pocket ascent stops exactly at its requested height");
            Point next = move(position, riseStep);
            check(flight.sent(position, next) == (i == 3), "ascent completion uses actual transmitted movement");
            position = next;
        }
        check(near(position.y(), 70) && flight.riseRemaining() == 0, "rise finishes without overshooting passenger clearance target");
        flight.rise(10);
        flight.plan(position, 0, 0, false, true, 0, 3, 3, 2, 126, false);
        check(flight.riseRemaining() == 0, "manual descent cancels automatic ascent");
        flight.rise(10);
        flight.corrected(8);
        check(flight.riseRemaining() == 0 && flight.diveRemaining() == 0, "corrections cancel both vertical travel directions");
        flight.reset();
        System.out.println("BoatPhaseMotionTest: " + checks + " checks passed");
    }
}
