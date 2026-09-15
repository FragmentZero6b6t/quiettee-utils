package com.quiettee.utils.modules.combat;

import com.quiettee.utils.modules.combat.LanceAttackMath.Bounds;
import com.quiettee.utils.modules.combat.LanceAttackMath.Lane;
import com.quiettee.utils.modules.combat.LanceAttackMath.Point;

public final class LanceAttackMathTest {
    private static int checks;

    public static void main(String[] args) {
        verticalBaseline();
        horizontalDirections();
        glidingFloorAndCeiling();
        unequalBoundsAndWorldCoordinates();
        closingSpeedAndCorridor();
        prospectiveStrikeReadiness();
        invalidInputs();
        System.out.println("LanceAttackMathTest: " + checks + " checks passed");
    }

    private static void verticalBaseline() {
        Bounds player = new Bounds(-0.3, 64, -0.3, 0.3, 65.8, 0.3);
        Lane lane = LanceAttackMath.vertical(player, 3.6, 1.6, 5.2);
        check(lane != null && !lane.horizontal(), "vertical lane remains the ordinary overhead mode");
        close(lane.impact().x(), 0, "vertical impact keeps target column X");
        close(lane.impact().z(), 0, "vertical impact keeps target column Z");
        close(lane.impact().y() + LanceAttackMath.EYE - player.maxY(), 3.6, "vertical eye ends 3.6 above target head");
        close(lane.contact().y(), player.maxY(), "vertical contact records the target surface independent of strike gap");
        close(lane.launch().y() - lane.impact().y(), 1.6, "vertical launch retains the existing 1.6 dip");
        close(lane.axis().dot(lane.impact().subtract(lane.launch())), 1.6, "downward movement has positive closing projection");
        Lane minimumRest = LanceAttackMath.vertical(player, 3.6, 1.6, 0);
        check(minimumRest.rest().equals(minimumRest.launch()), "rest cannot sit inside the launch point when given a smaller gap");
        Lane tall = LanceAttackMath.vertical(new Bounds(-0.5, 10, -0.5, 0.5, 13.5, 0.5), 3.6, 1.6, 5.5);
        close(tall.impact().y() + LanceAttackMath.EYE, 17.1, "tall victim's top surface determines vertical reach");
    }

    private static void horizontalDirections() {
        Bounds player = new Bounds(-0.3, 64, -0.3, 0.3, 65.8, 0.3);
        for (int direction = 0; direction < 8; direction++) {
            double angle = direction * Math.PI / 4;
            Lane lane = LanceAttackMath.horizontal(player, Math.cos(angle), Math.sin(angle), 3.6, 1.6, 5.5);
            check(lane != null && lane.horizontal(), "all eight horizontal approach directions produce a lane");
            close(lane.axis().lengthSquared(), 1, "horizontal strike axis is unit length");
            close(lane.axis().y(), 0, "horizontal strike axis has no ascent");
            close(lane.impact().y() + LanceAttackMath.EYE, 64.9, "horizontal ray aims through the victim midpoint");
            close(lane.launch().y(), lane.impact().y(), "horizontal launch fits the same vertical corridor");
            close(lane.rest().y(), lane.impact().y(), "horizontal rest fits the same vertical corridor");
            Point impactEye = lane.impact().add(0, LanceAttackMath.EYE, 0);
            close(lane.contact().subtract(impactEye).dot(lane.axis()), 3.6, "horizontal contact records the actual ray endpoint");
            close(rayEntry(impactEye, lane.axis(), player), 3.6, "raw ray reaches victim surface at the configured strike gap");
            close(rayEntry(lane.launch().add(0, LanceAttackMath.EYE, 0), lane.axis(), player), 5.2, "launch is outside the raw 4.5 reach");
            close(rayEntry(lane.rest().add(0, LanceAttackMath.EYE, 0), lane.axis(), player), 5.5, "rest gap stays beyond launch");
            double nearest = distanceToBox(impactEye, player);
            check(nearest > 2 && nearest < 4.5, "impact also stays inside the raw box-distance band");
            close(lane.impact().subtract(lane.launch()).dot(lane.axis()), 1.6, "each horizontal direction preserves closing dip");
        }
    }

