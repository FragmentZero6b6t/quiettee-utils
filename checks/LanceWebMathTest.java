import com.quiettee.utils.util.LanceWebMath;
import com.quiettee.utils.util.LanceWebMath.Bounds;
import com.quiettee.utils.util.LanceWebMath.Cell;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class LanceWebMathTest {
    private static int checks;

    public static void main(String[] args) {
        stationaryAndPoseCoverage();
        movingInterception();
        sustainedCoverage();
        captureAndEnclosure();
        directionalMaintenance();
        captureInvariants();
        placementSupportAndReach();
        sampledPathInvariants();
        invalidInputs();
        System.out.println("LanceWebMathTest: " + checks + " checks passed");
    }

    private static void stationaryAndPoseCoverage() {
        Bounds standing = new Bounds(0.2, 65, 0.2, 0.8, 66.8, 0.8);
        List<Cell> still = plan(standing, 0, 0, 0, 5, 0.5, 70, 0.5);
        check(still.size() == 2, "stationary body has no unrelated 3x3 side cells");
        check(still.getFirst().equals(new Cell(0, 65, 0)), "first web intersects the standing body's center");
        List<Cell> fractional = plan(standing.offset(0, 0.7, 0), 0, 0, 0, 0, 0.5, 70, 0.5);
        check(fractional.containsAll(Set.of(new Cell(0, 65, 0), new Cell(0, 66, 0), new Cell(0, 67, 0))),
            "fractional standing body can intersect three actual vertical cells");
        List<Cell> gliding = plan(new Bounds(0.2, 65.1, 0.2, 0.8, 65.7, 0.8), 0, 0, 0, 0, 0.5, 70, 0.5);
        check(gliding.equals(List.of(new Cell(0, 65, 0))), "gliding body does not spend a second web above its path");
        check(plan(new Bounds(-0.8, 65.1, -0.8, -0.2, 65.7, -0.2), 0, 0, 0, 0, -0.5, 70, -0.5)
            .equals(List.of(new Cell(-1, 65, -1))), "negative coordinates use floor rather than truncation");
    }

    private static void movingInterception() {

        Bounds runner = new Bounds(34.27, 122.9, 1658.6, 34.87, 123.5, 1659.2);
        List<Cell> led = plan(runner, -1.0, 0, -0.14, 5, 29.56, 126.94, 1658.22);
        check(!led.isEmpty(), "reported runner has reachable future web cells");
        check(led.getFirst().x() == 29, "intercept is led to the ghost's predicted target column");
        check(led.stream().noneMatch(c -> c.x() >= 30), "no obsolete cube cells behind this runner");
        check(led.stream().map(Cell::x).distinct().count() >= 4, "budget can buy forward travel depth");
        for (Cell cell : led) check(reachable(cell, 29.56, 126.94, 1658.22, 5.5), "field candidate obeys ghost reach");

        Bounds small = new Bounds(0.2, 65.2, 0.2, 0.8, 65.8, 0.8);
        List<Cell> diagonal = plan(small, 1, 0, 1, 0, 3, 69, 3);
        check(diagonal.contains(new Cell(1, 65, 1)), "diagonal center path is populated");
        check(diagonal.contains(new Cell(1, 65, 0)), "diagonal body fringe crossing is retained");
        check(!diagonal.contains(new Cell(0, 65, 2)), "diagonal envelope-only corner is not a useful web");
        List<Cell> ascending = plan(small, 0, 0.8, 0, 2, 0.5, 70, 0.5);
        check(ascending.stream().noneMatch(c -> c.y() == 65), "ascending runner does not receive stale low webs");
        check(ascending.stream().anyMatch(c -> c.y() >= 69), "vertical travel receives future height coverage");
        List<Cell> descending = plan(small, 0, -0.8, 0, 2, 0.5, 65, 0.5);
        check(descending.stream().anyMatch(c -> c.y() <= 61), "descending path also uses vertical velocity");

        Bounds tall = new Bounds(0.2, 65.1, 0.2, 0.8, 66.9, 0.8);
        List<Cell> depth = plan(tall, 0.9, 0, 0, 1, 3.5, 69, 0.5);
        check(depth.stream().limit(6).map(Cell::x).distinct().count() >= 4,
            "small volley prioritizes depth before duplicating torso and foot layers");
        check(plan(small, 1, 0, 0, 20, 0.5, 69, 0.5).isEmpty(), "unreachable intercept skips rather than backfilling stale webs");
    }

    private static void sustainedCoverage() {
        Bounds caught = new Bounds(0.2, 65.1, 0.2, 0.8, 65.7, 0.8);
        Set<Cell> oneWeb = Set.of(new Cell(0, 65, 0));
        check(covered(caught, 0, 0, 0, oneWeb), "stationary body with substantial observed coverage needs no volley");
        check(!covered(caught, 0, 0, 0, Set.of()), "no observed web cannot qualify as caught");
        check(!covered(caught.offset(0.75, 0, 0), 0, 0, 0, oneWeb), "thin edge contact is not a secure catch");
        check(!covered(caught.offset(0.75, 0, 0), 0, 0, 0,
            Set.of(new Cell(0, 65, 0), new Cell(0, 66, 0))), "stacked cells do not double-count horizontal coverage");
        check(!covered(caught, 0.2, 0, 0, oneWeb), "slow runner projected out of the web within three ticks is unsecured");
        check(!covered(caught, 0, 0.25, 0, oneWeb), "vertical escape also invalidates the catch");
        check(!covered(caught, 0.5, 0, 0, Set.of(new Cell(0, 65, 0), new Cell(1, 65, 0), new Cell(2, 65, 0))),
            "full-speed passing contact is not labelled immobilized even in a short corridor");
        check(covered(caught.offset(0.5, 0, 0), 0, 0, 0, Set.of(new Cell(0, 65, 0), new Cell(1, 65, 0))),
            "coverage combines adjacent columns under a body straddling the boundary");
        check(!covered(new Bounds(0.2, 65.99, 0.2, 0.8, 66.59, 0.8), 0, 0, 0, oneWeb),
            "vertical edge overlap alone is insufficient");
    }

    private static void sampledPathInvariants() {
        Random random = new Random(122);
        for (int scenario = 0; scenario < 40; scenario++) {
            double shift = scenario % 2 == 0 ? 0 : -100;
            Bounds box = new Bounds(shift + 0.13, 64.11, shift + 0.17, shift + 0.73, 64.71, shift + 0.77);
            double vx = random.nextDouble() * 1.6 - 0.8;
            double vy = random.nextDouble() * 0.8 - 0.4;
            double vz = random.nextDouble() * 1.6 - 0.8;
            List<Cell> cells = plan(box, vx, vy, vz, 2, shift + 0.43 + vx * 2, 68.5, shift + 0.47 + vz * 2);
            Set<Cell> unique = new HashSet<>(cells);
            check(cells.size() <= 64 && cells.size() == unique.size(), "candidate pool is bounded and has no duplicate budget entries");
            for (Cell cell : cells) check(reachable(cell, shift + 0.43 + vx * 2, 68.5, shift + 0.47 + vz * 2, 5.5),
                "every candidate remains inside current ghost reach");

            for (int sample = 0; sample <= 120; sample++) {
                Bounds moved = box.offset(vx * (2 + sample * 0.05), vy * (2 + sample * 0.05), vz * (2 + sample * 0.05));
                for (int x = (int) Math.floor(moved.minX() + 1e-7); x <= (int) Math.floor(moved.maxX() - 1e-7); x++) {
                    for (int y = (int) Math.floor(moved.minY() + 1e-7); y <= (int) Math.floor(moved.maxY() - 1e-7); y++) {
                        for (int z = (int) Math.floor(moved.minZ() + 1e-7); z <= (int) Math.floor(moved.maxZ() - 1e-7); z++) {
                            Cell cell = new Cell(x, y, z);
                            if (reachable(cell, shift + 0.43 + vx * 2, 68.5, shift + 0.47 + vz * 2, 5.5)) {
                                check(unique.contains(cell), "sweep retains sampled 3D path contact: scenario=" + scenario + " sample=" + sample + " cell=" + cell + " count=" + cells.size());
                            }
                        }
                    }
                }
            }
        }
    }

    private static void captureAndEnclosure() {
        Bounds standing = new Bounds(0.2, 65.1, 0.2, 0.8, 66.9, 0.8);
        List<Cell> shell = capture(standing, 0, 0, 0, 5, 0.5, 68, 0.5, true);
        Set<Cell> cube = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = 65; y <= 67; y++) {
                for (int z = -1; z <= 1; z++) cube.add(new Cell(x, y, z));
            }
        }
        check(shell.size() == 27 && new HashSet<>(shell).equals(cube),
            "caught stationary player receives a complete reachable 3x3x3 enclosure");
        check(Set.copyOf(shell.subList(0, 2)).equals(Set.of(new Cell(0, 65, 0), new Cell(0, 66, 0))),
            "the first placement budget secures both body layers before the surrounding shell");
        Bounds gliding = new Bounds(0.2, 65.1, 0.2, 0.8, 65.7, 0.8);
        List<Cell> glideShell = capture(gliding, 0, 0, 0, 4, 0.5, 68, 0.5, true);
        check(new HashSet<>(glideShell).equals(cube), "gliding capture reserves headroom for standing up inside the enclosure");
        check(glideShell.getFirst().equals(new Cell(0, 65, 0)), "actual gliding contact precedes the pose-transition shell");
        List<Cell> overshot = capture(gliding, 1, 0, 0, 20, 0.5, 69, 0.5, false);
        check(!overshot.isEmpty() && overshot.contains(new Cell(0, 65, 0)),
            "an unreachable extrapolated point retains reachable observed-to-led interception cells");
        check(overshot.getFirst().x() >= 3, "reachable forward interception outranks the stale current position");

        Bounds fieldRunner = new Bounds(29865328.32, 134.90, -29905756.56, 29865328.92, 135.50, -29905755.96);
        check(plan(fieldRunner, -1.13, -0.88, 1.34, 6, 29865326.04, 138.69, -29905753.22).isEmpty(),
            "r124 full-led-only descending sweep loses all reachable body intersections");
        List<Cell> field = capture(fieldRunner, -1.13, -0.88, 1.34, 6,
            29865326.04, 138.69, -29905753.22, false);
        check(!field.isEmpty(), "r124 descending runner has reachable capture candidates despite overshot prediction");
        for (Cell cell : field) check(reachable(cell, 29865326.04, 138.69, -29905753.22, 5.5),
            "world-border-distance runner candidates retain ordinary ghost reach");
        check(field.stream().limit(9).map(Cell::x).distinct().count() >= 2,
            "initial runner budget covers travel depth rather than one intercept plane");
        List<Cell> escaping = capture(gliding, 0.4, 0, 0, 4, 2.5, 68, 0.5, true);
        check(escaping.getFirst().equals(new Cell(0, 65, 0)), "escaping maintenance keeps observed contact first");
        check(escaping.stream().limit(9).anyMatch(c -> c.x() >= 2 && c.y() == 65 && c.z() == 0),
            "escape direction receives forward body intersections before cosmetic enclosure corners");
    }

    private static void directionalMaintenance() {
        Bounds body = new Bounds(0.2, 65.1, 0.2, 0.8, 65.7, 0.8);
        Set<Cell> one = Set.of(new Cell(0, 65, 0));
        check(LanceWebMath.hasCurrentWebCoverage(body, one::contains), "current observed coverage is available before sustained certainty");
        check(!LanceWebMath.isEscaping(body, 0, 0, 0, one::contains), "stationary single-web catch does not cause repeated spear interruptions");
        check(!LanceWebMath.isEscaping(body, 0.01, 0, 0, one::contains), "sub-centimeter drift has a maintenance deadband");
        check(!LanceWebMath.isEscaping(body, 0.2, 0, 0, Set.<Cell>of()::contains),
            "unconfirmed or absent coverage cannot be treated as a caught target's escape");
        check(LanceWebMath.isEscaping(body, 0.1, 0, 0, one::contains), "slow eastward creep into a gap triggers directional maintenance");
        check(LanceWebMath.isEscaping(body, -0.1, 0, 0, one::contains), "westward escape is symmetric");
        check(LanceWebMath.isEscaping(body, 0, 0.1, 0, one::contains), "vertical escape also requires maintenance");
        Set<Cell> east = Set.of(new Cell(0, 65, 0), new Cell(1, 65, 0));
        check(!LanceWebMath.isEscaping(body, 0.1, 0, 0, east::contains), "already webbed escape direction leaves the spear couched");
        check(LanceWebMath.isEscaping(body, 0, 0, 0.1, east::contains), "coverage on the wrong axis does not conceal a directional gap");
        check(!LanceWebMath.hasCurrentWebCoverage(body.offset(0.79, 0, 0), one::contains),
            "thin edge contact does not start the enclosure phase");
        check(!LanceWebMath.isEscaping(body, Double.NaN, 0, 0, one::contains), "invalid motion does not claim an observed escape");
    }

    private static void captureInvariants() {
        Random random = new Random(125);
        for (int scenario = 0; scenario < 60; scenario++) {
            double x = scenario % 2 == 0 ? -29990000.73 : 0.27;
            Bounds body = new Bounds(x, 64.13, -0.26, x + 0.6, 64.73, 0.34);
            double vx = random.nextDouble() * 2.4 - 1.2;
            double vy = random.nextDouble() - 0.5;
            double vz = random.nextDouble() * 2.4 - 1.2;
            List<Cell> cells = capture(body, vx, vy, vz, 2 + random.nextInt(10), x + 0.3, 68, 0.04, scenario % 3 == 0);
            check(cells.size() <= 64 && new HashSet<>(cells).size() == cells.size(), "capture pool is bounded and duplicate-free");
            for (Cell cell : cells) check(reachable(cell, x + 0.3, 68, 0.04, 5.5), "every capture/shell cell is reachable");
            try {
                cells.add(new Cell(0, 0, 0));
                check(false, "capture result must be immutable");
            } catch (UnsupportedOperationException expected) {
                check(true, "capture result is immutable");
            }
        }
        Bounds body = new Bounds(0.2, 65.1, 0.2, 0.8, 65.7, 0.8);
        check(capture(body, Double.NaN, 0, 0, 4, 0.5, 68, 0.5, false).isEmpty(), "capture rejects unknown velocity");
        check(capture(body, 0, 0, 0, 4, Double.MAX_VALUE, 68, 0.5, true).isEmpty(), "capture rejects malformed reach origin");
        check(LanceWebMath.captureCandidates(body, 0, 0, 0, 4, 0.5, 68, 0.5, -1, true).isEmpty(), "capture rejects negative reach");
    }

    private static void placementSupportAndReach() {
        Cell intended = new Cell(0, 64, 0), below = new Cell(0, 63, 0), above = new Cell(0, 65, 0);
        Set<Cell> locallyOccupied = new HashSet<>(Set.of(below));
        Set<Cell> pending = new HashSet<>(Set.of(below));
        var direct = LanceWebMath.placementClick(intended, 0.5, 68, 0.5, 5.5, true,
            cell -> locallyOccupied.contains(cell) && !pending.contains(cell));
        check(direct != null && direct.clicked().equals(intended),
            "an unconfirmed neighboring web cannot redirect the server placement into its still-air cell");
        check(LanceWebMath.placementClick(intended, 0.5, 68, 0.5, 5.5, false,
            cell -> locallyOccupied.contains(cell) && !pending.contains(cell)) == null,
            "unconfirmed support is insufficient when direct air placement is disabled");
        pending.clear();
        var supported = LanceWebMath.placementClick(intended, 0.5, 68, 0.5, 5.5, false, locallyOccupied::contains);
        check(supported != null && supported.clicked().equals(below) && supported.sideY() == 1 && supported.hitY() == 64,
            "a confirmed nonreplaceable support is clicked on its actual face toward the intended cell");
        check(new Cell(supported.clicked().x() + supported.sideX(), supported.clicked().y() + supported.sideY(),
            supported.clicked().z() + supported.sideZ()).equals(intended), "supported placement resolves to the intended cell");

        Set<Cell> replaceable = Set.of(below);
        var vegetation = LanceWebMath.placementClick(intended, 0.5, 68, 0.5, 5.5, true,
            cell -> locallyOccupied.contains(cell) && !replaceable.contains(cell));
        check(vegetation != null && vegetation.clicked().equals(intended), "replaceable neighbors require direct-cell placement");

        var rangeFallback = LanceWebMath.placementClick(intended, 0.5, 69.75, 0.5, 5.5, true, locallyOccupied::contains);
        check(rangeFallback != null && rangeFallback.clicked().equals(intended),
            "a reachable cell center remains available when its support face exceeds reach");
        check(LanceWebMath.placementClick(intended, 0.5, 69.75, 0.5, 5.5, false, locallyOccupied::contains) == null,
            "nearest-cell reach alone cannot authorize an unreachable actual support click");
        check(LanceWebMath.placementClick(intended, 0.5, 70.1, 0.5, 5.5, true, cell -> false) == null,
            "direct placement also checks the transmitted center point rather than the nearest cell edge");
        var fromBelow = LanceWebMath.placementClick(intended, 0.5, 61, 0.5, 5.5, false, Set.of(below, above)::contains);
        check(fromBelow != null && fromBelow.clicked().equals(above) && fromBelow.sideY() == -1,
            "support face selection uses the supplied ghost eye direction");
        Cell remote = new Cell(20407618, 102, -9300762);
        var field = LanceWebMath.placementClick(remote, 20407617.49, 107.84, -9300760.92, 5.5, true, cell -> false);
        check(field != null && field.clicked().equals(remote), "r126 lower body cell stays a direct intended-cell click at world-border-scale coordinates");
        check(LanceWebMath.placementClick(intended, Double.NaN, 68, 0.5, 5.5, true, cell -> true) == null,
            "nonfinite placement origins fail closed");
        check(LanceWebMath.placementClick(intended, 0.5, 68, 0.5, -1, true, cell -> true) == null,
            "negative click reach fails closed");
    }

    private static void invalidInputs() {
        Bounds normal = new Bounds(0, 65, 0, 0.6, 65.6, 0.6);
        check(plan(normal, Double.NaN, 0, 0, 1, 0, 69, 0).isEmpty(), "unknown motion fails closed");
        check(plan(normal, 0, 0, 0, 1, Double.MAX_VALUE, 69, 0).isEmpty(), "malformed coordinates cannot cause unbounded enumeration");
        check(plan(new Bounds(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1), 0, 0, 0, 0, 0, 0, 0).isEmpty(), "invalid body fails closed");
        check(LanceWebMath.candidates(normal, 0, 0, 0, 0, 0, 69, 0, -1).isEmpty(), "negative reach fails closed");
        check(!covered(normal, Double.NaN, 0, 0, Set.of(new Cell(0, 65, 0))), "unknown motion cannot confirm a catch");
    }

    private static List<Cell> plan(Bounds body, double vx, double vy, double vz, double lead, double eyeX, double eyeY, double eyeZ) {
        return LanceWebMath.candidates(body, vx, vy, vz, lead, eyeX, eyeY, eyeZ, 5.5);
    }

    private static List<Cell> capture(Bounds body, double vx, double vy, double vz, double lead, double eyeX, double eyeY, double eyeZ, boolean contained) {
        return LanceWebMath.captureCandidates(body, vx, vy, vz, lead, eyeX, eyeY, eyeZ, 5.5, contained);
    }

    private static boolean covered(Bounds body, double vx, double vy, double vz, Set<Cell> webs) {
        return LanceWebMath.hasSustainedWebCoverage(body, vx, vy, vz, webs::contains);
    }

    private static boolean reachable(Cell cell, double x, double y, double z, double reach) {

        double px = Math.min(Math.max(x, cell.x()), cell.x() + 1.0);
        double py = Math.min(Math.max(y, cell.y()), cell.y() + 1.0);
        double pz = Math.min(Math.max(z, cell.z()), cell.z() + 1.0);
        return (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz) <= reach * reach + 1e-7;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
