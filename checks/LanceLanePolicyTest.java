package com.quiettee.utils.modules.combat;

public final class LanceLanePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        observedAndPotentialAreDifferent();
        healthAndStrictLimits();
        rankedChoices();
        finiteBoundaries();
        System.out.println("LanceLanePolicyTest: " + checks + " checks passed");
    }

    private static void observedAndPotentialAreDifferent() {
        double limit = LanceLanePolicy.hardLimit(20, 0.9, false);
        double hypothetical = score(0, 13.993, 4, 0.9, limit, true, true);
        check(Double.isFinite(hypothetical), "r125's 13.993 HP hypothetical estimate no longer hard-stops an otherwise safe lane");
        double isolated = score(0, 0, 4, 0.9, limit, true, true);
        check(Double.isFinite(isolated) && isolated < hypothetical, "no nearby actors and no potential threat make the isolated lane cheaper");
        check(Double.isFinite(score(0, Double.POSITIVE_INFINITY, 4, 0.9, limit, true, true)), "unknown potential exposure remains a bounded preference");
        check(Double.isInfinite(score(13.993, 0, 0, 0.9, limit, true, true)), "the same observed danger is refused even beside the current body");
        check(Double.isFinite(score(1, 0, 0, 0.9, limit, true, true)), "a 0.9 preference does not deny one HP observed exposure at healthy twenty HP");
    }

    private static void healthAndStrictLimits() {
        close(LanceLanePolicy.hardLimit(20, 0.9, false), 5, "twenty health uses the quarter-health cap");
        close(LanceLanePolicy.hardLimit(40, 0.9, false), 6, "extra absorption cannot exceed the six-HP cap");
        close(LanceLanePolicy.hardLimit(9, 0.9, false), 1, "nine health preserves the eight-health reserve");
        for (double hp : new double[] {-10, 0, 1, 7.99, 8}) {
            double limit = LanceLanePolicy.hardLimit(hp, 0.9, false);
            close(limit, 0, "low health permits no positive observed exposure");
            check(Double.isInfinite(score(0.01, 0, 0, 0.9, limit, true, true)), "low-health positive damage causes a hold");
            check(Double.isFinite(score(0, 0, 0, 0.9, limit, true, true)), "low health does not prevent a zero-exposure move");
        }
        double strict = LanceLanePolicy.hardLimit(20, 0.9, true);
        close(strict, 0.9, "strict mode promotes preference to the hard limit");
        check(Double.isFinite(score(0.9, 25, 0, 0.9, strict, true, true)), "strict equality passes even with potential exposure");
        check(Double.isInfinite(score(1, 0, 0, 0.9, strict, true, true)), "strict mode rejects the one-HP lane");
        close(LanceLanePolicy.hardLimit(8.5, 0.9, true), 0.5, "strict mode still preserves the health reserve");
        close(LanceLanePolicy.hardLimit(20, -1, true), 0, "negative strict preference cannot permit negative damage");
    }

    private static void rankedChoices() {
        double limit = LanceLanePolicy.hardLimit(20, 0.9, false);
        double saferFar = score(0.231, 0, 10, 0.9, limit, false, true);
        double worseNear = score(2, 0, 1, 0.9, limit, true, true);
        check(saferFar < worseNear, "meaningfully safer observed exposure outweighs a shorter trip and current-axis preference");
        check(Double.isFinite(saferFar), "safe horizontal choice stays eligible after the old twenty-tick dwell");
        check(Double.isInfinite(score(13.993, 0, 0, 0.9, limit, true, true)), "dwell expiry cannot make unsafe overhead preferable");
        double current = score(0, 0, 3, 0.9, limit, true, true);
        double switchNear = score(0, 0, 2, 0.9, limit, false, true);
        double switchMuchNearer = score(0, 0, 0, 0.9, limit, false, true);
        check(current < switchNear, "a one-block saving does not flip the current axis");
        check(switchMuchNearer < current, "a larger travel improvement can beat the stable-axis preference");
        check(score(0, 0, 3, 0.9, limit, false, true) < score(0, 0, 3, 0.9, limit, false, false), "a fully clear approach wins an otherwise equal choice");
        check(Double.isFinite(score(0, 0, 3, 0.9, limit, false, false)), "a safe capped first step stays eligible without an entirely clear approach");
        check(score(0, 25, 0, 0.9, limit, false, true) < score(0, 25, 1, 0.9, limit, false, true), "even maximum potential cost leaves ordinary travel ranking intact");
    }

    private static void finiteBoundaries() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            close(LanceLanePolicy.hardLimit(invalid, 0.9, false), 0, "unknown health fails closed");
            close(LanceLanePolicy.hardLimit(20, invalid, true), 0, "unknown preference fails closed");
            check(Double.isInfinite(score(invalid, 0, 0, 0.9, 5, true, true)), "unknown actual exposure fails closed");
            check(Double.isInfinite(score(0, 0, invalid, 0.9, 5, true, true)), "invalid travel does not enter the ranking");
            check(Double.isInfinite(score(0, 0, 0, 0.9, invalid, true, true)), "an invalid hard limit fails closed");
            close(score(0, invalid, 0, 0.9, 5, true, true), score(0, 25, 0, 0.9, 5, true, true), "unknown potential uses the bounded conservative preference");
        }
        check(Double.isInfinite(score(-1, 0, 0, 0.9, 5, true, true)), "negative actual damage is not treated as safety");
        close(score(0, -1, 0, 0.9, 5, true, true), score(0, 25, 0, 0.9, 5, true, true), "negative potential is unknown, not a discount");
        close(score(0, Double.MAX_VALUE, 0, 0.9, 5, true, true), score(0, 25, 0, 0.9, 5, true, true), "very large potential cannot become a numerical hard veto");
        check(Double.isFinite(score(5, 0, 0, 0.9, 5, true, true)), "actual exposure at the hard limit is eligible");
        check(Double.isInfinite(score(Math.nextUp(5.0), 0, 0, 0.9, 5, true, true)), "actual exposure above the limit is rejected");
        close(LanceLanePolicy.hardLimit(Double.MAX_VALUE, 0.9, false), 6, "large finite health stays bounded");
    }

    private static double score(double actual, double potential, double travel, double preferred, double hard,
                                boolean current, boolean clear) {
        return LanceLanePolicy.score(actual, potential, travel, preferred, hard, current, clear);
    }

    private static void close(double actual, double expected, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-9, message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