    private static void unequalBoundsAndWorldCoordinates() {
        for (double shift : new double[] { 0, -20_398_052, 20_398_052, -29_999_980, 29_999_980 }) {
            Bounds wide = new Bounds(shift - 2, 20, shift - 0.25, shift + 2, 21.8, shift + 0.25);
            for (int direction = 0; direction < 8; direction++) {
                double angle = direction * Math.PI / 4;
                Lane lane = LanceAttackMath.horizontal(wide, Math.cos(angle) * 7, Math.sin(angle) * 7, 3.6, 1.6, 5.5);
                check(lane != null, "finite lane supports ordinary world-border-scale coordinates");
                close(rayEntry(lane.impact().add(0, LanceAttackMath.EYE, 0), lane.axis(), wide), 3.6,
                    "unequal half extents choose the actual near ray face, independent of coordinate sign");
                close(lane.impact().subtract(lane.launch()).dot(lane.axis()), 1.6,
                    "large-coordinate lane preserves the configured actual closing displacement");
            }
        }
        Bounds a = new Bounds(9.7, 64, 19.7, 10.3, 65.8, 20.3);
        Bounds mirrored = new Bounds(-10.3, 64, -20.3, -9.7, 65.8, -19.7);
        Lane positive = LanceAttackMath.horizontal(a, 1, 1, 3.6, 1.6, 5.5);
        Lane negative = LanceAttackMath.horizontal(mirrored, -1, -1, 3.6, 1.6, 5.5);
        close(positive.impact().x(), -negative.impact().x(), "mirrored X world positions produce mirrored impact positions");
        close(positive.impact().z(), -negative.impact().z(), "mirrored Z world positions produce mirrored impact positions");
    }

    private static void glidingFloorAndCeiling() {
        Bounds gliding = new Bounds(-0.3, 64, -0.3, 0.3, 64.6, 0.3);
        for (int direction = 0; direction < 8; direction++) {
            double angle = direction * Math.PI / 4;
            Lane lane = LanceAttackMath.horizontal(gliding, Math.cos(angle), Math.sin(angle), 3.6, 1.6, 5.5);
            check(lane != null, "gliding victim has all eight horizontal lane candidates");
            Point continuation = lane.impact().add(lane.axis().multiply(0.3));
            for (int sample = 0; sample <= 20; sample++) {
                Point feet = lane.rest().add(continuation.subtract(lane.rest()).multiply(sample / 20.0));
                check(feet.y() >= 64, "horizontal gliding lane does not put the server body into the shared floor");
                check(feet.y() + 0.6 <= 64.9, "entire gliding lane fits below a 0.9-block ceiling");
                double eyeY = feet.y() + LanceAttackMath.EYE;
                check(eyeY >= gliding.minY() && eyeY < gliding.maxY(), "raised horizontal aim remains inside the gliding victim's box");
            }
            close(rayEntry(lane.impact().add(0, LanceAttackMath.EYE, 0), lane.axis(), gliding), 3.6,
                "floor-safe aim preserves the configured horizontal strike gap");
        }
        Bounds tiny = new Bounds(-0.3, 64, -0.3, 0.3, 64.25, 0.3);
        Lane tinyLane = LanceAttackMath.horizontal(tiny, 1, 0, 3.6, 1.6, 5.5);
        double tinyAim = tinyLane.impact().y() + LanceAttackMath.EYE;
        check(tinyAim >= tiny.minY() && tinyAim < tiny.maxY(),
            "shorter-than-eye-height mobs keep a real hitbox aim instead of inventing a ray above them");
        Lane outer = LanceAttackMath.horizontal(gliding, 1, 0, 4.2, 1.6, 5.5);
        close(outer.contact().subtract(outer.impact().add(0, LanceAttackMath.EYE, 0)).dot(outer.axis()), 4.2,
            "an outer-reach lane retains its own surface contact instead of reusing the original strike gap");
    }

