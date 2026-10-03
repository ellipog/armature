package dev.ellipog.armature.client.ui.shape;

import dev.ellipog.armature.client.ui.kit.RoundedRect;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntBinaryOperator;

/**
 * The built-in shapes, and the ways to make one.
 *
 * <h2>Shapes are values, not enum members</h2>
 *
 * <p>{@link Shape} is an interface with one method, so a shape is an object a caller can hold, pass,
 * compare and build. That is what makes a radius a <b>parameter</b> rather than a property of a name:
 * {@code Shapes.rounded(8)} and {@code Shapes.rounded(16)} are two shapes, and neither needs a new enum
 * member or a new field in a data format.
 *
 * <p>The alternative — which this project had — is a closed enum of four, where the only way to add a
 * corner radius to {@code ROUNDED} is to add a field somewhere and then thread it through every caller.
 * A radius is not a different <i>kind</i> of shape; it is the same shape at a different size, and the
 * type now says so.
 *
 * <h2>Why the older shapes are preserved exactly</h2>
 *
 * <p>{@link #ROUNDED} uses {@link #roundedProportional} with a divisor of four — integer division,
 * {@code size / 4} — and not {@code (int) (size * 0.25)}. For most sizes the two agree, and for a size
 * where {@code size / 4} has a remainder of a half they do not: at 18 pixels integer division gives 4 and
 * the float form rounds to 5. That is one pixel of corner, which is invisible in a screenshot and a
 * change to every existing quest's appearance. The divisor form keeps the arithmetic in integers and the
 * output identical, which is the whole reason it exists rather than a fraction.
 *
 * <h2>{@link #HEXAGON} and {@link #TOME} are not roundings</h2>
 *
 * <p>Worth stating because both look like they could be written with {@link RoundedRect}: a hexagon's
 * edge is a <b>straight taper</b>, not an arc, and that is the entire visual difference between a hexagon
 * and a rounded rectangle at these sizes. A book's fore-edge is an arc, and its spine is not — the
 * asymmetry is the read, and a shape rounded equally on both sides says nothing about a book.
 *
 * <h2>Two ways to define a shape, and why both are here</h2>
 *
 * <p>Most of these are <b>closed forms</b>: a circle's half-width is the circle equation, a hexagon's is a
 * taper, and {@link #of} takes the two edges of each row. They are exact, they are three lines each, and
 * a reader can check them against a pencil.
 *
 * <p>{@link #GEAR} and {@link #HEART} are not, and forcing them into that form was a mistake worth
 * recording: the gear was a hub with eight trapezoids welded on and the heart was two discs and a
 * triangle, which read as a cog-shaped blob and a shield with a dip. A real gear's flank is an
 * <b>involute</b> and a real heart is an implicit <b>curve</b>, and neither can be written as two edges
 * per row without becoming something else. So {@link #sampled} takes a <b>point test</b> — is this pixel
 * inside? — and turns it into the same span table everything else consumes, by walking each row and
 * bisecting at the edges. The renderer, the hit test and the icon fit do not know the difference.
 *
 * <p>{@link #rotated} is the same idea composed with a turn: it samples the base shape through an
 * inverse-rotated point, so <b>every</b> shape here can be rotated, closed forms included, and none of
 * them needed to know about it.
 *
 * <p>And the sampling is why rows may hold more than one span: a heart's top rows are two lobes with a
 * notch between them, and a gear's teeth stand clear of its hub. The merging, clamping and ordering is
 * the {@link Shape#spans} default's job, so a sampled shape only has to answer the point test.
 */
public final class Shapes {

    private Shapes() {
    }

    /** A plain rectangle. The degenerate case, and the honest fallback when nothing else fits. */
    public static final Shape RECT = of((row, size) -> 0, (row, size) -> size);

    /**
     * A circle.
     *
     * <p>Measured to the pixel centre rather than the pixel edge, so an odd size comes out symmetric
     * instead of one pixel lopsided. {@code ShapeTest} asserts that symmetry for every size from 1 to
     * 80 rather than for the even ones.
     */
    public static final Shape CIRCLE = of(
            (row, size) -> {
                double centre = (size - 1) / 2.0;
                double radius = size / 2.0;
                double dy = row - centre;
                return (int) Math.round(centre - Math.sqrt(Math.max(0.0, radius * radius - dy * dy)));
            },
            (row, size) -> {
                double centre = (size - 1) / 2.0;
                double radius = size / 2.0;
                double dy = row - centre;
                return (int) Math.round(centre + Math.sqrt(Math.max(0.0, radius * radius - dy * dy))) + 1;
            });

