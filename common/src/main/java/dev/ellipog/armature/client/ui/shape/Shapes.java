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
 * <h2>One definition each, in the unit square</h2>
 *
 * <p>Every shape here is a point test in a normalized square: {@link Unit} answers whether a point
 * {@code (u, v)}, with both coordinates in {@code [-0.5, 0.5]}, is inside. That single definition is
 * sampled into the span table the renderer, the hit test and the icon fit all read, so the drawn outline
 * and the clickable outline cannot disagree — and it is what makes a turn exact, because {@link #rotated}
 * turns the definition rather than sampling a raster that was itself sampled.
 *
 * <p>{@link #of}, {@link #ofSpans} and {@link #sampled} remain for a caller whose shape is a function of
 * a row or of a pixel. Those are the only shapes with no unit definition, and the only ones whose turn
 * falls back to re-sampling a raster.
 *
 * <h2>Two conventions for one half pixel, and why both survive</h2>
 *
 * <p>The sampler asks about pixel <i>centres</i>. A shape whose arithmetic has always been written on
 * row indices — the rounded rectangle, the diamond, the octagon, the shield, the tome — is asked one
 * half-pixel higher through {@link #gridV}, because that is the convention its pixels were drawn with,
 * and moving it would move every existing node's outline by a pixel or three. The circle and the heart,
 * which were always written on centres, are asked directly. The difference is invisible in a picture and
 * load-bearing in a diff, which is why {@code ShapeTest} pins the shapes that predate this file to their
 * old spans.
 *
 * <h2>Why the older shapes are preserved exactly</h2>
 *
 * <p>{@link #ROUNDED} uses {@link #roundedProportional} with a divisor of four — integer division,
 * {@code size / 4} — and not {@code (int) (size * 0.25)}. For most sizes the two agree, and for a size
 * where {@code size / 4} has a remainder of a half they do not: at 18 pixels integer division gives 4 and
 * the float form rounds to 5. That is one pixel of corner, which is invisible in a screenshot and a
 * change to every existing entry's appearance. The divisor form keeps the arithmetic in integers and the
 * output identical, which is the whole reason it exists rather than a fraction.
 *
 * <h2>{@link #HEXAGON} and {@link #TOME} are not roundings</h2>
 *
 * <p>Worth stating because both look like they could be written with {@link RoundedRect}: a hexagon's
 * edge is a <b>straight taper</b>, not an arc, and that is the entire visual difference between a hexagon
 * and a rounded rectangle at these sizes. A book's fore-edge is an arc, and its spine is not — the
 * asymmetry is the read, and a shape rounded equally on both sides says nothing about a book.
 *
 * <h2>Two ways to define a point test, and why the curves are not closed forms</h2>
 *
 * <p>Most of these are <b>closed forms</b>: a circle is the circle equation, a hexagon is two linear
 * inequalities, and a reader can check them against a pencil. {@link #GEAR} and {@link #HEART} are not,
 * and forcing them into that form was a mistake worth recording: the gear was a hub with eight
 * trapezoids welded on and the heart was two discs and a triangle, which read as a cog-shaped blob and a
 * shield with a dip. So both are radii and implicit curves, which is one comparison per point either way.
 *
 * <p>And the sampling is why rows may hold more than one span: a heart's top rows are two lobes with a
 * notch between them, and a gear's teeth stand clear of its hub. The merging, clamping and ordering is
 * the {@link Shape#spans} default's job, so a shape only has to answer the point test.
 */
public final class Shapes {

    private Shapes() {
    }

    // ------------------------------------------------------------------
    // The numbers the geometry is made of, before the shapes that read them
    // ------------------------------------------------------------------

    /** The half-height of a regular flat-topped hexagon of half-width one half: {@code √3/4}. */
    private static final double HEXAGON_HALF_HEIGHT = Math.sqrt(3.0) / 4.0;

    /** The hexagon flank's drop per unit across: {@code 1/√3}. */
    private static final double HEXAGON_TAPER = 1.0 / Math.sqrt(3.0);

    /** Where a regular octagon's chamfer starts: {@code |u| + |v| ≤ 1/√2} inside the square. */
    private static final double OCTAGON_DIAGONAL = 1.0 / Math.sqrt(2.0);

    /** Where a shield's vertical flanks end, as a fraction of the height below its middle. */
    private static final double PENTAGON_SHOULDER = 0.0;

    /** How far above its box's middle a shield's item sits, as a fraction of the size. */
    private static final double PENTAGON_ANCHOR = 0.03;

    /** A tome's fore-edge radius, as a divisor of the size: the same third it has always been. */
    private static final int TOME_FORE_EDGE_DIVISOR = 3;

    /** How deep a tome's spine notch is, as a divisor of the size. */
    private static final int TOME_NOTCH_DEPTH_DIVISOR = 16;

    /** How long a tome's spine notch is, as a divisor of the size. */
    private static final int TOME_NOTCH_LENGTH_DIVISOR = 8;

    /** How far up a tome's item sits, as a fraction of the size; small, because the notches are small. */
    private static final double TOME_ANCHOR = 0.02;

    /** How far up a heart's item sits, as a fraction of the size; under the lobes, where the mass is. */
    private static final double HEART_ANCHOR = 0.03;

    /** The largest tooth count a gear is drawn with; {@link #GEAR} explains the pair. */
    private static final int GEAR_TEETH = 8;

    /** The tooth count below {@link #GEAR_SMALL_SIZE} pixels, because eight would be too many to read. */
    private static final int GEAR_TEETH_SMALL = 6;

    /** The size below which a gear drops to {@link #GEAR_TEETH_SMALL} teeth. */
    private static final int GEAR_SMALL_SIZE = 32;

    /** How far the solid hub reaches from the centre, as a fraction of the size. */
    private static final double GEAR_HUB = 0.34;

    /** The share of one pitch a tooth occupies at the root circle: a wide-rooted trapezoid. */
    private static final double GEAR_ROOT_SHARE = 0.62;

    /** The same share at the tip, where the tooth is narrower. */
    private static final double GEAR_TIP_SHARE = 0.30;

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

    /**
     * The middle of the heart curve's box, in the curve's own units.
     *
     * <p>The curve is not centred on its own origin — it peaks at {@link #HEART_TOP} and points at
     * {@link #HEART_BOTTOM}, and the midpoint between them is where the <i>box's</i> centre is. Fitting
     * the box to the square means mapping that midpoint to the square's centre; mapping the origin
     * instead puts the box's centre a tenth of the node above the middle and clips the lobes off at the
     * top edge, which is what the first version of this did.
     */
    private static final double HEART_MIDDLE = (HEART_TOP + HEART_BOTTOM) / 2.0;

    /** The heart curve's full height, which is also the one scale its box is fitted with. */
    private static final double HEART_SPAN = HEART_TOP - HEART_BOTTOM;

    /**
     * How far outside a boundary a sample may still count as on it.
     *
     * <p>Floating point cannot represent most of these boundaries, so a point that is exactly on the
     * outline — and the outermost row of every symmetric shape has one, because its grid coordinate is
     * exactly half the square — can compute a hair outside it. Without the tolerance the row comes back
     * empty and the shape loses its cap: a rounded rectangle's top row at 14 pixels, a diamond's point,
     * a circle's topmost pixel. A billionth of the unit is a millionth of a pixel at the largest node a
     * file can ask for, so nothing else moves.
     */
    private static final double ON_BOUNDARY = 1.0E-9;

    /** The smallest node whose rows are sampled on their own grid line; see {@link #gridV}. */
    private static final int GRID_MIN_SIZE = 4;

    // ------------------------------------------------------------------
    // The built-ins
    // ------------------------------------------------------------------

    /** A plain rectangle. The degenerate case, and the honest fallback when nothing else fits. */
    public static final Shape RECT = unit((u, v, size) ->
            Math.abs(u) <= 0.5 + ON_BOUNDARY && Math.abs(v) <= 0.5 + ON_BOUNDARY);

    /**
     * A circle: the unit circle in the node's square, so it touches all four edges.
     *
     * <p>Its centre is the square's centre, and the sampler asks about pixel centres — so an odd size
     * comes out symmetric instead of one pixel lopsided. {@code ShapeTest} asserts that symmetry for
     * every size from 1 to 80 rather than for the even ones.
     */
    public static final Shape CIRCLE = unit((u, v, size) -> u * u + v * v <= 0.25 + ON_BOUNDARY);

    /**
     * A true flat-topped hexagon: full width at the middle, horizontal edges top and bottom, straight
     * flanks between them.
     *
     * <h2>It used to be eight-sided, and the name was doing the lying</h2>
     *
     * <p>The first version tapered only a quarter of the way in and then ran its sides straight up to
     * the top edge: top edge, two taper steps, two vertical flanks, two more tapers, bottom edge. At 48
     * pixels rows 12 to 35 were the full width of the node, which is an octagon wearing a hexagon's
     * name — and a node is exactly the size at which that reads as a chamfered square. A hexagon is six
     * sides: the taper has to reach the middle.
     *
     * <p>Flat-topped rather than pointy-topped because then its width is the node's width, which is the
     * horizontal weight a 16-pixel item sprite wants.
     *
     * <p>Regular, so its height is {@code √3/2} of its width and the node keeps a little air above and
     * below. That air is the price of not stretching it, and cheaper than a hexagon whose angles are
     * wrong — a stretched hexagon is a shape that no longer tiles, and its turn is no longer its own.
     */
    public static final Shape HEXAGON = unit((u, v, size) -> {
        double y = gridV(v, size);
        return Math.abs(y) <= HEXAGON_HALF_HEIGHT + ON_BOUNDARY
                && Math.abs(u) <= 0.5 - Math.abs(y) * HEXAGON_TAPER + ON_BOUNDARY;
    });

    /**
     * A book seen from the front: a straight spine with a notch at its head and tail, and a rounded
     * fore-edge.
     *
     * <h2>Why the notches</h2>
     *
     * <p>The shape was a flat left edge and a rounded right one, and at node size it read as a D-pad
     * button as often as a book: one straight side and one round side is a shape, not a codex. The
     * notches are the cue that fixes it — a step in the spine just below the top and just above the
     * bottom, where a binding's hinge sits — and they say "book" with no colour, no line and no second
     * part. A bookmark ribbon would say it too and cannot be drawn here: a silhouette has no inside to
     * print one on.
     *
     * <p>The first implementation of this gave the <b>left</b> the larger radius, which produced a
     * quarter cut out of the top-left rather than a book — at 48 pixels the top row ran from x=24 to
     * x=40, a bar floating right of centre. Six pixels of asymmetry sounded plausible and looked like a
     * broken shape, which is the cost of writing geometry without drawing it.
     */
    public static final Shape TOME = anchored(Shapes::tomeInside, 0.0, -TOME_ANCHOR);

    /**
     * A diamond: a square turned 45 degrees and fitted to the node, so its points reach the middle of
     * each edge.
     *
     * <p>Symmetric top to bottom by construction, and the tip is a pixel rather than a rounded cap: a
     * row at the very tip is widened rather than dropped, because a diamond one pixel tall is still a
     * diamond and a row that vanishes is a hole in the outline. {@code ShapeTest} asserts both.
     */
    public static final Shape DIAMOND = unit((u, v, size) -> {
        double y = gridV(v, size);
        return Math.abs(u) + Math.abs(y) <= 0.5 + ON_BOUNDARY || Math.abs(u) <= 0.5 / size + ON_BOUNDARY;
    });

    /**
     * A regular octagon: a square with equal straight chamfers on all four corners.
     *
     * <p>The chamfer meets the edges where a regular octagon's sides are all the same length — a cut of
     * {@code 1/(2+√2)} of the size, about 29 percent, where the old version cut a third. Distinct from
     * {@link #ROUNDED} by more than a pixel at the sizes a node is drawn at: the cut is deeper than the
     * rounded rectangle's quarter and the flank is a line rather than an arc. {@code ShapeTest} asserts
     * the difference, because two shapes an author cannot tell apart are one shape with two names.
     */
    public static final Shape OCTAGON = unit((u, v, size) -> {
        double y = gridV(v, size);
        return Math.abs(u) <= 0.5 && Math.abs(y) <= 0.5
                && Math.abs(u) + Math.abs(y) <= OCTAGON_DIAGONAL + ON_BOUNDARY;
    });

    /**
     * A shield: a full-width flat top, vertical flanks, and a taper to a point at the bottom.
     *
     * <h2>Why the old pentagon was replaced rather than kept</h2>
     *
     * <p>It was a point-up pentagon with a flat base — the "house" orientation, and the reason the shape
     * read as an irregular house: a point at the top of a node is where an item's own head wants to be,
     * so the silhouette and the sprite fought. The shield puts the point where an item has nothing (the
     * bottom) and the flat edge where a sprite's top sits, which is also why every pennant, badge and
     * escutcheon is drawn that way.
     *
     * <p>Its widest row is its top, so its mass is not its middle: the item's anchor sits
     * {@code PENTAGON_ANCHOR} of the size above the box's centre, under the flat edge. See
     * {@link Shape#iconAnchor}.
     */
    public static final Shape PENTAGON = anchored(Shapes::pentagonInside, 0.0, -PENTAGON_ANCHOR);

    /**
     * A gear: eight trapezoidal teeth around a large solid hub, six teeth when the node is small.
     *
     * <h2>What changed, and why the involute went</h2>
     *
     * <p>The first version was a real involute spur gear — pressure angle, root clearance, the flank a
     * string unwinding from the base circle traces — and the arithmetic was correct and the picture was
     * not: at 48 pixels it drew ten thin curved flanks whose teeth merged into a daisy at exactly the
     * size a node is drawn at, around a hub too small for an item to sit in. A gear in a node is read in
     * one glance at a dozen pixels, and what reads is the count, the gaps and the hub.
     *
     * <p>So: {@value #GEAR_TEETH} teeth — {@value #GEAR_TEETH_SMALL} below {@value #GEAR_SMALL_SIZE}
     * pixels, because eight teeth on a sixteen-pixel node is a pitch of two pixels and teeth that touch
     * are not teeth — with straight flanks that are wide at the root and narrower at the tip, which is
     * what makes the silhouette read as cut rather than grown. The hub reaches {@value #GEAR_HUB} of the
     * size from the centre, so a centred item has material under it and an edge to be seen against.
     */
    public static final Shape GEAR = unit(Shapes::gearInside);

    /**
     * A heart: the classic curve, {@code y = |x|^(2/3) ± √(1 − x²)}.
     *
     * <p>Written as the implicit form {@code (v − |u|^(2/3))² + u² ≤ 1}, which is the same curve and is
     * one comparison per point. Its notch, its lobes and its point are the curve's own — the version
     * before the curve assembled a heart out of two discs and a triangle and read as a shield with a dip.
     *
     * <h2>Fitted whole, not stretched to fill</h2>
     *
     * <p>The curve's own bounding box is two units wide and {@code 1.512} tall, and the version that
     * shipped stretched that box over the node in both directions — about 20 percent wider, relative to
     * its height, than the real curve. It filled the node and it was not a heart, and the moment the
     * node was turned the stretch turned with it, which is the shear a turned heart shows. It is now
     * fitted with one scale for both axes: about four fifths of the node wide, with the node's air at
     * its sides. That is the honest price of a heart that stays a heart when it turns.
     *
     * <p>Its top rows are two lobes with a notch between them, so this is still the shape that made rows
     * plural; the item's anchor sits above the box's middle, under the lobes, where the mass is. See
     * {@link Shape#iconAnchor}.
     */
    public static final Shape HEART = anchored(Shapes::heartInside, 0.0, -HEART_ANCHOR);

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
        return unit((u, v, size) ->
                roundedInside(u, v, size, Math.min(0.5, radius / (double) size)));
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
        return unit((u, v, size) -> roundedInside(u, v, size,
                Math.min(0.5, Math.max(1, size / safeDivisor) / (double) size)));
    }

    /**
     * Every built-in, by the name a data file would use.
     *
     * <p>The names are the lowercase enum names this project already writes in data files, so a file
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
     * <p>The plainest way to make a shape whose arithmetic is a row: two numbers per row, and the
     * interface clamps them. A shape built this way has no unit definition and so cannot be turned
     * exactly — see {@link #rotated} — which is why the built-ins use {@link #unit}.
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
     * Builds a shape from a whole row's spans, for the shapes that need more than one interval per row.
     *
     * <p>Implementations return their honest arithmetic — possibly out of range, possibly several
     * overlapping pieces of the same material — and {@link Shape#spans} clamps, drops, orders, merges
     * and widens it. Parts are how a union-built shape is written: a disc, a polygon, a wedge, and
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

    /**
     * A shape defined by a point test in the unit square — the way every built-in here is written.
     *
     * <p>Coordinates are the node's width and height as one unit, so the same arithmetic draws at 12
     * pixels and at 512, and {@link #rotated} can turn it exactly because the definition is continuous
     * rather than a raster. {@code size} is passed because a few shapes are proportional to the pixel
     * after all: a rounded corner's integer radius, a gear's tooth count, a tome's spine notch.
     *
     * <p>An implementation must answer false outside the square. The sampler never asks out there, but
     * the turn's fit measurement does, and a test that said true at any distance would make "how far
     * does this shape reach" meaningless.
     */
    @FunctionalInterface
    public interface Unit {
        /** Whether the unit point {@code (u, v)} is inside a shape drawn at {@code size}. */
        boolean isInside(double u, double v, int size);
    }

    /**
     * Builds a shape from a point test in the unit square.
     *
     * <p>The recommended way to write one, and the only way to get an exact turn: the definition is
     * continuous, so rotating it costs no fidelity, where rotating a row's arithmetic can only re-step
     * an edge that was already stepped.
     */
    public static Shape unit(Unit unit) {
        Objects.requireNonNull(unit, "unit");
        return new UnitShape(unit, 0, 0);
    }

    /**
     * A unit shape whose item sits off the middle of its box; see {@link Shape#iconAnchor}.
     *
     * @param anchorY negative is up, because screen y grows downwards
     */
    private static Shape anchored(Unit unit, double anchorX, double anchorY) {
        return new UnitShape(unit, anchorX, anchorY);
    }

    // ------------------------------------------------------------------
    // Geometry, in the unit square
    // ------------------------------------------------------------------

    /**
     * A pixel centre's unit coordinate, moved to the pixel's own grid line — the half pixel a shape
     * written on row indices is drawn with.
     *
     * <p>The sampler asks about centres, and a shape like the rounded rectangle has always been written
     * with the row's index as its vertical coordinate: its top row's cut is the arc at the top edge, not
     * at the centre of the top pixel, and its bottom row's cut is the arc at the bottom edge. So the
     * shift goes half a pixel toward the <b>nearer</b> edge, which keeps the shape symmetric about the
     * middle of the square — a one-sided shift would have put the top row's cut on the bottom row and
     * left the shape asymmetrically taller at one end. See the class note for which shapes are asked
     * this way.
     */
    private static double gridV(double v, int size) {
        if (size < GRID_MIN_SIZE) {
            // Below four pixels a row's own edge is a quarter of the node away and "the nearer edge"
            // stops meaning anything — and there is nothing to preserve down here either, because the
            // old arithmetic's floors turned every shape into a square at these sizes. The pixel centre
            // is the only honest sample.
            return v;
        }
        double shift = 0.5 / size;
        return v < 0
                ? Math.max(-0.5, v - shift)
                : Math.min(0.5, v + shift);
    }

    /** Whether a pixel is inside a rounded rectangle. See {@link #rounded} and {@link #ROUNDED}. */
    private static boolean roundedInside(double u, double v, int size, double radius) {
        double y = gridV(v, size);
        double qx = Math.max(0.0, Math.abs(u) - (0.5 - radius));
        double qy = Math.max(0.0, Math.abs(y) - (0.5 - radius));
        return qx * qx + qy * qy <= radius * radius + ON_BOUNDARY;
    }

    /** Whether a pixel is inside a shield. See {@link #PENTAGON}. */
    private static boolean pentagonInside(double u, double v, int size) {
        double y = gridV(v, size);
        if (Math.abs(y) > 0.5) {
            return false;
        }
        // Vertical flanks down to the shoulder, then a straight taper to the point at the bottom.
        double halfWidth = y <= PENTAGON_SHOULDER
                ? 0.5
                : 0.5 * (0.5 - y) / (0.5 - PENTAGON_SHOULDER);
        // Never narrower than half a pixel: a shield one pixel tall is still a shield, and a row that
        // rounds to nothing is a hole in the outline rather than a small shape.
        halfWidth = Math.max(0.5 / size, halfWidth);
        return Math.abs(u) <= halfWidth + ON_BOUNDARY;
    }

    /**
     * Whether a pixel is inside a tome. See {@link #TOME}.
     *
     * <p>The right side is a rounded rectangle's fore-edge, the same third-of-the-size arc it has always
     * been, written as the rounded-box distance the rounded rectangle itself uses rather than as a row's
     * cut. That form is the one that survives a radius larger than half the size, which the fraction
     * reaches at the smallest nodes: the cut form divides by a corner that has gone negative there and
     * collapses the shape to nothing. The spine is straight except for the notch at each end.
     */
    private static boolean tomeInside(double u, double v, int size) {
        double y = gridV(v, size);
        if (Math.abs(y) > 0.5) {
            return false;
        }
        // Never past half the node: a larger radius is a semicircle's worth of curve on a shape with no
        // room for one, and at the smallest sizes it would empty the tome completely.
        double radius = Math.min(0.5, Math.max(1, size / TOME_FORE_EDGE_DIVISOR) / (double) size);
        double corner = 0.5 - radius;
        double qx = Math.max(0.0, u - corner);
        double qy = Math.max(0.0, Math.abs(y) - corner);
        if (qx * qx + qy * qy > radius * radius + ON_BOUNDARY) {
            return false;
        }
        // A notch never takes more than a third of the spine: at a handful of pixels the two ends
        // would meet in the middle and leave a book with no binding at all.
        double depth = Math.min(1.0 / 3.0, Math.max(1, size / TOME_NOTCH_DEPTH_DIVISOR) / (double) size);
        double length = Math.max(2, size / TOME_NOTCH_LENGTH_DIVISOR) / (double) size;
        double left = -0.5 + (Math.abs(y) >= 0.5 - length ? depth : 0.0);
        return u >= left - ON_BOUNDARY;
    }

    /**
     * Whether a pixel is inside the gear. See {@link #GEAR} for the geometry.
     *
     * <p>Radial: the hub is solid, the tips are on the node's edge, and between them the material is the
     * teeth — a tooth's half-angle shrinking from {@value #GEAR_ROOT_SHARE} of the pitch at the root to
     * {@value #GEAR_TIP_SHARE} at the tip, which is a trapezoid with its wide side in. The quarter turn
     * puts a tooth at the top of the node rather than a gap, which is the read everyone expects of a
     * gear.
     */
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

    /**
     * Whether a pixel is inside the heart. See {@link #HEART}.
     *
     * <p>One scale for both axes, so the curve keeps its own proportions and a turned heart is a turned
     * heart rather than a sheared one. The {@code size} is not needed: the curve's box is fitted to the
     * square whichever size that is, which is what lets one definition draw every node.
     */
    private static boolean heartInside(double u, double v, int size) {
        double cu = u * HEART_SPAN;
        // Screen y grows downward: the top of the square is the lobes' peak, the bottom is the point,
        // and the curve's box — not its origin — is what sits in the middle of the square.
        double cv = HEART_MIDDLE - v * HEART_SPAN;
        double dy = cv - Math.pow(Math.abs(cu), 2.0 / 3.0);
        return cu * cu + dy * dy <= 1.0 + ON_BOUNDARY;
    }

    // ------------------------------------------------------------------
    // Sampling, and turning what is sampled
    // ------------------------------------------------------------------

    /**
     * A shape defined by a point test in <b>pixels</b>, sampled into spans.
     *
     * <p>The older factory, kept because a caller with row-and-pixel arithmetic should not have to learn
     * the unit square to draw one thing: it adapts the test to {@link Unit} and hands back the same
     * machinery every built-in uses. A shape made this way has no unit definition of its own, so its turn
     * takes the degraded path — see {@link #rotated}.
     */
    public static Shape sampled(Inside inside) {
        Objects.requireNonNull(inside, "inside");
        return new UnitShape(
                (u, v, size) -> inside.isInside((u + 0.5) * size, (v + 0.5) * size, size), 0, 0);
    }

    /**
     * A shape turned about its own centre and fitted to the node's square.
     *
     * <h2>Rigid, then fitted once — never stretched</h2>
     *
     * <p>The turn is a pure rotation of the shape's own point test, the matrix this geometry has always
     * used: {@code (u, v)} is answered by the base at {@code R(-θ)·(u, v)}. What changed is what happens
     * where the turned outline would leave the square. It used to sample the base's raster inside the
     * same square, so a shape that filled its node — which is all of them — was cut off flat along the
     * node's edges at any angle: a square at 41 degrees came out an octagon, and that is what "squashed"
     * looks like. Sampling a raster also re-stepped an edge that was already stepped.
     *
     * <p>Now the turned shape is measured — how far it reaches from its centre, along
     * {@value #FIT_DIRECTIONS} rays with each boundary bisected — and if it reaches past half the node,
     * <b>one</b> uniform factor brings it back: {@code k = 0.5 / reach}, the same factor on both axes, so
     * the result is a rotation and a scale and never a shear. The factor is at most one, so a turn can
     * only make a node smaller, never bigger; and a shape that never leaves the square (a circle, a
     * square at 90 degrees) is returned untouched rather than shrunk by a rounding error.
     *
     * <p>Zero and 360 degrees return the base shape itself rather than a copy, so the common case costs
     * nothing and the identity is the one rotation a caller can be sure of.
     *
     * <p>A base built by {@link #of} or {@link #ofSpans} has no unit definition to turn, so its raster is
     * sampled through the same inverse rotation and the same uniform fit. That is the degraded path: the
     * turn is still rigid and nothing is cut, but the silhouette is re-stepped, which is why every shape
     * in this class is defined the other way.
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
        return new Turned(base, Math.cos(radians), Math.sin(radians));
    }

    /** Whether a point is inside a shape being sampled. Coordinates are local pixels, {@code 0..size}. */
    @FunctionalInterface
    public interface Inside {
        boolean isInside(double x, double y, int size);
    }

    /** How many sizes a sampled shape remembers before it starts again. See {@link #unit}. */
    private static final int SAMPLED_SIZES_REMEMBERED = 8;

    /** How many bisections an edge gets: six is under a hundredth of a pixel. */
    private static final int EDGE_BISECTIONS = 6;

    /** How many rays a turned shape's reach is measured along: 512 is under a degree apart. */
    private static final int FIT_DIRECTIONS = 512;

    /** How many bisections each ray gets: twenty places a boundary well inside a thousandth of a pixel. */
    private static final int FIT_BISECTIONS = 20;

    /**
     * The room the fit gives the measurement's own error.
     *
     * <p>The reach is measured from inside the boundary along {@value #FIT_DIRECTIONS} rays, so the
     * answer comes out a hair small when a sharp corner falls between two of them — and that is the
     * direction to err in, because a shape measured small is drawn a hair large, where the opposite
     * would cut it. This is the small tolerance that keeps that error from shrinking anything at all: a
     * shape that reaches half the node within it is returned <b>exactly</b> untouched, which is what
     * makes a circle invariant rather than smaller by a thousandth.
     *
     * <p>It is deliberately not a safety margin a person could see. A visible one would cost more than
     * it buys: at 45 degrees a turned square's outermost pixel sits exactly on the boundary, so any
     * real shrink loses the tip row and the fitted shape stops touching its box.
     */
    private static final double FIT_SLACK = 1.0E-9;

    /**
     * A shape that can state its own unit definition.
     *
     * <p>The seam between the factory and the turn: {@link #rotated} turns a normalized shape by turning
     * its definition, and everything else by re-sampling a raster. Package-private because it is an
     * implementation detail rather than a promise to callers.
     */
    interface Normalized {
        Unit unit();
    }

    /** A shape drawn from a unit test: one definition, sampled into the span table everything reads. */
    private static final class UnitShape implements Shape, Normalized {

        private final Unit unit;
        private final double anchorX;
        private final double anchorY;
        private final Tables tables = new Tables();

        UnitShape(Unit unit, double anchorX, double anchorY) {
            this.unit = unit;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }

        @Override
        public int[] spansOf(int row, int size) {
            return tables.spans(unit, row, size);
        }

        @Override
        public Unit unit() {
            return unit;
        }

        @Override
        public int[] iconAnchor(int size) {
            return new int[] {
                    (int) Math.round(anchorX * size),
                    (int) Math.round(anchorY * size)};
        }

        @Override
        public String toString() {
            return "Shape";
        }
    }

    /** A shape turned about its own centre and fitted to the node's square; see {@link #rotated}. */
    private static final class Turned implements Shape, Normalized {

        private final Shape base;
        private final Unit baseUnit;
        private final double cos;
        private final double sin;
        private final java.util.Map<Integer, Double> fits = new java.util.HashMap<>();
        private final Tables tables = new Tables();

        Turned(Shape base, double cos, double sin) {
            this.base = base;
            this.baseUnit = base instanceof Normalized normalized ? normalized.unit() : null;
            this.cos = cos;
            this.sin = sin;
        }

        @Override
        public int[] spansOf(int row, int size) {
            return tables.spans(this::inside, row, size);
        }

        @Override
        public Unit unit() {
            return this::inside;
        }

        /**
         * The base's anchor, turned with the base — so a shape's item stays on the part of the shape
         * that is heavy, at any angle.
         */
        @Override
        public int[] iconAnchor(int size) {
            int[] anchor = base.iconAnchor(size);
            return new int[] {
                    (int) Math.round(anchor[0] * cos - anchor[1] * sin),
                    (int) Math.round(anchor[0] * sin + anchor[1] * cos)};
        }

        /** The turned, fitted shape's point test: undo the fit, undo the turn, ask the base. */
        private boolean inside(double u, double v, int size) {
            double fit = fit(size);
            return insideRaw(u / fit, v / fit, size);
        }

        /**
         * The turned shape before the fit; also what the fit is measured on.
         *
         * <p>Deliberately <b>not</b> clamped to the unit square: how far a turn reaches <i>past</i> the
         * square is the measurement the fit exists for, and a clamp here would cap every shape at half
         * the node and leave a turned square cut off at its corners — the exact bug the fit fixes. The
         * base's own test is what bounds the shape, which is why {@link Unit} requires one that answers
         * false outside the square.
         */
        private boolean insideRaw(double u, double v, int size) {
            double x = u * cos + v * sin;
            double y = -u * sin + v * cos;
            if (baseUnit != null) {
                return baseUnit.isInside(x, y, size);
            }
            // A base with no unit definition is reduced to the raster it draws, and a raster is finite:
            // the point is in its pixels or it is not.
            double px = 0.5 * size + x * size;
            double py = 0.5 * size + y * size;
            if (px < 0 || py < 0 || px >= size || py >= size) {
                return false;
            }
            return base.containsLocal(px, py, size);
        }

        /**
         * The one uniform factor that keeps this turn inside the node's square.
         *
         * <p>Measured rather than computed, because a shape's reach is not a property of its bounding
         * box: a circle reaches half in every direction and must not shrink at all, while a square's
         * corner reaches {@code √2/2} and must. Below half there is nothing to do, so the answer is
         * exactly one — that is the branch that keeps a circle invariant instead of shrinking it by a
         * thousandth.
         */
        private double fit(int size) {
            Double cached = fits.get(size);
            if (cached != null) {
                return cached;
            }
            double reach = measure(size);
            double fit = reach <= 0.5 + FIT_SLACK ? 1.0 : 0.5 / (reach - FIT_SLACK);
            fits.put(size, fit);
            return fit;
        }

        /** How far the turned shape reaches from its centre: the farthest material in any direction. */
        private double measure(int size) {
            if (!insideRaw(0, 0, size)) {
                // Not centred on its own origin, so "how far it reaches" has no single answer and a fit
                // would have no honest basis. Leave it alone: the turn is still rigid and exact.
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
                    if (insideRaw(mid * du, mid * dv, size)) {
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

        @Override
        public String toString() {
            return "Turned shape";
        }
    }

    /**
     * The span table for a point test, built a size at a time and remembered.
     *
     * <p>Sampling is O(size²) and a node is drawn at the same size for as long as the zoom holds still,
     * so a table is built once per size and kept. The memory is bounded — a handful of sizes — and this
     * is not thread-safe by design: shapes are drawn on the client's render thread, and a lock on the
     * frame path would cost more than the table.
     */
    private static final class Tables {

        private final java.util.Map<Integer, int[][]> tables = new java.util.LinkedHashMap<>(4);

        int[] spans(Unit unit, int row, int size) {
            if (size <= 0 || row < 0 || row >= size) {
                return null;
            }
            int[][] table = tables.get(size);
            if (table == null) {
                table = sample(unit, size);
                if (tables.size() >= SAMPLED_SIZES_REMEMBERED) {
                    tables.clear();
                }
                tables.put(size, table);
            }
            return table[row];
        }

        /** The whole span table for one size: one entry per row, possibly empty or null. */
        private int[][] sample(Unit unit, int size) {
            int[][] table = new int[size][];
            for (int row = 0; row < size; row++) {
                table[row] = sampleRow(unit, row, size);
            }
            return table;
        }

        private int[] sampleRow(Unit unit, int row, int size) {
            double y = row + 0.5;
            int[] runs = new int[size * 2];
            int found = 0;
            int start = -1;
            for (int col = 0; col < size; col++) {
                boolean here = inside(unit, col + 0.5, y, size);
                if (here && start < 0) {
                    start = col;
                }
                else if (!here && start >= 0) {
                    found = addRun(unit, runs, found, start, col, y, size);
                    start = -1;
                }
            }
            if (start >= 0) {
                found = addRun(unit, runs, found, start, size, y, size);
            }
            return found == 0 ? null : Arrays.copyOf(runs, found * 2);
        }

        /** One run, with both edges bisected to where the curve actually crosses the row. */
        private int addRun(Unit unit, int[] runs, int found, int start, int end, double y, int size) {
            int from = edge(unit, start, start - 0.5, y, size);
            int to = edge(unit, end - 1, end + 0.5, y, size);
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
        private int edge(Unit unit, int sample, double beyond, double y, int size) {
            double within = sample + 0.5;
            for (int i = 0; i < EDGE_BISECTIONS; i++) {
                double mid = (within + beyond) / 2.0;
                if (inside(unit, mid, y, size)) {
                    within = mid;
                }
                else {
                    beyond = mid;
                }
            }
            return (int) Math.ceil((within + beyond) / 2.0 - 0.5);
        }

        /** The unit test asked about a pixel-space point: the sampler works in pixels, shapes in units. */
        private boolean inside(Unit unit, double x, double y, int size) {
            return unit.isInside(x / size - 0.5, y / size - 0.5, size);
        }
    }

    @Override
    public String toString() {
        return "Shapes";
    }
}