    private static void closingSpeedAndCorridor() {
        Bounds player = new Bounds(-0.3, 64, -0.3, 0.3, 65.8, 0.3);
        Lane lane = LanceAttackMath.horizontal(player, 1, 0, 3.6, 1.6, 5.5);
        Point strikeMovement = lane.impact().subtract(lane.launch());
        double closing = strikeMovement.dot(lane.axis()) * 20;
        close(closing, 32, "horizontal 1.6 movement produces the same 32 blocks/sec projection as a vertical dip");
        check(1 + Math.floor((closing + 1e-9) * 1.2) == 39, "stationary-target netherite damage geometry matches the baseline");
        Point continuation = lane.impact().add(lane.axis().multiply(0.3));
        close(rayEntry(continuation.add(0, LanceAttackMath.EYE, 0), lane.axis(), player), 3.3,
            "continuation retains the same axis and remains in the raw range band");
        close(continuation.subtract(lane.impact()).dot(lane.axis()), 0.3, "continuation still closes if its primary packet was not sampled");
        close(strikeMovement.dot(lane.axis()) - lane.axis().multiply(0.5).dot(lane.axis()), 1.1,
            "a fleeing target's projection subtracts from horizontal relative speed");
        for (int sample = 0; sample <= 100; sample++) {
            Point feet = lane.launch().add(continuation.subtract(lane.launch()).multiply(sample / 100.0));
            check(feet.y() >= 64 && feet.y() + 0.6 < 65.2, "all gliding body positions fit the example low ceiling corridor");
            check(feet.x() + 0.3 < player.minX(), "entire example strike body stays outside the target body and its own web cell");
        }
    }

    private static void prospectiveStrikeReadiness() {
        Bounds target = new Bounds(-0.3, 64, -0.3, 0.3, 64.6, 0.3);
        Point zero = new Point(0, 0, 0);
        for (int direction = 0; direction < 8; direction++) {
            double angle = direction * Math.PI / 4;
            double ax = Math.cos(angle), az = Math.sin(angle);
            Lane oldLane = LanceAttackMath.horizontal(target, ax, az, 3.6, 1.6, 5.5);
            check(LanceAttackMath.readyToStrike(oldLane, oldLane.launch(), zero, 1.6, 2.8, 1.4),
                "stationary horizontal strike remains ready at its launch point");
            for (Point motion : new Point[] {
                new Point(-az * 0.28, 0, ax * 0.28), new Point(az * 0.28, 0, -ax * 0.28),
                new Point(ax * 0.28, 0, az * 0.28), new Point(-ax * 0.28, 0, -az * 0.28)
            }) {
                Bounds moved = new Bounds(target.minX() + motion.x(), target.minY(), target.minZ() + motion.z(),
                    target.maxX() + motion.x(), target.maxY(), target.maxZ() + motion.z());
                Lane newLane = LanceAttackMath.horizontal(moved, ax, az, 3.6, 1.6, 5.5);
                check(newLane.launch().subtract(oldLane.launch()).lengthSquared() > 0.2 * 0.2,
                    "old settled-launch gate would keep waiting on this continuing target motion");
                check(LanceAttackMath.readyToStrike(newLane, oldLane.launch(), motion, 1.6, 2.8, 1.4),
                    "quarter-block sideways or inward/outward motion can be absorbed by a valid prospective strike");
            }
            Point sideTooFar = oldLane.launch().add(-az * 2.4, 0, ax * 2.4);
            check(!LanceAttackMath.readyToStrike(oldLane, sideTooFar, zero, 1.6, 2.8, 1.4),
                "combined sideways alignment and strike cannot overrun the actual horizontal cap");
            check(LanceAttackMath.readyToStrike(oldLane, oldLane.launch().add(oldLane.axis().multiply(0.35)), zero, 1.6, 2.8, 1.4),
                "a shortened 1.25-block dip remains valid when it clears vanilla damage conditions");
            check(!LanceAttackMath.readyToStrike(oldLane, oldLane.launch().add(oldLane.axis().multiply(-0.5)), zero, 1.6, 2.8, 1.4),
                "excessive along-axis launch displacement does not turn an approach into an unplanned strike");
            check(!LanceAttackMath.readyToStrike(oldLane, oldLane.contact().add(oldLane.axis()).add(0, -LanceAttackMath.EYE, 0), zero, 1.6, 2.8, 1.4),
                "a ghost beyond the target cannot strike backward along the selected inward axis");
        }
        Lane horizontal = LanceAttackMath.horizontal(target, 1, 0, 3.6, 1.6, 5.5);
        check(!LanceAttackMath.readyToStrike(horizontal, horizontal.launch().add(0, 0.2, 0), zero, 1.6, 2.8, 0.1),
            "horizontal strike's vertical alignment must fit its caller-supplied vertical cap");
        check(!LanceAttackMath.readyToStrike(horizontal, horizontal.launch().add(0, 0.41, 0), zero, 1.6, 2.8, 1.4),
            "horizontal height error is limited before any large diagonal lunge");
        check(LanceAttackMath.readyToStrike(horizontal, horizontal.launch(), new Point(0.4, 0, 0), 1.6, 2.8, 1.4),
            "1.2 blocks of relative closing is useful even though it is less than 85 percent of the configured dip");
        check(!LanceAttackMath.readyToStrike(horizontal, horizontal.launch(), new Point(1.38, 0, 0), 1.6, 2.8, 1.4),
            "relative closing below the vanilla 4.6-blocks-per-second minimum is rejected");
        check(LanceAttackMath.readyToStrike(horizontal, horizontal.launch(), new Point(1.6 - 4.6f / 20d, 0, 0), 1.6, 2.8, 1.4),
            "the vanilla float minimum damage speed is inclusive");
        Lane vertical = LanceAttackMath.vertical(target, 3.6, 1.6, 5.5);
        check(LanceAttackMath.readyToStrike(vertical, vertical.launch().add(0.28, 0, 0), zero, 1.6, 2.8, 1.65),
            "existing vertical strike allows ordinary sideways correction within the movement cap");
        check(LanceAttackMath.readyToStrike(vertical, vertical.launch(), new Point(0, -0.30, 0), 1.6, 2.8, 1.65),
            "descending target's 1.3-block relative closing still supports a valid vertical strike");
        check(!LanceAttackMath.readyToStrike(vertical, vertical.launch().add(0, -0.16, 0), zero, 1.6, 2.8, 1.65),
            "vertical mode retains its existing strict launch-height gate");
        check(!LanceAttackMath.readyToStrike(vertical, vertical.launch(), zero, 1.6, 2.8, 1.4),
            "vertical dip cannot exceed the explicitly supplied strike cap");
        check(!LanceAttackMath.readyToStrike(horizontal, horizontal.launch(), new Point(Double.NaN, 0, 0), 1.6, 2.8, 1.4),
            "unknown target velocity cannot confirm useful closing speed");
        check(!LanceAttackMath.readyToStrike(horizontal, horizontal.launch(), zero, 0, 2.8, 1.4), "zero dip is not an attack");
        check(!LanceAttackMath.readyToStrike(null, horizontal.launch(), zero, 1.6, 2.8, 1.4), "missing lane cannot be ready");
    }