    /**
     * A flat-topped hexagon: full width through the middle, tapering in a straight line to each edge.
     *
     * <p>The taper is what makes it a hexagon. An arc of the same depth would be a rounded rectangle —
     * and they are hard to tell apart in a small picture, which is why {@code ShapeTest} asserts that
     * the taper's first few steps are equal rather than merely that the shape narrows.
     */
    public static final Shape HEXAGON = of(
            (row, size) -> hexagonInset(row, size),
            (row, size) -> size - hexagonInset(row, size));

    /**
     * How far a hexagon's taper cuts in on one row.
     *
     * <p>A named method rather than calling {@code HEXAGON.spansOf} from its own second lambda, which
     * would work — the lambda is only evaluated after class initialisation finishes — and would be a
     * static initialiser referring to the field it is initialising. That compiles and reads as a trap,
     * and the arithmetic is one expression either way.
     */
    private static int hexagonInset(int row, int size) {
        int taper = Math.max(1, size / 4);
        int depth = Math.min(row, size - 1 - row);
        return depth >= taper ? 0 : (int) Math.round((taper - depth) * (size / 4.0) / taper);
    }

    /**
     * A book seen from the front: a straight spine on the left, a rounded fore-edge on the right.
     *
     * <p>The first implementation of this gave the <b>left</b> the larger radius, which produced a
     * quarter cut out of the top-left rather than a book — at 48 pixels the top row ran from x=24 to
     * x=40, a bar floating right of centre. Six pixels of asymmetry sounded plausible and looked like a
     * broken shape, which is the cost of writing geometry without drawing it.
     */
    public static final Shape TOME = of(
            (row, size) -> 0,
            (row, size) -> size - RoundedRect.cornerCut(row, size, Math.max(1, size / 3)));

    /**
     * A diamond: straight taper to a point at the top and the bottom, full width through the middle.
     *
     * <p>The taper is measured over the <b>depth</b> — the distance to the nearer horizontal edge — so
     * the shape is symmetric top to bottom by construction, and the tip is a pixel or two rather than a
     * rounded cap. {@code ShapeTest} asserts both.
     */
    public static final Shape DIAMOND = ofSpans((row, size) -> {
        double centre = (size - 1) / 2.0;
        double half = size / 2.0;
        double deepest = Math.max(1, (size - 1) / 2);
        double depth = Math.min(row, size - 1 - row);
        // Never narrower than half a pixel, for the same reason the pentagon is not: a diamond one
        // pixel tall is still a diamond, and a row that rounds to nothing is a hole in the outline.
        double halfWidth = Math.max(0.5, half * (depth + 0.5) / (deepest + 0.5));
        return new int[] {(int) Math.round(centre - halfWidth), (int) Math.round(centre + halfWidth)};
    });

    /**
     * An octagon: a square with straight 45° corners.
     *
     * <p>Distinct from {@link #ROUNDED} by more than a pixel at the sizes a node is drawn at — the cut
     * is a third of the size against the rounded rectangle's quarter, and the flank is a line rather
     * than an arc. {@code ShapeTest} asserts the difference, because two shapes an author cannot tell
     * apart are one shape with two names.
     */
    public static final Shape OCTAGON = ofSpans((row, size) -> {
        int cut = Math.max(1, size / 3);
        int depth = Math.min(row, size - 1 - row);
        int inset = Math.max(0, Math.min(cut - depth, (size - 1) / 2));
        return new int[] {inset, size - inset};
    });

    /**
     * A pentagon, point up, with a flat base.
     *
     * <p>Deliberately asymmetric top to bottom — a pentagon with a flat top would be a different shape
     * — and that is the one shape here whose widest row is not its middle: the shoulders are about a
     * third of the way down, and the sides then taper slightly to the base. Nothing in the geometry
     * depends on where the widest row is any more; see {@link Shape#maxInset}.
     */
    public static final Shape PENTAGON = ofSpans((row, size) -> {
        double centre = (size - 1) / 2.0;
        double half = size / 2.0;
        double shoulder = Math.max(1.0, size * 0.30);
        double baseHalf = Math.max(0.5, size * 0.31);
        double halfWidth;
        if (row < shoulder) {
            halfWidth = half * (row + 0.5) / shoulder;
        }
        else {
            double t = (row - shoulder) / Math.max(1.0, (size - 1) - shoulder);
            halfWidth = half - (half - baseHalf) * t;
        }
        // Never narrower than half a pixel: a pentagon one pixel tall is still a pentagon, and a row
        // that rounds to nothing is a hole in the outline rather than a small shape.
        halfWidth = Math.max(0.5, halfWidth);
        return new int[] {(int) Math.round(centre - halfWidth), (int) Math.round(centre + halfWidth)};
    });

