package com.quiettee.utils.modules.combat;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import com.quiettee.utils.modules.combat.LancePathSafety.Bounds;

public final class LancePathSafetyTest {
    private static int checks;

    public static void main(String[] args) {
        loggedOwnWebPaths();
        continuousCornerCrossing();
        exactDiagonalPaths();
        exactSurfacesAndDetourGate();
        stagedDetoursAndFloorDeparture();
        boundariesAndUnchangedMovement();
        randomizedSafePrefixes();
        randomizedExactSweeps();
        invalidInputs();
        System.out.println("LancePathSafetyTest: " + checks + " checks passed");
    }

    private static void loggedOwnWebPaths() {

        fieldPath(body(-107.17, 118.27, 1501.37), 0, 1.2, 0,
            List.of(cell(-108, 120, 1501), cell(-107, 120, 1501), cell(-108, 121, 1501)),
            "20:58:46 next rise enters the newly placed upper web");

        fieldPath(body(-20398052.50, 90.12, -3262516.50), 0, 1.2, 0,
            List.of(cell(-20398053, 91, -3262517), cell(-20398053, 92, -3262517)),
            "21:09:20 vertical intercept obstructs the following ghost step");

        fieldPath(body(-20398136.55, 220.07, -3262497.53), -0.40, 1.40, 0.44,
            List.of(cell(-20398138, 221, -3262497), cell(-20398138, 222, -3262497)),
            "21:09:37 diagonal climb into own web corridor");
    }

    private static void fieldPath(Bounds start, double dx, double dy, double dz, List<Bounds> hazards, String label) {
        Predicate<Bounds> clear = clearOf(hazards);
        check(clear.test(start), label + ": starts outside web");
        check(!clear.test(LancePathSafety.swept(start, dx, dy, dz)), label + ": complete path crosses web");
        double fraction = LancePathSafety.safeFraction(start, dx, dy, dz, hazards);
        check(fraction > 0 && fraction < 1, label + ": safe progress remains available");
        check(clear.test(LancePathSafety.swept(start, dx * fraction, dy * fraction, dz * fraction)), label + ": accepted prefix stays outside");

        check(LancePathSafety.safeFraction(start, dx, dy, dz, ignored -> true) == 1,
            label + ": treating empty web collision shapes as air would accept the unsafe complete move");
    }

    private static void continuousCornerCrossing() {
        Bounds start = body(0, 0, 0);
        Bounds corner = new Bounds(0.51, 0, -0.4, 1, 1, -0.06);
        Predicate<Bounds> clear = clearOf(List.of(corner));

        for (int i = 0; i <= 4; i++) check(clear.test(translated(start, i * 0.25, 0, i * 0.25)), "old 0.4-step sample misses thin diagonal contact");
        check(!clear.test(translated(start, 0.225, 0, 0.225)), "independent in-between body actually touches the corner");
        double fraction = LancePathSafety.safeFraction(start, 1, 0, 1, List.of(corner));
        check(fraction > 0.20 && fraction < 0.21, "continuous envelope stops before the first corner contact");
        check(clear.test(LancePathSafety.swept(start, fraction, 0, fraction)), "corner prefix is conservatively clear");
    }

