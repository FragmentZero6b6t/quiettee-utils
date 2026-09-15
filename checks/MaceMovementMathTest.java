import com.quiettee.utils.util.MaceMovementMath;
import com.quiettee.utils.util.MaceMovementMath.Sweep;
import java.util.Random;

public final class MaceMovementMathTest {
    private static int checks;

    public static void main(String[] args) {

        double offset = MaceMovementMath.offsetAfterMove(90, -1, 101.5);
        near(89, 101.5 + offset, "climbing body's wire endpoint");
        near(-1, 101.5 + offset - 90, "climbing body must not alter ghost dip");
        check(101.5 + offset < 90, "floor at 90 must reject this dip");
        check(101.5 - 10 - 1 >= 90, "regression fixture reproduces old optimistic floor check");

        for (double previousReal : new double[]{-64, 0, 100, 30_000_000}) {
            for (double oldOffset : new double[]{-25, -0.01, 0, 8}) {
                for (double realDy : new double[]{-4, -1.5, 0, 1.5, 4}) {
                    for (double dip : new double[]{-1.75, -1, 0, 0.25, 1.5}) {
                        double previousClaim = previousReal + oldOffset;
                        double currentReal = previousReal + realDy;
                        double nextOffset = MaceMovementMath.offsetAfterMove(previousClaim, dip, currentReal);
                        near(previousClaim + dip, currentReal + nextOffset, "absolute endpoint invariant");
                        near(dip, currentReal + nextOffset - previousClaim, "packet displacement invariant");
                    }
                }
            }
        }

        near(82, MaceMovementMath.ghostY(true, 82, 100, -10), "Alternate pending dive uses actual dive altitude");
        near(90, MaceMovementMath.ghostY(false, 82, 100, -10), "completed return uses resting offset");

        Sweep pane = new Sweep(1.4375, 64, -0.125, 1.5625, 66, 0.125);
        for (double x : new double[]{0.5, 2, 3.5}) {
            check(!intersects(MaceMovementMath.sweptBody(x, 64, 0, x, 64, 0, 0.3, 0.6), pane), "pane bypasses old samples");
        }
        Sweep sweep = MaceMovementMath.sweptBody(0.5, 64, 0, 3.5, 64, 0, 0.3, 0.6);
        check(intersects(sweep, pane), "continuous sweep catches pane");
        check(!intersects(sweep, new Sweep(4, 64, -0.125, 4.125, 66, 0.125)), "clear outside obstacle stays clear");

        Random random = new Random(121);
        for (int i = 0; i < 250; i++) {
            double x0 = random.nextDouble() * 20 - 10, y0 = random.nextDouble() * 100, z0 = random.nextDouble() * 20 - 10;
            double x1 = x0 + random.nextDouble() * 6 - 3, y1 = y0 + random.nextDouble() * 4 - 2, z1 = z0 + random.nextDouble() * 6 - 3;
            Sweep forward = MaceMovementMath.sweptBody(x0, y0, z0, x1, y1, z1, 0.3, 0.6);
            check(forward.equals(MaceMovementMath.sweptBody(x1, y1, z1, x0, y0, z0, 0.3, 0.6)), "reverse sweep symmetry");
            for (int j = 0; j <= 20; j++) {
                double t = j / 20.0;
                double x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t, z = z0 + (z1 - z0) * t;
                Sweep body = MaceMovementMath.sweptBody(x, y, z, x, y, z, 0.3, 0.6);
                check(contains(forward, body), "sweep contains entire intermediate body");
            }
        }
        System.out.println("MaceMovementMathTest passed: " + checks + " checks against production geometry.");
    }

    private static boolean intersects(Sweep a, Sweep b) {
        return a.minX() < b.maxX() && a.maxX() > b.minX() && a.minY() < b.maxY() && a.maxY() > b.minY()
            && a.minZ() < b.maxZ() && a.maxZ() > b.minZ();
    }

    private static boolean contains(Sweep outer, Sweep inner) {
        double e = 1e-10;
        return outer.minX() <= inner.minX() + e && outer.maxX() + e >= inner.maxX()
            && outer.minY() <= inner.minY() + e && outer.maxY() + e >= inner.maxY()
            && outer.minZ() <= inner.minZ() + e && outer.maxZ() + e >= inner.maxZ();
    }

    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1e-8, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