    /**
     * A gear: a real involute spur gear.
     *
     * <h2>The arithmetic, and why it is worth this much of it</h2>
     *
     * <p>The proportions are the standard ones, from the module {@code m = size / (teeth + 2)} — chosen so
     * the <b>tip circle lands on the square's edge</b>, which is what makes a gear fill its node:
     *
     * <ul>
     *   <li>pitch radius {@code m·N/2}, base radius {@code r_p·cos 20°} (a 20° pressure angle, the
     *       standard one), root radius {@code r_p − 1.25m}, tip radius {@code r_p + m};</li>
     *   <li>the tooth's angular half-thickness at the pitch circle is {@code π/(2N)} — half the pitch is
     *       tooth and half is gap, which is what a gear is;</li>
     *   <li>and at any other radius it follows the <b>involute</b>: {@code ψ(r) = π/(2N) + inv(20°) −
     *       inv(α_r)} with {@code cos α_r = r_base/r} and {@code inv(a) = tan a − a}. That is the curve a
     *       string unwinding from the base circle traces, and it is the whole reason a gear's flank is
     *       <i>concave</i> near the root and straightens towards the tip. Below the base circle the flank
     *       is radial, as it is on a real one.</li>
     * </ul>
     *
     * <p>The tooth count falls with the size — ten at 48 pixels, six at 16 — because ten teeth on a
     * sixteen-pixel node is a pitch of under two pixels, and teeth that merge are not teeth. A pixel
     * artist makes the same choice for the same reason.
     *
     * <p>The silhouette is <b>not</b> monotone: a row through a tooth is wider than the gap row nearer the
     * middle, which is the case that made {@link Shape#maxInset} exact rather than a two-row shortcut.
     */
    public static final Shape GEAR = sampled(Shapes::gearInside);

    /**
     * A heart: the classic curve, {@code y = |x|^(2/3) ± √(1 − x²)}.
     *
     * <p>Written as the implicit form {@code (v − |u|^(2/3))² + u² ≤ 1}, which is the same curve and is
     * one comparison per point. Its notch, its lobes and its point are the curve's own — the previous
     * version assembled a heart out of two discs and a triangle and read as a shield with a dip.
     *
     * <p>The curve's own bounding box is two units wide and {@code 1.512} tall: the lobes peak about half
     * a unit <b>above</b> the notch, at {@code u ≈ ±0.62}. Fitting that box to the square is what makes
     * the heart fill its node, and it is why the widest row is near the top rather than the middle — which
     * is also true of a real heart.
     *
     * <p>Its top rows are two lobes with a notch between them, so this is still the shape that made rows
     * plural; the icon's fit lands in the body under the notch, which is the same rule as every other
     * shape: the icon goes where the material is.
     */
    public static final Shape HEART = sampled(Shapes::heartInside);

    /**
     * The default rounded corner: a quarter of the node's size, in integer arithmetic.
     *
     * <p>This is what {@code ROUNDED} has always been, and the reason it is expressed as a divisor rather
     * than as {@code 0.25} is in the class note.
     */
    public static final Shape ROUNDED = roundedProportional(4);

    /**
     * A rounded rectangle whose radius is a fixed number of pixels, however big the shape is.
     *
     * <p>A fixed radius is what a <i>panel border</i> wants: the corner should look the same whether the
     * panel is 120 pixels wide or 400. A shape that scales its radius is what a <i>node</i> wants, so both
     * forms exist and neither is the default for the other.
     *
     * @param radius a radius of zero or less is {@link #RECT}, which is a real answer rather than an
     *     error: a caller deriving a radius from a size that shrank to nothing wants a rectangle
     */
    public static Shape rounded(int radius) {
        if (radius <= 0) {
            return RECT;
        }
        return of((row, size) -> RoundedRect.cornerCut(row, size, radius),
                (row, size) -> size - RoundedRect.cornerCut(row, size, radius));
    }

