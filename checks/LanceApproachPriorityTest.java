package com.quiettee.utils.modules.combat;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import com.quiettee.utils.modules.combat.LanceAttackMath.Bounds;
import com.quiettee.utils.modules.combat.LanceAttackMath.Lane;
import com.quiettee.utils.modules.combat.LanceAttackMath.Point;

public final class LanceApproachPriorityTest {
    private static int checks;
    private static final Point STILL = new Point(0, 0, 0);
    private static final UnaryOperator<Point> APPROACH = d -> {
        var step = LanceMovementMath.approachStep(d.x(), d.y(), d.z(), 2.8, 1.2);
        return new Point(step.x(), step.y(), step.z());
    };

    public static void main(String[] args) {
        loggedNearLaunch();
        movingTargets();
        obstructedRoutes();
        boundedWindow();
        invalidGeometry();
        System.out.println("LanceApproachPriorityTest: " + checks + " checks passed");
    }

    private static void loggedNearLaunch() {

        Lane lane = LanceAttackMath.vertical(new Bounds(20407654.52, 157.89, -9300661.79,
            20407655.12, 158.49, -9300661.19), 3.6, 1.6, 5.2);
        Point next = new Point(20407654.89, 163.341, -9300661.60);
        check(!LanceAttackMath.readyToStrike(lane, next, STILL, 1.6, 2.8, 1.65), "logged endpoint still needs a small approach");
        check(imminent(lane, next, STILL, (a, b) -> true), "clear near-launch strike deserves the bounded grace");
        check(imminent(lane, lane.launch(), STILL, (a, b) -> true), "already aligned next-tick strike stays ready");
        check(!imminent(lane, lane.launch().add(0, 2.5, 0), STILL, (a, b) -> true), "distant vertical alignment is not imminent");
    }

    private static void movingTargets() {
        Lane lane = overhead();
        Point behind = lane.launch().add(-4, 0, 0);
        check(!imminent(lane, behind, new Point(2.9, 0, 0), (a, b) -> true),
            "logged straight runner keeps outrunning 2.8 cap when its lane advances too");
        check(imminent(lane, behind, STILL, (a, b) -> true), "same current distance is reachable when target stops");
        check(imminent(lane, lane.launch(), new Point(1.45, 0, 0), (a, b) -> true),
            "ordinary sideways movement can be absorbed by the actual strike");
        Lane side = LanceAttackMath.horizontal(new Bounds(-.3, 64, -.3, .3, 64.6, .3), 1, 0, 3.6, 1.6, 5.2);
        check(!imminent(side, side.launch(), new Point(2.9, 0, 0), (a, b) -> true),
            "horizontal lane does not promise a strike with insufficient relative closing speed");
        check(imminent(side, side.launch().add(-.45, 0, 0), STILL, (a, b) -> true),
            "horizontal lane can make one small approach before striking");
    }

    private static void obstructedRoutes() {
        Lane lane = overhead();
        Point near = lane.launch().add(5.5, 0, 0);
        check(imminent(lane, near, STILL, (a, b) -> true), "two-tick setup fits the actual approach and strike caps");
        var wall = new LancePathSafety.Bounds(3.9, 0, -1, 4.1, 100, 1);
        BiPredicate<Point, Point> clear = (a, b) -> {
            var body = new LancePathSafety.Bounds(a.x() - .3, a.y(), a.z() - .3, a.x() + .3, a.y() + .6, a.z() + .3);
            Point delta = b.subtract(a);
            return LancePathSafety.safeFraction(body, delta.x(), delta.y(), delta.z(), List.of(wall)) >= 1;
        };
        check(!imminent(lane, near, STILL, clear), "actual continuous body obstacle denies speculative grace");
        check(!imminent(lane, near, STILL, (a, b) -> b.x() > 1), "unsafe final strike segment also denies grace");
        check(!LanceApproachPriority.imminent(lane, near, STILL, 1.6, 2.8, 1.65,
            APPROACH, (a, b) -> true, l -> false), "blocked contact ray denies grace even when body corridor is clear");
    }

    private static void boundedWindow() {
        LanceApproachPriority window = new LanceApproachPriority();
        window.observe(3395, 640, 12, true, false);
        check(window.preserve(3395, true), "first near-launch opportunity holds couch");
        window.observe(3396, 640, 12, true, false);
        check(window.active(3396) && window.preserve(3396, true), "following tick approaches launch instead of descending to capture");
        for (int tick = 3397; tick < 3497; tick++) {
            window.observe(tick, 640, 12, true, false);
            check(!window.preserve(tick, true), "moving near launch cannot renew consumed grace indefinitely");
        }
        window.observe(3497, 641, 12, true, false);
        check(window.preserve(3497, true), "new couch gets its own bounded opportunity");
        window.observe(3498, 641, 12, true, true);
        check(!window.active(3498), "actual strike continuation ends old approach grace");
        window.observe(3508, 641, 12, true, false);
        check(window.preserve(3508, true), "subsequent beat may preserve a new imminent strike");
        window.observe(3509, 641, 13, true, false);
        check(window.preserve(3509, true), "new target does not inherit spent opportunity");
        window.observe(3510, 641, 13, false, false);
        check(!window.active(3510), "unconfirmed or arming use cannot retain a ready-couch hold");
        window.observe(Integer.MAX_VALUE - 1, 642, 13, true, false);
        check(window.preserve(Integer.MAX_VALUE - 1, true) && window.active(Integer.MAX_VALUE), "two-tick deadline does not overflow int");
    }

    private static void invalidGeometry() {
        check(!imminent(overhead(), new Point(Double.NaN, 0, 0), STILL, (a, b) -> true), "nonfinite body fails closed");
        check(!imminent(overhead(), overhead().launch(), new Point(Double.POSITIVE_INFINITY, 0, 0), (a, b) -> true),
            "nonfinite target motion fails closed");
    }

    private static Lane overhead() {
        return LanceAttackMath.vertical(new Bounds(-.3, 64, -.3, .3, 64.6, .3), 3.6, 1.6, 5.2);
    }

    private static boolean imminent(Lane lane, Point next, Point velocity, BiPredicate<Point, Point> clear) {
        return LanceApproachPriority.imminent(lane, next, velocity, 1.6, 2.8, lane.horizontal() ? 1.4 : 1.65,
            APPROACH, clear, l -> true);
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