    private static void exactDiagonalPaths() {
        Bounds start = body(0, 0, 0);
        Bounds outsidePath = new Bounds(0.8, 0, -0.4, 1, 1, -0.1);
        check(LancePathSafety.intersects(LancePathSafety.swept(start, 1, 0, 1), outsidePath),
            "r123 broad envelope contains the off-path corner");
        check(LancePathSafety.safeFraction(start, 1, 0, 1, clearOf(List.of(outsidePath))) < 1,
            "r123 envelope callback would falsely stop that diagonal");
        check(LancePathSafety.safeFraction(start, 1, 0, 1, List.of(outsidePath)) == 1,
            "precise sweep preserves diagonal movement outside the actual obstacle");
        for (int sample = 0; sample <= 1000; sample++) {
            double t = sample / 1000.0;
            check(!LancePathSafety.intersects(translated(start, t, 0, t), outsidePath),
                "independent sampled bodies verify that off-path corner never intersects");
        }
        Bounds graze = new Bounds(0.6, 0, -0.4, 1, 1, 0);
        check(LancePathSafety.safeFraction(start, 1, 0, 1, List.of(graze)) == 1,
            "equal slab entry/exit is a corner graze with no positive-volume collision");
        Bounds trueHit = new Bounds(0.51, 0, -0.4, 1, 1, -0.06);
        double first = LancePathSafety.safeFraction(start, 1, 0, 1, List.of(outsidePath, trueHit));
        check(first > 0.20 && first < 0.21, "true thin contact still stops the exact sweep");
        check(first == LancePathSafety.safeFraction(start, 1, 0, 1, List.of(trueHit, outsidePath)),
            "nearest contact does not depend on collision-box iteration order");
        Bounds translatedStart = translated(start, -20_398_052.5, 90.12, -3_262_516.5);
        Bounds translatedHit = translated(trueHit, -20_398_052.5, 90.12, -3_262_516.5);
        double largeCoordinate = LancePathSafety.safeFraction(translatedStart, 1, 0, 1, List.of(translatedHit));
        check(Double.isFinite(largeCoordinate) && Math.abs(largeCoordinate - first) < 1e-7,
            "precise thin-contact timing survives the logged large world coordinates");
    }

    private static void exactSurfacesAndDetourGate() {
        Bounds unit = cell(0, 0, 0);
        List<Bounds> wall = List.of(cell(1, 0, 0));
        check(LancePathSafety.safeFraction(unit, 1, 0, 0, wall) == 0, "exact surface contact blocks motion into the wall");
        check(LancePathSafety.safeFraction(unit, -1, 0, 0, wall) == 1, "exact surface contact allows motion away");
        check(LancePathSafety.safeFraction(unit, 0, 1, 0, wall) == 1, "exact surface contact allows sliding parallel to wall");
        check(LancePathSafety.safeFraction(unit, 0, 0, 0, wall) == 1, "stationary face contact is clear");
        check(LancePathSafety.safeFraction(unit, 1, 0, 0, List.of(cell(2, 0, 0))) == 1,
            "ending on a surface without crossing it remains allowed");
        check(LancePathSafety.safeFraction(unit, -10, 0, 0, List.of(unit)) == 0,
            "initial positive overlap invokes recovery even if the destination is clear");
        check(LancePathSafety.safeFraction(body(0, 64, 0), 0, 1.4, 0,
            List.of(new Bounds(-5, 63, -5, 5, 64, 5))) == 1, "floor contact permits normal upward departure");
        check(LancePathSafety.safeFraction(body(0, 64, 0), 0, -1.6, 0, List.of()) == 1,
            "exact open-air strike keeps the successful -1.6 dip");
        check(LancePathSafety.safeFraction(body(0, 64, 0), 2.8, 1.4, 0, List.of()) == 1,
            "exact open-air chase keeps normal horizontal and vertical speed");
        check(!LancePathSafety.allowDetour(true, -1.6), "logged cancelled strike cannot start the five-tick upward oscillation");
        check(!LancePathSafety.allowDetour(true, 1.4), "continuation does not become a detour even against an ascending target");
        check(!LancePathSafety.allowDetour(false, -1.2), "downward approach never routes upward away from its objective");
        check(!LancePathSafety.allowDetour(false, 0), "horizontal chase does not introduce an unsolicited climb");
        check(!LancePathSafety.allowDetour(false, 0.2), "tiny vertical adjustment stays outside the detour gate");
        check(LancePathSafety.allowDetour(false, 1.2), "meaningful upward pursuit may route around a blocked climb");
        check(!LancePathSafety.allowDetour(false, Double.NaN), "invalid detour input cannot authorize movement");
    }

