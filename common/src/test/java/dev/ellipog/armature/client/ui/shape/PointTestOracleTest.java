package dev.ellipog.armature.client.ui.shape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The point tests, held to the arithmetic they replaced: same tables, bit for bit.
 *
 * <h2>Why a copy of the old code rather than a property</h2>
 *
 * <p>The pentagon's normals and apothem are constants now rather than eleven calls to {@code cos} and
 * {@code sin} per sample, the gear's pitch is chosen rather than divided for, and a turned shape finds its
 * fit without a map lookup on every sample. Each of those is meant to change <b>nothing</b>: not a pixel
 * of a span, which a property such as "the outline is symmetric" would be far too coarse to see. So the
 * point tests as they were are kept below, every built-in's, and the tables sampled from them are asked
 * to equal the tables the shapes draw from at every size from 1 to 128, row by row.
 *
 * <p>The comparison goes through the same sampler on both sides ({@link Shapes#unit} builds the oracle's
 * shape exactly as the built-ins are built), so a difference can only come from the point test itself.
 * The turn's oracle restates the old fit too, looked up per sample from a map, so the turned shapes are
 * held to the turn as it was rather than to themselves.
 */
@DisplayName("the point tests, held to the arithmetic they replaced")
class PointTestOracleTest {

    private static final int LARGEST = 128;

    // ------------------------------------------------------------------
    // The constants, restated from Shapes by their own expressions
    // ------------------------------------------------------------------

    private static final double ON_BOUNDARY = 1.0E-9;
    private static final int GRID_MIN_SIZE = 4;
    private static final double HEXAGON_HALF_HEIGHT = Math.sqrt(3.0) / 4.0;
    private static final double HEXAGON_TAPER = 1.0 / Math.sqrt(3.0);
    private static final double OCTAGON_DIAGONAL = 1.0 / Math.sqrt(2.0);
    private static final double PENTAGON_VERTEX = 2.0 * Math.PI / 5.0;
    private static final double PENTAGON_APOTHEM = PENTAGON_VERTEX / 2.0;
    private static final double STAR_PINCH = 0.62;
    private static final double STAR_TIP_GROWTH = 0.25;
    private static final double PENTAGON_RADIUS = 0.5 / Math.cos(Math.PI / 2.0 - PENTAGON_VERTEX);
    private static final double PENTAGON_SAG = (PENTAGON_RADIUS - PENTAGON_RADIUS
            * Math.cos(PENTAGON_APOTHEM)) / 2.0;
    private static final int TOME_FORE_EDGE_DIVISOR = 3;
    private static final int TOME_NOTCH_DEPTH_DIVISOR = 16;
    private static final int TOME_NOTCH_LENGTH_DIVISOR = 8;
    private static final int GEAR_TEETH = 8;
    private static final int GEAR_TEETH_SMALL = 6;
    private static final int GEAR_SMALL_SIZE = 32;
    private static final double GEAR_HUB = 0.34;
    private static final double GEAR_ROOT_SHARE = 0.62;
    private static final double GEAR_TIP_SHARE = 0.30;
    private static final double HEART_TOP = 1.512;
    private static final double HEART_BOTTOM = -1.0;
    private static final double HEART_MIDDLE = (HEART_TOP + HEART_BOTTOM) / 2.0;
    private static final double HEART_SPAN = HEART_TOP - HEART_BOTTOM;
    private static final int FIT_DIRECTIONS = 512;
    private static final int FIT_BISECTIONS = 20;
    private static final double FIT_SLACK = 1.0E-9;

    // ------------------------------------------------------------------
    // The point tests as they were
    // ------------------------------------------------------------------

    private static double gridV(double v, int size) {
        if (size < GRID_MIN_SIZE) {
            return v;
        }
        double shift = 0.5 / size;
        return v < 0 ? v - shift : v + shift;
    }

    private static boolean roundedInside(double u, double v, int size, double radius) {
        double y = gridV(v, size);
        double qx = Math.max(0.0, Math.abs(u) - (0.5 - radius));
        double qy = Math.max(0.0, Math.abs(y) - (0.5 - radius));
        return qx * qx + qy * qy <= radius * radius + ON_BOUNDARY;
    }

    private static boolean starInside(double u, double v, int size) {
        double a = Math.abs(u) * 2.0;
        double b = Math.abs(v) * 2.0;
        if (Math.pow(a, STAR_PINCH) + Math.pow(b, STAR_PINCH) <= 1.0 + ON_BOUNDARY) {
            return true;
        }
        if (Math.abs(v) <= 0.5 + ON_BOUNDARY) {
            double minHalf = 0.5 / size + STAR_TIP_GROWTH * (0.5 - Math.abs(v));
            if (Math.abs(u) <= minHalf + ON_BOUNDARY) {
                return true;
            }
        }
        if (Math.abs(u) <= 0.5 + ON_BOUNDARY) {
            double minHalf = 0.5 / size + STAR_TIP_GROWTH * (0.5 - Math.abs(u));
            if (Math.abs(v) <= minHalf + ON_BOUNDARY) {
                return true;
            }
        }
        return false;
    }

    /** The pentagon with its trigonometry worked out per sample, which is what the constants replaced. */
    private static boolean pentagonInside(double u, double v, int size) {
        double x = u;
        double y = -v + PENTAGON_SAG;
        double limit = PENTAGON_RADIUS * Math.cos(PENTAGON_APOTHEM);
        for (int side = 0; side < 5; side++) {
            double angle = Math.PI / 2.0 + PENTAGON_VERTEX * side + PENTAGON_APOTHEM;
            if (x * Math.cos(angle) + y * Math.sin(angle) > limit + ON_BOUNDARY) {
                return false;
            }
        }
        return true;
    }

    private static boolean tomeInside(double u, double v, int size) {
        double y = gridV(v, size);
        if (Math.abs(y) > 0.5) {
            return false;
        }
        double radius = Math.min(0.5, Math.max(1, size / TOME_FORE_EDGE_DIVISOR) / (double) size);
        double corner = 0.5 - radius;
        double qx = Math.max(0.0, u - corner);
        double qy = Math.max(0.0, Math.abs(y) - corner);
        if (qx * qx + qy * qy > radius * radius + ON_BOUNDARY) {
            return false;
        }
        double depth = Math.min(1.0 / 3.0, Math.max(1, size / TOME_NOTCH_DEPTH_DIVISOR) / (double) size);
        double length = Math.max(2, size / TOME_NOTCH_LENGTH_DIVISOR) / (double) size;
        double left = -0.5 + (Math.abs(y) >= 0.5 - length ? depth : 0.0);
        return u >= left - ON_BOUNDARY;
    }

    /** The gear with its pitch divided for per sample, which is what the constants replaced. */
    private static boolean gearInside(double u, double v, int size) {
        double radius = Math.hypot(u, v);
        if (radius > 0.5 + ON_BOUNDARY) {
            return false;
        }
        if (radius <= GEAR_HUB + ON_BOUNDARY) {
            return true;
        }
        int teeth = size < GEAR_SMALL_SIZE ? GEAR_TEETH_SMALL : GEAR_TEETH;
        double pitch = 2.0 * Math.PI / teeth;
        double fromRoot = (radius - GEAR_HUB) / (0.5 - GEAR_HUB);
        double half = pitch * (GEAR_ROOT_SHARE + (GEAR_TIP_SHARE - GEAR_ROOT_SHARE) * fromRoot) / 2.0;
        double angle = Math.atan2(v, u) + Math.PI / 2.0;
        angle -= pitch * Math.round(angle / pitch);
        return Math.abs(angle) <= half + ON_BOUNDARY;
    }

    private static boolean heartInside(double u, double v, int size) {
        double cu = u * HEART_SPAN;
        double cv = HEART_MIDDLE - v * HEART_SPAN;
        double dy = cv - Math.pow(Math.abs(cu), 2.0 / 3.0);
        return cu * cu + dy * dy <= 1.0 + ON_BOUNDARY;
    }

    private static boolean rectInside(double u, double v, int size) {
        return Math.abs(u) <= 0.5 + ON_BOUNDARY && Math.abs(v) <= 0.5 + ON_BOUNDARY;
    }

    /** One built-in, its name for a message, and the point test it was drawn from. */
    private record BuiltIn(String name, Shape shape, Shapes.Unit old) {
    }

    /** Every built-in beside the point test it was drawn from, in the order {@link Shapes} declares them. */
    private static List<BuiltIn> builtIns() {
        return List.of(
                new BuiltIn("rect", Shapes.RECT, PointTestOracleTest::rectInside),
                new BuiltIn("circle", Shapes.CIRCLE, (u, v, size) -> u * u + v * v <= 0.25 + ON_BOUNDARY),
                new BuiltIn("hexagon", Shapes.HEXAGON, (u, v, size) -> {
                    double y = gridV(v, size);
                    return Math.abs(y) <= HEXAGON_HALF_HEIGHT + ON_BOUNDARY
                            && Math.abs(u) <= 0.5 - Math.abs(y) * HEXAGON_TAPER + ON_BOUNDARY;
                }),
                new BuiltIn("tome", Shapes.TOME, PointTestOracleTest::tomeInside),
                new BuiltIn("diamond", Shapes.DIAMOND, (u, v, size) -> {
                    double y = gridV(v, size);
                    return Math.abs(u) + Math.abs(y) <= 0.5 + ON_BOUNDARY
                            || (Math.abs(y) <= 0.5 + ON_BOUNDARY && Math.abs(u) <= 0.5 / size + ON_BOUNDARY);
                }),
                new BuiltIn("star", Shapes.STAR, PointTestOracleTest::starInside),
                new BuiltIn("octagon", Shapes.OCTAGON, (u, v, size) -> {
                    double y = gridV(v, size);
                    return Math.abs(u) <= 0.5 && Math.abs(y) <= 0.5
                            && Math.abs(u) + Math.abs(y) <= OCTAGON_DIAGONAL + ON_BOUNDARY;
                }),
                new BuiltIn("pentagon", Shapes.PENTAGON, PointTestOracleTest::pentagonInside),
                new BuiltIn("gear", Shapes.GEAR, PointTestOracleTest::gearInside),
                new BuiltIn("heart", Shapes.HEART, PointTestOracleTest::heartInside),
                new BuiltIn("rounded", Shapes.ROUNDED, (u, v, size) -> roundedInside(u, v, size,
                        Math.min(0.5, Math.max(1, size / 4) / (double) size))));
    }

    // ------------------------------------------------------------------
    // The turn as it was
    // ------------------------------------------------------------------

    /**
     * {@code Shapes.rotated}'s point test as it stood: undo the fit, looked up per sample from a map, then
     * undo the turn and ask the base. Unbounded, which changes no value: the old map's bound only ever
     * cost a re-measurement.
     */
    private static Shapes.Unit oldTurn(Shapes.Unit base, double degrees) {
        double radians = Math.toRadians(degrees % 360.0);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Shapes.Unit raw = (u, v, size) -> base.isInside(u * cos + v * sin, -u * sin + v * cos, size);
        Map<Integer, Double> fits = new HashMap<>();
        return (u, v, size) -> {
            double fit = fits.computeIfAbsent(size, each -> oldFit(raw, each));
            return raw.isInside(u / fit, v / fit, size);
        };
    }

    private static double oldFit(Shapes.Unit raw, int size) {
        double reach = oldMeasure(raw, size);
        return reach <= 0.5 + FIT_SLACK ? 1.0 : 0.5 / (reach - FIT_SLACK);
    }

    private static double oldMeasure(Shapes.Unit raw, int size) {
        if (!raw.isInside(0, 0, size)) {
            return 0.0;
        }
        double reach = 0.0;
        for (int i = 0; i < FIT_DIRECTIONS; i++) {
            double angle = (2.0 * Math.PI * i) / FIT_DIRECTIONS;
            double du = Math.cos(angle);
            double dv = Math.sin(angle);
            double inside = 0.0;
            double outside = 1.0;
            for (int step = 0; step < FIT_BISECTIONS; step++) {
                double mid = (inside + outside) / 2.0;
                if (raw.isInside(mid * du, mid * dv, size)) {
                    inside = mid;
                }
                else {
                    outside = mid;
                }
            }
            reach = Math.max(reach, Math.max(Math.abs(inside * du), Math.abs(inside * dv)));
        }
        return reach;
    }

    /** A shape's own unit definition, which every built-in and every turn of one can state. */
    private static Shapes.Unit unitOf(Shape shape) {
        return ((Shapes.Normalized) shape).unit();
    }

    private static void assertSameTable(Shape expected, Shape actual, int size, String what) {
        for (int row = 0; row < size; row++) {
            assertArrayEquals(expected.spans(row, size), actual.spans(row, size),
                    what + " at " + size + "px, row " + row);
        }
    }

    // ------------------------------------------------------------------
    // The comparisons
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every built-in draws the table its old point test drew, at every size from 1 to 128")
    void everyTableIsTheTableItWas() {
        for (BuiltIn each : builtIns()) {
            Shape old = Shapes.unit(each.old());
            for (int size = 1; size <= LARGEST; size++) {
                assertSameTable(old, each.shape(), size, each.name());
            }
        }
    }

    @Test
    @DisplayName("the two shapes whose arithmetic moved keep their raw rows too")
    void theRawRowsAreTheRowsTheyWere() {
        // The raw table is its own sampling -- `Outlines` reads it, unclamped -- so it is checked on its own
        // for the two point tests that were rewritten, rather than trusted to follow the clean one.
        for (BuiltIn each : builtIns()) {
            if (each.shape() != Shapes.PENTAGON && each.shape() != Shapes.GEAR) {
                continue;
            }
            Shape old = Shapes.unit(each.old());
            for (int size = 1; size <= LARGEST; size++) {
                for (int row = 0; row < size; row++) {
                    assertArrayEquals(old.spansOf(row, size), each.shape().spansOf(row, size),
                            each.name() + " raw at " + size + "px, row " + row);
                }
            }
        }
    }

    @Test
    @DisplayName("the rewritten point tests answer every point as they did, not only the sampler's")
    void thePointTestsAnswerAsTheyDid() {
        // The tables only ask about pixel centres and the bisection points between them. A point anywhere
        // else -- the turn's rays, a rotated sampler's grid -- is asked here, at random but seeded, and
        // out past the square as well, where the turn's fit measurement asks.
        Random random = new Random(0x0DDBA11L);
        Shapes.Unit pentagon = unitOf(Shapes.PENTAGON);
        Shapes.Unit gear = unitOf(Shapes.GEAR);
        for (int i = 0; i < 200_000; i++) {
            double u = random.nextDouble() * 1.6 - 0.8;
            double v = random.nextDouble() * 1.6 - 0.8;
            int size = 1 + random.nextInt(LARGEST * 2);
            assertEquals(pentagonInside(u, v, size), pentagon.isInside(u, v, size),
                    "the pentagon at (" + u + ", " + v + "), " + size + "px");
            assertEquals(gearInside(u, v, size), gear.isInside(u, v, size),
                    "the gear at (" + u + ", " + v + "), " + size + "px");
        }
    }

    @Test
    @DisplayName("a turned shape draws the table the old turn drew, at several angles and every size")
    void aTurnIsTheTurnItWas() {
        // The pentagon and the gear at three angles, because both their point tests and the turn's fit
        // lookup moved; the heart and the square at one, which holds the fit lookup on its own.
        double[] three = {20.0, 137.0, 211.0};
        double[] one = {41.0};
        for (BuiltIn each : builtIns()) {
            double[] angles;
            if (each.shape() == Shapes.PENTAGON || each.shape() == Shapes.GEAR) {
                angles = three;
            }
            else if (each.shape() == Shapes.HEART || each.shape() == Shapes.RECT) {
                angles = one;
            }
            else {
                continue;
            }
            for (double angle : angles) {
                Shape turned = Shapes.rotated(each.shape(), angle);
                Shape old = Shapes.unit(oldTurn(each.old(), angle));
                for (int size = 1; size <= LARGEST; size++) {
                    assertSameTable(old, turned, size, each.name() + " turned " + angle);
                }
            }
        }
    }

    @Test
    @DisplayName("a turn's remembered fit is right however the sizes alternate")
    void aTurnAnswersEverySizeInAnyOrder() {
        // The last fit is remembered beside the map, so the case to pin is the one that defeats it: every
        // sample at a different size from the one before, across more sizes than the map keeps, so the
        // remembered fit, the map and a fresh measurement are all exercised and all held to the old turn.
        int[] pool = {5, 12, 16, 24, 31, 32, 33, 48, 64, 97, 127, 128};
        Random random = new Random(0x7E57L);
        Shapes.Unit turned = unitOf(Shapes.rotated(Shapes.PENTAGON, 211.0));
        Shapes.Unit old = oldTurn(PointTestOracleTest::pentagonInside, 211.0);
        for (int i = 0; i < 1500; i++) {
            int size = pool[random.nextInt(pool.length)];
            double u = random.nextDouble() - 0.5;
            double v = random.nextDouble() - 0.5;
            assertEquals(old.isInside(u, v, size), turned.isInside(u, v, size),
                    "the turned pentagon at (" + u + ", " + v + "), " + size + "px, sample " + i);
            // And again at once, which is the remembered fit's own path.
            assertEquals(old.isInside(v, u, size), turned.isInside(v, u, size),
                    "the turned pentagon at (" + v + ", " + u + "), " + size + "px, sample " + i);
        }
    }
}
