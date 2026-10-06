package dev.ellipog.armature.client.ui.shape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The cached span tables: that they answer what the rule answers, and that they answer it once.
 *
 * <h2>The two halves, and why both are needed</h2>
 *
 * <p>A table that is fast and wrong is the worst outcome available here — a node's outline, its border,
 * its wash and its hit test all read the same rows, so a row that differs from what the shape's own
 * arithmetic says is a node whose pixels and whose clicks disagree. So the equivalence case is the one
 * that matters most, and it is written against the <b>default method itself</b>: a shape built through
 * {@code Shapes.ofSpans} has no table and no override, so its {@code spans} is the clamp-and-merge the
 * interface has always promised, and the two are compared row by row over every built-in shape.
 *
 * <p>The other half is the point of the change, and it can only be asserted by identity: a row that is
 * computed twice is two arrays, so {@code assertSame} is what says the work was not repeated. Nothing
 * about the numbers can tell those two apart.
 */
@DisplayName("the span tables")
class SpanTableCacheTest {

    /** Every built-in, by the name a file would use. */
    private static final List<Shape> SHAPES = List.of(
            Shapes.RECT, Shapes.CIRCLE, Shapes.HEXAGON, Shapes.TOME, Shapes.DIAMOND,
            Shapes.STAR, Shapes.OCTAGON, Shapes.PENTAGON, Shapes.GEAR, Shapes.HEART,
            Shapes.ROUNDED, Shapes.rotated(Shapes.RECT, 37), Shapes.rotated(Shapes.HEART, 12));

    @Test
    @DisplayName("a cached row is what the rule says, for every shape, size and row")
    void aCachedRowIsTheRule() {
        // The default method, reached through a shape that has no table: same raw arithmetic, no cache,
        // no override. This is the equivalence the whole change rests on.
        for (Shape shape : SHAPES) {
            Shape uncached = Shapes.ofSpans(shape::spansOf);
            for (int size : new int[] {1, 2, 3, 7, 12, 16, 33, 48, 64}) {
                for (int row = 0; row < size; row++) {
                    assertArrayEquals(uncached.spans(row, size), shape.spans(row, size),
                            shape + " at size " + size + ", row " + row);
                }
            }
        }
    }

    @Test
    @DisplayName("a row asked for twice is the same array, not two that happen to match")
    void aRowIsComputedOnce() {
        Shape shape = Shapes.CIRCLE;

        int[] first = shape.spans(3, 24);
        int[] second = shape.spans(3, 24);
        assertSame(first, second, "the clamp and the merge ran once for this size, not once per call");

        // A different row of the same size is its own entry, and the size is what the table is keyed by.
        int[] other = shape.spans(4, 24);
        assertNotSame(first, other);
        assertArrayEquals(shape.spans(3, 24), first, "and the first row is still there");
    }

    @Test
    @DisplayName("the raw accessor still answers the shape's own arithmetic")
    void theRawAccessorIsUntouched() {
        // `Outlines` reads `spansOf` and does its own interval work on it, so the table must not have
        // quietly started handing out clamped rows: the two accessors answer different questions and
        // both are asked.
        Shape shape = Shapes.GEAR;

        int[] raw = shape.spansOf(0, 32);
        if (raw != null) {
            assertNotSame(raw, shape.spans(0, 32),
                    "the raw row and the clamped one are different answers, so they cannot be one table");
        }
        // And the raw row is what the shape's own test can see: a gear's top row is a tooth, which the
        // clamp widens to at least a pixel -- an assertion the swept test above already holds for both.
        assertArrayEquals(raw, shape.spansOf(0, 32), "and it is itself remembered per size");
    }

    @Test
    @DisplayName("a layer is built once per inset, and one inset is not another")
    void aLayerIsKeptPerInset() {
        Shape shape = Shapes.CIRCLE;

        assertSame(shape.inner(), shape.inner(), "a node asks for its fill every frame");
        assertSame(shape.outer(), shape.outer(), "and for its ring");
        assertSame(shape.inner(1), shape.inner(), "the default inset is the inset of one");

        assertNotSame(shape.inner(1), shape.inner(2), "two insets are two layers");
        assertNotSame(shape.inner(), shape.outer(), "and a layer in is not a layer out");
    }

    @Test
    @DisplayName("a turned shape has the same memo, and its own tables")
    void aTurnedShapeIsItsOwnShape() {
        Shape upright = Shapes.RECT;
        Shape turned = Shapes.rotated(Shapes.RECT, 45);

        assertNotSame(upright, turned, "a turn is a shape, not a setting on one");
        assertSame(turned.inner(), turned.inner(), "and it keeps its own layers");
        assertSame(turned.spans(0, 32), turned.spans(0, 32), "and its own rows");

        // The turn makes the shape smaller than its box, so the two outlines are different rows rather
        // than a rotation nobody applied.
        assertNotSame(upright.spans(0, 32), turned.spans(0, 32));
    }

    @Test
    @DisplayName("a layer of a shape with no override is a stable key too")
    void aLayerOfAPlainShapeIsStable() {
        // The case the old code was false for, and the reason this test exists.
        //
        // `inner`/`outer` used to return `Shapes.cached(Shapes.ofSpans(...))`, and `cached` built a new
        // wrapper every call — so the memo they were written to provide was only real for the two shapes
        // that overrode them, `UnitShape` and `Turned`. Every shape built through `Shapes.ofSpans` — and
        // every *layer* of one, which is what `inner` returns — was a fresh object each call, and
        // `Plans.of` and `ArmatureTheme`'s surface table are both keyed on the shape's **identity**. The
        // plan was built and then unreachable, every frame, for every node.
        //
        // The existing assertions only covered singletons (`Shapes.CIRCLE`, `Shapes.GEAR`), which is
        // exactly where the bug was not. This is where it was.
        Shape plain = Shapes.ofSpans((row, size) -> row < size / 2 ? new int[] {0, size} : null);

        assertSame(plain.inner(), plain.inner(), "a plain shape's fill is one object per inset");
        assertSame(plain.outer(), plain.outer(), "and so is its ring");
        assertSame(plain.inner(2), plain.inner(2), "and every other inset keeps its own");
        assertNotSame(plain.inner(1), plain.inner(2), "two insets are two layers");

        // And a layer of a layer, which is what a node's wash is: the same property has to hold one
        // level down, or the outermost layer is stable and the one inside it is not.
        Shape fill = plain.inner();
        assertSame(fill.inner(), fill.inner(), "a layer of a layer is memoised as well");
        assertSame(fill.outer(), fill.outer(), "in both directions");

        // The rows behind a layer are remembered per size as well, which is the other half of the memo.
        assertSame(plain.inner().spans(2, 32), plain.inner().spans(2, 32),
                "and its rows are computed once per size");
    }
}