    private static void boundariesAndUnchangedMovement() {
        Bounds start = body(0, 64, 0);
        check(LancePathSafety.safeFraction(start, 0, -1.6, 0, ignored -> true) == 1, "valid open-air -1.6 strike is unchanged");
        check(LancePathSafety.safeFraction(start, 2.8, 1.4, -0.1, ignored -> true) == 1, "open-air follow keeps its existing caps");
        check(LancePathSafety.safeFraction(start, 0, 0, 0, ignored -> true) == 1, "clear stationary body remains unchanged");
        check(LancePathSafety.safeFraction(start, 0, 0, 0, ignored -> false) == 0, "stationary occupied body needs recovery");
        check(LancePathSafety.safeFraction(start, 0, 2, 0, clearOf(List.of(cell(0, 64, 0)))) == 0,
            "start overlap cannot be treated as permission to move through the rest of the web");
        Bounds unit = cell(0, 0, 0);
        check(!LancePathSafety.intersects(unit, cell(1, 0, 0)), "shared face alone is not overlap");
        check(!LancePathSafety.intersects(unit, cell(1, 1, 0)), "shared edge alone is not overlap");
        check(!LancePathSafety.intersects(unit, cell(1, 1, 1)), "shared corner alone is not overlap");
        check(LancePathSafety.intersects(unit, new Bounds(0.999, 0.2, 0.2, 2, 0.8, 0.8)), "positive thin overlap is detected");
        check(!LancePathSafety.intersects(unit, new Bounds(0.5, 0, 0, 0.5, 1, 1)), "zero-volume box does not overlap");
        check(LancePathSafety.safeFraction(unit, 1, 0, 0, clearOf(List.of(cell(2, 0, 0)))) == 1, "move ending exactly at a shared face is allowed");
        check(LancePathSafety.safeFraction(unit, 1, 0, 0, clearOf(List.of(cell(1, 0, 0)))) == 0, "touching boundary blocks movement into it");
        check(LancePathSafety.safeFraction(unit, -1, 0, 0, clearOf(List.of(cell(1, 0, 0)))) == 1, "touching boundary allows movement away");
        Bounds negative = LancePathSafety.swept(unit, -3, 2, -4);
        check(negative.equals(new Bounds(-3, 0, -4, 1, 3, 1)), "negative and positive axes form the continuous envelope");
    }

    private static void stagedDetoursAndFloorDeparture() {
        Bounds logged = body(-20398052.50, 90.12, -3262516.50);
        Predicate<Bounds> column = clearOf(List.of(cell(-20398053, 91, -3262517), cell(-20398053, 92, -3262517),
            cell(-20398053, 93, -3262517), cell(-20398053, 94, -3262517)));
        LancePathSafety.Detour detour = LancePathSafety.detour(logged, 0, 8, 0, column);
        check(detour != null, "logged overhead web allows staged escape beside the column");
        check(Math.hypot(detour.sideX(), detour.sideZ()) >= 1.9, "overhead web needs an actual sidestep");
        check(!column.test(LancePathSafety.swept(logged, detour.sideX(), detour.rise(), detour.sideZ())),
            "one diagonal conservative envelope would reject this escape");
        check(LancePathSafety.safeFraction(logged, detour.sideX(), 0, detour.sideZ(), column) == 1,
            "production detour first completes a clear level sidestep");
        Bounds beside = translated(logged, detour.sideX(), 0, detour.sideZ());
        check(LancePathSafety.safeFraction(beside, 0, detour.rise(), 0, column) == 1,
            "production detour then climbs clear of the web column");

        Bounds grounded = body(0.5, 64, 0.5);
        Predicate<Bounds> floor = clearOf(List.of(new Bounds(-10, 63, -10, 10, 64, 10)));
        check(LancePathSafety.safeFraction(grounded, 0, 1.4, 0, floor) == 1,
            "actual body touching floor can depart upward without treating floor contact as initial overlap");
        check(LancePathSafety.detour(grounded, 0, 3, 0, floor).equals(new LancePathSafety.Detour(0, 0, 3)),
            "clear floor departure does not need a sideways detour");

        Predicate<Bounds> wideCanopy = clearOf(List.of(new Bounds(-2, 65, -2, 3, 66, 3)));
        LancePathSafety.Detour wide = LancePathSafety.detour(grounded, 0, 6, 0, wideCanopy);
        check(wide != null && Math.hypot(wide.sideX(), wide.sideZ()) > 3.9, "four-block candidate can clear a canopy blocking the two-block routes");
        check(LancePathSafety.safeFraction(grounded, wide.sideX(), 0, wide.sideZ(), wideCanopy) == 1, "wide route level leg is clear");
        check(LancePathSafety.safeFraction(translated(grounded, wide.sideX(), 0, wide.sideZ()), 0, wide.rise(), 0, wideCanopy) == 1,
            "wide route rising leg is clear");
        check(LancePathSafety.detour(grounded, 0, 6, 0, box -> box.maxY() <= 64.7) == null,
            "continuous ceiling blocks every candidate rather than admitting an unsafe route");
        check(LancePathSafety.detour(grounded, 0, 6, 0, ignored -> false) == null, "initially trapped body remains a recovery case");
        check(LancePathSafety.detour(grounded, Double.NaN, 6, 0, ignored -> true) == null, "invalid route objective cannot authorize movement");

        List<Bounds> exactColumn = List.of(cell(-20398053, 91, -3262517), cell(-20398053, 92, -3262517),
            cell(-20398053, 93, -3262517), cell(-20398053, 94, -3262517));
        LancePathSafety.Detour exact = LancePathSafety.detour(logged, 0, 8, 0, exactColumn);
        check(exact != null && Math.hypot(exact.sideX(), exact.sideZ()) > 1.9, "precise production detour avoids the logged overhead column");
        check(LancePathSafety.safeFraction(logged, exact.sideX(), 0, exact.sideZ(), exactColumn) == 1, "precise detour side leg is clear");
        check(LancePathSafety.safeFraction(translated(logged, exact.sideX(), 0, exact.sideZ()), 0, exact.rise(), 0, exactColumn) == 1,
            "precise detour rising leg is clear");
    }

