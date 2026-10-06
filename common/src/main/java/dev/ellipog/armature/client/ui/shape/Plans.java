package dev.ellipog.armature.client.ui.shape;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A shape's ink as rectangles, worked out once and reused.
 *
 * <h2>What this replaces, and what it does not</h2>
 *
 * <p>Every shaped draw — a node's four layers, a panel, a control's face — asks a shape for its spans one
 * row at a time and merges runs of equal rows as it goes. That is a table lookup and an {@code Arrays.equals}
 * per row, per call, and a node at 48 pixels has about two hundred rows across its layers. This does that
 * work once per shape and size and hands back flat rectangles instead.
 *
 * <p><b>It does not reduce the fills.</b> A circle is one fill per distinct row and stays that way: the
 * rectangles are the same rectangles, and merging them differently would change the pixels. What goes away
 * is the walking, the comparing and the per-row lookups in front of them — a third to a half of that path's
 * CPU, which is worth having and is not a cure for a canvas of a thousand nodes.
 *
 * <h2>Why the rectangles are relative</h2>
 *
 * <p>Because the plan is keyed by the shape and the size, not by where it is drawn: a panel that moved is
 * the same plan. So the rectangles are measured from the draw origin and the caller adds it — the same
 * split {@code LineArt} uses for a route, and for the same reason.
 *
 * <h2>Why identity keys work for a shape</h2>
 *
 * <p>Because the layers a node is drawn from are stable objects: the built-in shapes are singletons, and
 * {@link Shape#inner(int)} and {@link Shape#outer(int)} memoise their own results per inset on the shapes
 * that implement them. So the same layer is the same object on every frame, and a plan keyed by it is found
 * again rather than being rebuilt per node. A shape built afresh at a call site would miss every frame, which
 * is why {@code Shapes.cached} exists to make one that does not.
 */
public final class Plans {

    /** The most shape plans remembered. Past it the map is emptied, matching the other caches here. */
    private static final int LIMIT = 512;

    /**
     * One shape's ink, as rectangles relative to the draw origin.
     *
     * <p>Four ints each — {@code x1, y1, x2, y2}, half-open like every other rectangle in this toolkit — in
     * one array rather than a list of records: this is read in the innermost loop of the frame, so it is
     * read without an iterator and without an object per rectangle.
     */
    public static final class Plan {

        private final int[] rects;

        Plan(int[] rects) {
            this.rects = rects;
        }

        /** How many rectangles this plan draws. */
        public int count() {
            return rects.length / 4;
        }

        public int x1(int index) {
            return rects[index * 4];
        }

        public int y1(int index) {
            return rects[index * 4 + 1];
        }

        public int x2(int index) {
            return rects[index * 4 + 2];
        }

        public int y2(int index) {
            return rects[index * 4 + 3];
        }
    }

    /** The shape and size a plan was built from. */
    private record ShapeKey(Shape shape, int size) {
    }

    private static final Map<ShapeKey, Plan> SHAPES = new HashMap<>();

    private Plans() {
    }

    /** The rectangles for a shape drawn at this size, remembered. See the class note for the key. */
    public static Plan of(Shape shape, int size) {
        Objects.requireNonNull(shape, "shape");
        if (size <= 0) {
            return new Plan(new int[0]);
        }
        ShapeKey key = new ShapeKey(shape, size);
        Plan plan = SHAPES.get(key);
        if (plan != null) {
            return plan;
        }
        Plan built = merge(shape::spans, size);
        if (SHAPES.size() >= LIMIT) {
            SHAPES.clear();
        }
        SHAPES.put(key, built);
        return built;
    }

    /**
     * The rectangles for any row-to-spans lookup, for a caller whose shape is not an object.
     *
     * <p>The general form, and what {@link #of} and the surface case are both built from. It is not
     * remembered here because it has no key: the callers that can name what they are — a shape, or a
     * rounded rectangle's four numbers — memoise it themselves.
     *
     * <p>The merge is the one {@code ArmatureTheme.fillShape} has always done: a run of rows whose whole
     * span list is equal is one rectangle, and comparing only the first pair would merge a heart's two-lobed
     * row with a one-lobed row beneath it and fill the notch.
     */
    public static Plan merge(Shapes.SpansOf spans, int size) {
        int[] rects = new int[size * 8];
        int at = 0;
        int row = 0;
        while (row < size) {
            int[] span = spans.spansOf(row, size);
            if (span == null) {
                row++;
                continue;
            }
            int end = row + 1;
            while (end < size && Arrays.equals(spans.spansOf(end, size), span)) {
                end++;
            }
            for (int i = 0; i < span.length; i += 2) {
                if (at + 4 > rects.length) {
                    rects = Arrays.copyOf(rects, rects.length * 2);
                }
                rects[at] = span[i];
                rects[at + 1] = row;
                rects[at + 2] = span[i + 1];
                rects[at + 3] = end;
                at += 4;
            }
            row = end;
        }
        return new Plan(Arrays.copyOf(rects, at));
    }
}