    private static void invalidInputs() {
        Bounds player = new Bounds(-0.3, 64, -0.3, 0.3, 65.8, 0.3);
        check(LanceAttackMath.horizontal(player, 0, 0, 3.6, 1.6, 5.5) == null, "zero horizontal direction cannot produce an arbitrary lane");
        check(LanceAttackMath.horizontal(player, Double.NaN, 1, 3.6, 1.6, 5.5) == null, "invalid direction is rejected");
        check(LanceAttackMath.vertical(player, 3.6, 0, 5.5) == null, "zero strike displacement cannot create a closing attack");
        check(LanceAttackMath.vertical(player, Double.POSITIVE_INFINITY, 1.6, 5.5) == null, "infinite gap is rejected");
        check(LanceAttackMath.vertical(new Bounds(1, 0, 0, 0, 1, 1), 3.6, 1.6, 5.5) == null, "inverted target bounds are rejected");
    }

    private static double rayEntry(Point eye, Point axis, Bounds target) {
        double enter = Double.NEGATIVE_INFINITY;
        if (axis.x() > 1e-9) enter = Math.max(enter, (target.minX() - eye.x()) / axis.x());
        else if (axis.x() < -1e-9) enter = Math.max(enter, (target.maxX() - eye.x()) / axis.x());
        if (axis.y() > 1e-9) enter = Math.max(enter, (target.minY() - eye.y()) / axis.y());
        else if (axis.y() < -1e-9) enter = Math.max(enter, (target.maxY() - eye.y()) / axis.y());
        if (axis.z() > 1e-9) enter = Math.max(enter, (target.minZ() - eye.z()) / axis.z());
        else if (axis.z() < -1e-9) enter = Math.max(enter, (target.maxZ() - eye.z()) / axis.z());
        return enter;
    }

    private static double distanceToBox(Point p, Bounds b) {
        double x = Math.max(Math.max(b.minX() - p.x(), 0), p.x() - b.maxX());
        double y = Math.max(Math.max(b.minY() - p.y(), 0), p.y() - b.maxY());
        double z = Math.max(Math.max(b.minZ() - p.z(), 0), p.z() - b.maxZ());
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void close(double actual, double expected, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-7, message + ": " + actual + " vs " + expected);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