    private static void randomizedSafePrefixes() {
        Random random = new Random(123);
        for (int scenario = 0; scenario < 1000; scenario++) {
            double x = random.nextDouble() * 100 - 50, y = random.nextDouble() * 30, z = random.nextDouble() * 100 - 50;
            if (scenario % 3 == 0) { x -= 20_000_000; z -= 3_000_000; }
            Bounds start = body(x, y, z);
            double dx = random.nextDouble() * 5.6 - 2.8, dy = random.nextDouble() * 3.2 - 1.6, dz = random.nextDouble() * 5.6 - 2.8;
            Bounds obstacle = new Bounds(x + random.nextDouble() * 5 - 2.5, y + random.nextDouble() * 4 - 2, z + random.nextDouble() * 5 - 2.5,
                x + 3, y + 2.8, z + 3);
            Predicate<Bounds> clear = clearOf(List.of(obstacle));
            double fraction = LancePathSafety.safeFraction(start, dx, dy, dz, clear);
            check(Double.isFinite(fraction) && fraction >= 0 && fraction <= 1, "random prefix is finite and bounded");
            if (!clear.test(start)) {
                check(fraction == 0, "random initial overlap holds for recovery");
                continue;
            }
            check(clear.test(LancePathSafety.swept(start, dx * fraction, dy * fraction, dz * fraction)), "whole accepted random envelope is clear");
            for (int sample = 0; sample <= 32; sample++) {
                double t = fraction * sample / 32;
                check(clear.test(translated(start, dx * t, dy * t, dz * t)), "independent sampled body fits the accepted continuous prefix");
            }
            if (clear.test(LancePathSafety.swept(start, dx, dy, dz))) check(fraction == 1, "clear random move is preserved in full");
        }
    }