    /**
     * A rounded rectangle whose radius is the size divided by {@code divisor}.
     *
     * <p>Integer division on purpose — see the class note for the one-pixel difference it preserves.
     *
     * @param divisor at least one; anything less is clamped, since a divisor of zero would make the
     *     radius the size itself and a negative one would make it negative
     */
    public static Shape roundedProportional(int divisor) {
        int safeDivisor = Math.max(1, divisor);
        return of(
                (row, size) -> {
                    int radius = Math.max(1, size / safeDivisor);
                    return RoundedRect.cornerCut(row, size, radius);
                },
                (row, size) -> {
                    int radius = Math.max(1, size / safeDivisor);
                    return size - RoundedRect.cornerCut(row, size, radius);
                });
    }

    /**
     * Every built-in, by the name a data file would use.
     *
     * <p>The names are the lowercase enum names this project already writes in quest files, so a file
     * that says {@code "circle"} keeps working and a theme (R6) can name a shape in its own JSON. The
     * lookup is case-insensitive because a hand-written file's capitalisation is not a thing to be strict
     * about, and it <b>falls back rather than throwing</b> — a payload from a server running a newer
     * version can name a shape this client has never heard of, and drawing the fallback is obviously
     * better than a screen that throws while a player is standing in front of it.
     */
    public static Shape byName(String name, Shape fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "rounded" -> ROUNDED;
            case "circle" -> CIRCLE;
            case "hexagon" -> HEXAGON;
            case "tome" -> TOME;
            case "diamond" -> DIAMOND;
            case "octagon" -> OCTAGON;
            case "pentagon" -> PENTAGON;
            case "gear" -> GEAR;
            case "heart" -> HEART;
            case "rect", "rectangle", "square" -> RECT;
            default -> fallback;
        };
    }

    // ------------------------------------------------------------------
    // The factories
    // ------------------------------------------------------------------

    /**
     * Builds a single-span shape from the two edges of its span.
     *
     * <p>The plainest way to make a shape, and the one most of the built-ins use: two numbers per row,
     * and the interface clamps them. A row that computes to less than a pixel is widened to one by
     * {@link Shape#spans}, which is what makes a twelve-pixel circle a circle rather than a circle with
     * a notch in its cap.
     *
     * @param startOf the left edge, in local coordinates; may be out of range
     * @param endOf the right edge, <b>exclusive</b>; may be out of range
     */
    public static Shape of(IntBinaryOperator startOf, IntBinaryOperator endOf) {
        Objects.requireNonNull(startOf, "startOf");
        Objects.requireNonNull(endOf, "endOf");
        return ofSpans((row, size) -> new int[] {
                startOf.applyAsInt(row, size), endOf.applyAsInt(row, size)});
    }

    /**
     * Builds a shape from a whole row's spans, for the shapes that need more than one interval.
     *
     * <p>Implementations return their honest arithmetic — possibly out of range, possibly several
     * overlapping pieces of the same material — and {@link Shape#spans} clamps, drops, orders, merges
     * and widens it. Parts are how the union-built shapes are written: a disc, a polygon, a wedge, and
     * whatever any of them covers.
     */
    public static Shape ofSpans(SpansOf spansOf) {
        Objects.requireNonNull(spansOf, "spansOf");
        return new Shape() {
            @Override
            public int[] spansOf(int row, int size) {
                return spansOf.spansOf(row, size);
            }

            @Override
            public String toString() {
                return "Shape";
            }
        };
    }

    /** A shape's own per-row arithmetic, before the interface clamps and merges it. */
    @FunctionalInterface
    public interface SpansOf {
        /** Flattened {@code {from, to, …}} pairs, or null. */
        int[] spansOf(int row, int size);
    }

    // ------------------------------------------------------------------
    // The parts a union is made of
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // The shapes that are curves rather than closed forms
    // ------------------------------------------------------------------

    /** The largest tooth count a gear is drawn with, and the smallest. See {@link #GEAR}. */
    private static final int GEAR_TEETH_MAX = 10;
    private static final int GEAR_TEETH_MIN = 6;

    /** A 20-degree pressure angle: the standard, and the one that makes a flank look like a flank. */
    private static final double GEAR_PRESSURE_ANGLE = Math.toRadians(20.0);

    /** The root clearance, in modules. Real gears use 1.25 so a tip does not bottom out in a root. */
    private static final double GEAR_ROOT_CLEARANCE = 1.25;

    /**
     * The top of the heart curve, in its own units.
     *
     * <p>The maximum of {@code |u|^(2/3) + √(1 − u²)}: setting the derivative to zero gives
     * {@code 9t⁴ + 4t³ − 4 = 0} for {@code t = u^(2/3)}, whose root {@code t ≈ 0.7255} puts the peak at
     * {@code u ≈ 0.618} and the height at {@code 1.512}. Written as a constant with its derivation rather
     * than computed per sample, because it is a fact about the curve and not about the pixel.
     */
    private static final double HEART_TOP = 1.512;

    /** The point of the heart, at the curve's own bottom. */
    private static final double HEART_BOTTOM = -1.0;

    /** Whether a pixel is inside the gear. See {@link #GEAR} for the geometry. */
    private static boolean gearInside(double x, double y, int size) {
        int teeth = gearTeeth(size);
        double module = size / (double) (teeth + 2);
        double pitch = module * teeth / 2.0;
        double base = pitch * Math.cos(GEAR_PRESSURE_ANGLE);
        double root = pitch - GEAR_ROOT_CLEARANCE * module;
        double tip = pitch + module;

        // size/2, not (size-1)/2: this is asked about pixel *centres*, where the closed forms are
        // asked about row *indices*. The two describe the same centre -- row r is the centre of the
        // pixel r + 0.5 -- and at size 1 the difference is the whole node.
        double centre = size / 2.0;
        double dx = x - centre;
        double dy = y - centre;
        double radius = Math.hypot(dx, dy);
        if (radius > tip) {
            return false;
        }
        if (radius <= root) {
            return true;
        }

        double atPitch = Math.PI / (2.0 * teeth) + involute(GEAR_PRESSURE_ANGLE);
        double half = radius <= base
                ? atPitch
                : atPitch - involute(Math.acos(Math.min(1.0, base / radius)));

        // Folded into one tooth's pitch, measured from the nearest tooth centreline. The quarter turn
        // puts a tooth at the top of the node rather than a gap, which is the read everyone expects of
        // a gear -- and `round` rather than a floor-and-half-step, because the distance to the nearest
        // centreline is what the tooth's half-width is compared against.
        double pitchAngle = 2.0 * Math.PI / teeth;
        double angle = Math.atan2(dy, dx) + Math.PI / 2.0;
        angle -= pitchAngle * Math.round(angle / pitchAngle);
        return Math.abs(angle) <= half;
    }

    /** The involute function, {@code tan(a) − a}: the angle a string has unwound by. */
    private static double involute(double radians) {
        return Math.tan(radians) - radians;
    }

    /** Fewer teeth as the node shrinks, so the teeth stay teeth. See {@link #GEAR}. */
    private static int gearTeeth(int size) {
        return Math.max(GEAR_TEETH_MIN, Math.min(GEAR_TEETH_MAX, (int) Math.round(size / 4.5)));
    }

    /** Whether a pixel is inside the heart. See {@link #HEART} for the curve. */
    private static boolean heartInside(double x, double y, int size) {
        double u = (x / size) * 2.0 - 1.0;
        // Screen y grows downward: the top of the square is the lobes' peak, the bottom is the point.
        double v = HEART_TOP - (y / size) * (HEART_TOP - HEART_BOTTOM);
        double dy = v - Math.pow(Math.abs(u), 2.0 / 3.0);
        return u * u + dy * dy <= 1.0;
    }

    // ------------------------------------------------------------------
    // Sampling, and turning what is sampled
    // ------------------------------------------------------------------

    /**
     * A shape defined by a point test, sampled into spans.
     *
     * <p>The third way to make a shape, beside {@link #of} and {@link #ofSpans}, and the one for curves
     * that have no closed form per row: the caller says whether a pixel is inside, and this walks each
     * row, collects the runs of inside pixels into intervals and <b>bisects each edge</b> for a boundary
     * that lands where the curve is rather than where the pixel grid is.
     *
     * <p>A table is built once per size and remembered, because sampling is O(size²) and a node is drawn
     * at the same size for as long as the zoom holds still. The memory is bounded — a handful of sizes —
     * and the class is not thread-safe by design: shapes are drawn on the client's render thread, and a
     * lock on the frame path would cost more than the table.
     */
    public static Shape sampled(Inside inside) {
        Objects.requireNonNull(inside, "inside");
        return new Sampled(inside);
    }

    /**
     * A shape turned about its own centre.
     *
     * <p>Sampling the base through an inverse-rotated point, so <b>every</b> shape can be rotated and none
     * of them has to know: a circle is unchanged by any angle, a square at 90 degrees is the same square,
     * and a gear's teeth move round. Zero and 360 degrees return the base shape itself rather than a copy,
     * so the common case costs nothing.
     *
     * @param degrees clockwise on screen, where y grows downward; any value, wrapped into 0..360
     */
    public static Shape rotated(Shape base, double degrees) {
        Objects.requireNonNull(base, "base");
        double wrapped = degrees % 360.0;
        if (wrapped == 0.0) {
            return base;
        }
        double radians = Math.toRadians(wrapped);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return sampled((x, y, size) -> {
            double centre = size / 2.0;
            double dx = x - centre;
            double dy = y - centre;
            // `containsLocal` takes row-index coordinates, which is where the sample already is: it is
            // asked about pixel centres, and a centre is half a pixel into its own row.
            return base.containsLocal(centre + dx * cos + dy * sin,
                    centre - dx * sin + dy * cos, size);
        });
    }

    /** Whether a point is inside a shape being sampled. Coordinates are local pixels, {@code 0..size}. */
    @FunctionalInterface
    public interface Inside {
        boolean isInside(double x, double y, int size);
    }

    /** How many sizes a sampled shape remembers before it starts again. See {@link #sampled}. */
    private static final int SAMPLED_SIZES_REMEMBERED = 8;

    /** How many bisections an edge gets: six is under a hundredth of a pixel. */
    private static final int EDGE_BISECTIONS = 6;

    /** A shape built from a point test; see {@link #sampled}. */
    private static final class Sampled implements Shape {

        private final Inside inside;
        private final java.util.Map<Integer, int[][]> tables = new java.util.LinkedHashMap<>(4);

        Sampled(Inside inside) {
            this.inside = inside;
        }

        @Override
        public int[] spansOf(int row, int size) {
            if (size <= 0 || row < 0 || row >= size) {
                return null;
            }
            int[][] table = tables.get(size);
            if (table == null) {
                table = sample(size);
                if (tables.size() >= SAMPLED_SIZES_REMEMBERED) {
                    tables.clear();
                }
                tables.put(size, table);
            }
            return table[row];
        }

        /** The whole span table for one size: one entry per row, possibly empty or null. */
        private int[][] sample(int size) {
            int[][] table = new int[size][];
            for (int row = 0; row < size; row++) {
                table[row] = sampleRow(row, size);
            }
            return table;
        }

        private int[] sampleRow(int row, int size) {
            double y = row + 0.5;
            int[] runs = new int[size * 2];
            int found = 0;
            int start = -1;
            for (int col = 0; col < size; col++) {
                boolean here = inside.isInside(col + 0.5, y, size);
                if (here && start < 0) {
                    start = col;
                }
                else if (!here && start >= 0) {
                    found = addRun(runs, found, start, col, y, size);
                    start = -1;
                }
            }
            if (start >= 0) {
                found = addRun(runs, found, start, size, y, size);
            }
            return found == 0 ? null : Arrays.copyOf(runs, found * 2);
        }

        /** One run, with both edges bisected to where the curve actually crosses the row. */
        private int addRun(int[] runs, int found, int start, int end, double y, int size) {
            int from = edge(start, start - 0.5, y, size);
            int to = edge(end - 1, end + 0.5, y, size);
            runs[found * 2] = Math.max(0, Math.min(size - 1, from));
            runs[found * 2 + 1] = Math.max(runs[found * 2] + 1, Math.min(size, to));
            return found + 1;
        }

        /**
         * The pixel edge where the shape crosses, by bisection between an inside sample and an outside
         * one.
         *
         * <p>The answer is {@code ceil(boundary − 0.5)} for <b>both</b> edges, and that is worth stating
         * because it looks like it should differ: a pixel is inside when its <i>centre</i> is inside, so
         * the first inside pixel is the first whose centre clears the boundary — {@code ceil(b − 0.5)} —
         * and the exclusive end of a run is that same number for the boundary on the other side. One
         * formula, and a run that is empty because the boundary fell inside a single pixel is widened by
         * the sanitiser rather than lost.
         *
         * @param sample the pixel whose centre is inside
         * @param beyond half a pixel past it, which is outside
         */
        private int edge(int sample, double beyond, double y, int size) {
            double within = sample + 0.5;
            for (int i = 0; i < EDGE_BISECTIONS; i++) {
                double mid = (within + beyond) / 2.0;
                if (inside.isInside(mid, y, size)) {
                    within = mid;
                }
                else {
                    beyond = mid;
                }
            }
            return (int) Math.ceil((within + beyond) / 2.0 - 0.5);
        }

        @Override
        public String toString() {
            return "Sampled shape";
        }
    }

    @Override
    public String toString() {
        return "Shapes";
    }
}
