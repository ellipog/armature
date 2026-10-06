package dev.ellipog.armature.client.ui.shape;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.RecordingRenderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shape's remembered rectangles must be the rectangles the row-by-row merge produces.
 *
 * <h2>Why the assertion is "the same fills", not "the same plan"</h2>
 *
 * <p>Because the plan is an optimisation and the fills are the drawing. A plan that merged one row too many
 * would be a shape with a corner flattened; one that missed a row would be a notch in an outline — and
 * neither is visible in a diff, because both are still a list of rectangles that looks right. So each case
 * draws the same shape twice through a recording renderer, once through the plan and once through the
 * per-row merge that came before it, and compares the fills themselves.
 *
 * <p>The sizes are the interesting ones rather than a range: below the smallest node, the sizes the shipped
 * examples use, the size where a gear's tooth count steps, and a size past the sweep the shape tables were
 * built for.
 *
 * <h2>What this cannot cover, and what does</h2>
 *
 * <p>The rounded <i>surfaces</i> — panels and control faces — have no shape object, so there is no slow path
 * left to compare them against; their gate is the **previews**, which draw every panel at every size and are
 * compared byte for byte. What is asserted for them here is that the memo answers the same thing twice,
 * which is the failure a cache can have that the previews cannot distinguish.
 */
@DisplayName("a shape's remembered rectangles")
class PlanTest {

    /**
     * Every built-in, so one cannot be forgotten by not being listed — the same list `GuiRendererTest`
     * sweeps, and it caught this one: `STAR` was missing from an earlier version of it while the comment
     * claimed completeness.
     */
    private static final List<Shape> SHAPES = List.of(
            Shapes.ROUNDED, Shapes.RECT, Shapes.CIRCLE, Shapes.DIAMOND, Shapes.HEXAGON,
            Shapes.OCTAGON, Shapes.PENTAGON, Shapes.GEAR, Shapes.HEART, Shapes.TOME, Shapes.STAR);

    private static final int[] SIZES =
            {4, 5, 8, 12, 15, 16, 17, 23, 30, 31, 32, 48, 56, 64, 89, 127, 224, 259};

    @Test
    @DisplayName("the plan draws exactly what the per-row merge drew, for every shape and size")
    void thePlanMatchesTheRowMerge() {
        for (Shape shape : SHAPES) {
            for (int size : SIZES) {
                RecordingRenderer planned = RecordingRenderer.create();
                RecordingRenderer merged = RecordingRenderer.create();

                ArmatureTheme.fillShape(planned, 3, 5, size, 0xFF112233, shape);
                // The old path, named explicitly: `shape::spans` alone is ambiguous between the two
                // overloads — a shape and a row lookup are both functional interfaces with the same shape
                // of method — so the type has to be said out loud. That ambiguity is why the shape overload
                // is the one callers use and this is the one tests use.
                ArmatureTheme.Spans rows = shape::spans;
                ArmatureTheme.fillShape(merged, 3, 5, size, 0xFF112233, rows);

                assertEquals(merged.fills(), planned.fills(),
                        shape + " at size " + size + " draws different rectangles through the plan");
            }
        }
    }

    @Test
    @DisplayName("a shape drawn twice draws the same thing, and its layer is a stable key")
    void theSecondDrawIsTheSameOne() {
        // The property the memo depends on and the previews cannot see: the same shape and size must answer
        // the same rectangles on a later frame, which is only true because `inner`/`outer` memoise the layer
        // they return. A layer built afresh per call would still draw correctly and would miss the plan
        // every frame -- correct and pointless, which is the failure worth pinning.
        RecordingRenderer first = RecordingRenderer.create();
        RecordingRenderer second = RecordingRenderer.create();

        ArmatureTheme.shapePanel(first, 0, 0, 48, 0xFF111111, 0xFF222222, Shapes.GEAR);
        ArmatureTheme.shapePanel(second, 0, 0, 48, 0xFF111111, 0xFF222222, Shapes.GEAR);

        assertEquals(first.fills(), second.fills(), "the same panel twice");
        assertTrue(first.fills().size() > 10, "and it is a shaped panel, not one rectangle");

        assertTrue(Shapes.GEAR.inner() == Shapes.GEAR.inner(),
                "a shape's eroded layer is the same object every time it is asked for — otherwise the "
                        + "plan can never be found again and every node rebuilds it");
        assertTrue(Shapes.GEAR.outer() == Shapes.GEAR.outer(), "and so is its halo");
    }

    @Test
    @DisplayName("the rectangles are relative, so the same plan draws anywhere")
    void thePlanIsRelativeToTheDrawOrigin() {
        // Moving a panel must not build a second plan: the origin is the caller's, and the plan is the
        // shape. Two draws of one shape at different origins must therefore differ by exactly that offset.
        RecordingRenderer here = RecordingRenderer.create();
        RecordingRenderer there = RecordingRenderer.create();

        ArmatureTheme.fillShape(here, 0, 0, 40, 0xFFFFFFFF, Shapes.CIRCLE);
        ArmatureTheme.fillShape(there, 100, 60, 40, 0xFFFFFFFF, Shapes.CIRCLE);

        assertEquals(here.fills().size(), there.fills().size(), "the same number of rectangles");
        for (int i = 0; i < here.fills().size(); i++) {
            RecordingRenderer.Call a = here.fills().get(i);
            RecordingRenderer.Call b = there.fills().get(i);
            assertEquals(a.x() + 100, b.x());
            assertEquals(a.y() + 60, b.y());
            assertEquals(a.x2() + 100, b.x2());
            assertEquals(a.y2() + 60, b.y2());
        }
    }
}