    private static void invalidInputs() {
        Bounds start = body(0, 0, 0);
        check(LancePathSafety.safeFraction(start, Double.NaN, 0, 0, ignored -> true) == 0, "NaN movement holds");
        check(LancePathSafety.safeFraction(start, 0, Double.POSITIVE_INFINITY, 0, ignored -> true) == 0, "infinite movement holds");
        check(LancePathSafety.safeFraction(new Bounds(1, 0, 0, 0, 1, 1), 1, 0, 0, ignored -> true) == 0, "inverted bounds hold");
        check(LancePathSafety.safeFraction(new Bounds(Double.NaN, 0, 0, 1, 1, 1), 1, 0, 0, ignored -> true) == 0, "NaN bounds hold");
        check(LancePathSafety.safeFraction(start, 1, 0, 0, (Predicate<Bounds>) null) == 0, "missing clearance callback cannot authorize motion");
        check(LancePathSafety.safeFraction(start, 1, 0, 0, (Iterable<Bounds>) null) == 0, "missing exact obstacle source cannot authorize motion");
        check(LancePathSafety.safeFraction(start, Double.NaN, 0, 0, List.of()) == 0, "exact sweep rejects NaN motion");
        check(LancePathSafety.safeFraction(start, 0, Double.POSITIVE_INFINITY, 0, List.of()) == 0, "exact sweep rejects infinite motion");
        check(LancePathSafety.safeFraction(start, 1, 0, 0, List.of(new Bounds(Double.NaN, 0, 0, 1, 1, 1))) == 0,
            "malformed exact obstacle cannot silently authorize movement");
    }

    private static void randomizedExactSweeps() {
        Random random = new Random(124);
        for (int scenario = 0; scenario < 1500; scenario++) {
            double x = scenario % 3 == 0 ? -20_398_052.5 : random.nextDouble() * 100 - 50;
            double z = scenario % 3 == 0 ? -3_262_516.5 : random.nextDouble() * 100 - 50;
            double y = random.nextDouble() * 80 - 60;
            Bounds start = body(x, y, z);
            double dx = random.nextDouble() * 5.6 - 2.8, dy = random.nextDouble() * 3.2 - 1.6, dz = random.nextDouble() * 5.6 - 2.8;
            double ox = x + random.nextDouble() * 6 - 3, oy = y + random.nextDouble() * 4 - 2, oz = z + random.nextDouble() * 6 - 3;
            Bounds obstacle = new Bounds(ox, oy, oz, ox + 0.05 + random.nextDouble(), oy + 0.05 + random.nextDouble(), oz + 0.05 + random.nextDouble());
            double fraction = LancePathSafety.safeFraction(start, dx, dy, dz, List.of(obstacle));
            check(Double.isFinite(fraction) && fraction >= 0 && fraction <= 1, "random precise prefix is finite and bounded");
            if (LancePathSafety.intersects(start, obstacle)) {
                check(fraction == 0, "random exact initial overlap holds for recovery");
                continue;
            }
            for (int sample = 0; sample <= 128; sample++) {
                double t = sample / 128.0;
                boolean hit = LancePathSafety.intersects(translated(start, dx * t, dy * t, dz * t), obstacle);
                check(!hit || t > fraction, "independent sampled collision never precedes the returned exact stop");
                double safeT = fraction * t;
                check(!LancePathSafety.intersects(translated(start, dx * safeT, dy * safeT, dz * safeT), obstacle),
                    "independent samples along the accepted exact prefix remain clear");
            }
            if (!LancePathSafety.intersects(LancePathSafety.swept(start, dx, dy, dz), obstacle)) {
                check(fraction == 1, "obstacle wholly outside broad phase leaves exact move unchanged");
            }
        }
    }

    private static Bounds body(double x, double y, double z) {
        return new Bounds(x - 0.3, y, z - 0.3, x + 0.3, y + 0.6, z + 0.3);
    }

    private static Bounds cell(int x, int y, int z) {
        return new Bounds(x, y, z, x + 1, y + 1, z + 1);
    }

    private static Bounds translated(Bounds box, double x, double y, double z) {
        return new Bounds(box.minX() + x, box.minY() + y, box.minZ() + z, box.maxX() + x, box.maxY() + y, box.maxZ() + z);
    }

    private static Predicate<Bounds> clearOf(List<Bounds> hazards) {
        return box -> hazards.stream().noneMatch(hazard -> LancePathSafety.intersects(box, hazard));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
