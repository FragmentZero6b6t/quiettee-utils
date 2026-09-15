import com.quiettee.utils.modules.combat.LanceCrystalSafety;
import com.quiettee.utils.modules.combat.LanceCrystalSafety.DamageModel;
import com.quiettee.utils.modules.combat.LanceCrystalSafety.Point;
import com.quiettee.utils.modules.combat.LanceCrystalSafety.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class LanceCrystalSafetyTest {
    private static int checks;

    public static void main(String[] args) {
        vanillaFormula();
        cheapBounds();
        movementSamples();
        multipleThreats();
        observedPlacementReach();
        invalidInputs();
        System.out.println("LanceCrystalSafetyTest: " + checks + " checks passed");
    }

    private static void vanillaFormula() {
        close(LanceCrystalSafety.rawDamage(0, 1), 85, "full-strength crystal raw damage");
        close(LanceCrystalSafety.rawDamage(6, 1), 32.5, "half-radius damage retains fractional precision");
        close(LanceCrystalSafety.rawDamage(6, 0.5), 14.125, "exposure scales impact before the quadratic damage term");
        close(LanceCrystalSafety.rawDamage(12, 1), 1, "exact radius retains vanilla base damage");
        close(LanceCrystalSafety.rawDamage(12.001, 1), 0, "outside the blast radius has no damage");
        close(LanceCrystalSafety.rawDamage(4, 0), 1, "zero ray exposure retains vanilla base damage");
        Random random = new Random(125);
        for (int i = 0; i < 1000; i++) {
            double distance = random.nextDouble() * 12;
            double lowerExposure = random.nextDouble();
            double higherExposure = lowerExposure + random.nextDouble() * (1 - lowerExposure);
            check(LanceCrystalSafety.rawDamage(distance, lowerExposure) <= LanceCrystalSafety.rawDamage(distance, higherExposure),
                "full exposure is a monotonic raw-damage bound");
        }
    }

    private static void cheapBounds() {
        List<Point> samples = new ArrayList<>();
        DamageModel reduced = new DamageModel() {
            public double reduce(double raw) { return raw * 0.01; }
            public double exact(Point ghost, Point crystal) { samples.add(ghost); return 100; }
        };
        Point source = new Point(0, 0, 0);
        Result safe = estimate(new Point(-2, 0, 0), new Point(2, 0, 0), List.of(source), 1, reduced);
        close(safe.maximumDamage(), 0.85, "closest point bounds the whole movement segment");
        check(samples.isEmpty(), "a proven low full-exposure bound pays for no world rays");
        Result distant = estimate(new Point(13, 0, 0), new Point(30, 0, 0), List.of(source), 0, reduced);
        close(distant.maximumDamage(), 0, "entire segment outside radius bypasses damage evaluation");
        check(distant.threatPoint() == null, "absent damage has no threat point");
    }

    private static void movementSamples() {
        Point source = new Point(0, 0, 0);
        Result crossing = estimate(new Point(-2, 0, 0), new Point(2, 0, 0), List.of(source), 75, fullExposure());
        close(crossing.maximumDamage(), 85, "safe endpoints do not hide a dangerous middle crossing");
        check(source.equals(crossing.threatPoint()), "unsafe path reports the threatening crystal");

        List<Point> samples = new ArrayList<>();
        DamageModel occluded = new DamageModel() {
            public double reduce(double raw) { return raw; }
            public double exact(Point ghost, Point crystal) { samples.add(ghost); return 0.1; }
        };
        Point offAxis = new Point(0.37, 1, 0);
        Result covered = estimate(new Point(0, 0, 0), new Point(1, 0, 0), List.of(offAxis), 1, occluded);
        close(covered.maximumDamage(), 0.1, "exact occlusion can permit a lane rejected by a full-exposure-only rule");
        check(samples.stream().anyMatch(p -> Math.abs(p.x() - 0.37) < 1e-9), "off-grid nearest approach is checked");
        List<Double> positions = samples.stream().map(Point::x).distinct().sorted().toList();
        close(positions.getFirst(), 0, "segment start is sampled");
        close(positions.getLast(), 1, "segment end is sampled");
        for (int i = 1; i < positions.size(); i++) {
            check(positions.get(i) - positions.get(i - 1) <= 0.25 + 1e-9, "no exposure sample spacing exceeds a quarter block");
        }
        DamageModel narrowOpening = new DamageModel() {
            public double reduce(double raw) { return raw; }
            public double exact(Point ghost, Point crystal) { return ghost.x() > 0.24 && ghost.x() < 0.26 ? 5 : 0.1; }
        };
        Result opening = estimate(new Point(0, 0, 0), new Point(1, 0, 0), List.of(new Point(0.5, 1, 0)), 1, narrowOpening);
        close(opening.maximumDamage(), 5, "intermediate opening is checked beyond endpoints and nearest approach");
        Result stationary = estimate(new Point(3, 0, 0), new Point(3, 0, 0), List.of(source), 1, fullExposure());
        check(Double.isFinite(stationary.maximumDamage()) && stationary.maximumDamage() > 1, "stationary ghost evaluates without division by zero");
    }

    private static void multipleThreats() {
        Point far = new Point(0, 20, 0), dangerous = new Point(1, 0, 0);
        Result result = estimate(new Point(0, 0, 0), new Point(2, 0, 0), List.of(far, dangerous), 1, fullExposure());
        check(result.maximumDamage() > 1 && dangerous.equals(result.threatPoint()), "later dangerous source is not concealed by earlier harmless source");
        close(estimate(new Point(0, 0, 0), new Point(2, 0, 0), List.of(), 1, fullExposure()).maximumDamage(), 0,
            "empty observed threat set adds no arbitrary movement penalty");
    }

    private static void observedPlacementReach() {
        Point base = new Point(0, 64, 0);
        List<Point> noActors = List.of();
        check(noActors.stream().noneMatch(eye -> LanceCrystalSafety.canPlaceAt(eye, base, 4.5)),
            "an empty observed actor set cannot create a reachable future placement");
        check(!LanceCrystalSafety.canPlaceAt(null, base, 4.5), "missing observed player eye cannot authorize a hypothetical placement");
        check(!LanceCrystalSafety.canPlaceAt(new Point(6.5, 64.5, 0.5), base, 4.5),
            "exact base-AABB reach plus server allowance boundary is rejected by vanilla's strict comparison");
        check(LanceCrystalSafety.canPlaceAt(new Point(6.499, 64.5, 0.5), base, 4.5),
            "a point just inside the server allowance can reach the base");
        check(!LanceCrystalSafety.canPlaceAt(new Point(6.501, 64.5, 0.5), base, 4.5),
            "a point just outside the server allowance cannot reach the base");
        check(LanceCrystalSafety.canPlaceAt(new Point(6.4, 64.5, 0.5), base, 4.5),
            "reach uses the nearest block face rather than its more distant center");
        check(LanceCrystalSafety.canPlaceAt(new Point(-5.499, 64.5, 0.5), base, 4.5),
            "negative-side reach measures from the matching nearest face");
        check(LanceCrystalSafety.canPlaceAt(new Point(0.5, 70.499, 0.5), base, 4.5),
            "elevated actor can reach the top face before crossing the height boundary");
        check(!LanceCrystalSafety.canPlaceAt(new Point(0.5, 70.5, 0.5), base, 4.5),
            "moving the observed eye above the vertical reach boundary removes eligibility");
        check(!LanceCrystalSafety.canPlaceAt(new Point(0.5, 58.5, 0.5), base, 4.5),
            "a base above the eye uses its lower face for strict reach");
        check(LanceCrystalSafety.canPlaceAt(new Point(7.9, 64.5, 0.5), base, 6),
            "a larger observed range attribute is respected rather than capped to survival defaults");
        for (double shift : new double[] { -29_999_980, 29_999_980 }) {
            Point shiftedBase = new Point(shift, 64, shift);
            check(LanceCrystalSafety.canPlaceAt(new Point(shift + 6.499, 64.5, shift + 0.5), shiftedBase, 4.5),
                "ordinary world-border coordinates preserve inside-range placement eligibility");
            check(!LanceCrystalSafety.canPlaceAt(new Point(shift + 6.5, 64.5, shift + 0.5), shiftedBase, 4.5),
                "exact strict reach boundary remains stable at world-border coordinates");
        }
        check(!LanceCrystalSafety.canPlaceAt(new Point(Double.NaN, 64.5, 0.5), base, 4.5), "unknown player eye fails closed");
        check(!LanceCrystalSafety.canPlaceAt(new Point(0.5, 65.5, 0.5), base, Double.NaN), "unknown reach attribute fails closed");
        check(!LanceCrystalSafety.canPlaceAt(new Point(0.5, 65.5, 0.5), base, -1), "negative reach does not inherit the server allowance");
        check(!LanceCrystalSafety.canPlaceAt(new Point(0.5, 65.5, 0.5), base, 65), "malformed extreme reach is rejected without altering ordinary custom ranges");
    }

    private static void invalidInputs() {
        Point origin = new Point(0, 0, 0);
        check(Double.isInfinite(LanceCrystalSafety.rawDamage(Double.NaN, 1)), "unknown distance fails closed");
        check(Double.isInfinite(LanceCrystalSafety.rawDamage(2, -0.5)), "invalid exposure fails closed");
        check(Double.isInfinite(estimate(new Point(Double.NaN, 0, 0), origin, List.of(origin), 1, fullExposure()).maximumDamage()),
            "unknown movement cannot be labelled safe");
        check(Double.isInfinite(estimate(origin, origin, List.of(new Point(Double.MAX_VALUE, 0, 0)), 1, fullExposure()).maximumDamage()),
            "overflowing threat coordinates cannot disappear from the radius calculation");
        check(Double.isInfinite(estimate(origin, origin, List.of(origin), Double.NaN, fullExposure()).maximumDamage()),
            "invalid damage limit fails closed");
        DamageModel broken = new DamageModel() {
            public double reduce(double raw) { return raw; }
            public double exact(Point ghost, Point crystal) { return Double.NaN; }
        };
        check(Double.isInfinite(estimate(origin, origin, List.of(origin), 1, broken).maximumDamage()), "missing ray result fails closed");
        DamageModel brokenReduction = new DamageModel() {
            public double reduce(double raw) { return Double.NaN; }
            public double exact(Point ghost, Point crystal) { return 0; }
        };
        check(Double.isInfinite(estimate(origin, origin, List.of(origin), 1, brokenReduction).maximumDamage()), "missing reduction snapshot fails closed");
    }

    private static Result estimate(Point from, Point to, List<Point> crystals, double threshold, DamageModel model) {
        return LanceCrystalSafety.estimateSegment(from, to, crystals, threshold, model);
    }

    private static DamageModel fullExposure() {
        return new DamageModel() {
            public double reduce(double raw) { return raw; }
            public double exact(Point ghost, Point crystal) {
                double dx = ghost.x() - crystal.x(), dy = ghost.y() - crystal.y(), dz = ghost.z() - crystal.z();
                return LanceCrystalSafety.rawDamage(Math.sqrt(dx * dx + dy * dy + dz * dz), 1);
            }
        };
    }

    private static void close(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-8, message); }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
