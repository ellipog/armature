package dev.ellipog.armature.client.ui.shape;

import dev.ellipog.armature.client.ui.kit.RoundedRect;

/**
 * The built-in shapes, and the two ways to make a rounded rectangle.
 *
 * <h2>Shapes are values, not enum members</h2>
 *
 * <p>{@link Shape} is an interface with two methods, so a shape is an object a caller can hold, pass,
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
     * <p>A named method rather than calling {@code HEXAGON.startOf} from its own second lambda, which
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
            case "rect", "rectangle", "square" -> RECT;
            default -> fallback;
        };
    }

    /**
     * Builds a shape from the two edges of its span.
     *
     * <p>The only way to make one, so no implementation can skip the bounds check and the clamp that
     * {@link Shape#span} applies — an anonymous implementation would be free to override {@code span}
     * itself and get one of the three guarantees wrong, and nothing would notice until a node had a
     * notch in it.
     */
    public static Shape of(java.util.function.IntBinaryOperator startOf,
                           java.util.function.IntBinaryOperator endOf) {
        java.util.Objects.requireNonNull(startOf, "startOf");
        java.util.Objects.requireNonNull(endOf, "endOf");
        return new Shape() {
            @Override
            public int startOf(int row, int size) {
                return startOf.applyAsInt(row, size);
            }

            @Override
            public int endOf(int row, int size) {
                return endOf.applyAsInt(row, size);
            }

            @Override
            public String toString() {
                return "Shape";
            }
        };
    }

    @Override
    public String toString() {
        return "Shapes";
    }
}
