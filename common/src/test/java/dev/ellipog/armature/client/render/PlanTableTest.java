package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ui.shape.Plans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the GPU masks remember per plan: a mask's place, or that the plan is drawn as fills.
 *
 * <h2>What is held</h2>
 *
 * <p>A refusal is found again by a plan with the same rectangles, which is what makes a refused plan one lookup
 * a frame rather than a bounding box worked out again. A refusal is never mistaken for a mask, or a mask for a
 * refusal. And the refusals are bounded on their own, swept out together when there are enough of them, while
 * the masks beside them stay: a swept mask would be written again into a second region, and the texture has no
 * way to give the first one back until it is emptied.
 */
@DisplayName("The masks' plan table")
class PlanTableTest {

    /** A one-rectangle plan {@code width} pixels wide: small, and no two widths alike. */
    private static Plans.Plan bar(int width) {
        return Plans.merge((row, size) -> new int[] {0, width}, 1);
    }

    private static GpuMasks.Placed somewhere(int at) {
        return new GpuMasks.Placed(0, 0, 4, 4, at / 2048F, 0F, (at + 4) / 2048F, 4 / 2048F);
    }

    @Test
    @DisplayName("a refusal is answered from the table, for any plan with the same rectangles")
    void aRefusalIsRemembered() {
        GpuMasks.PlanTable table = new GpuMasks.PlanTable();
        assertNull(table.find(new GpuMasks.PlanKey(bar(9))), "never asked");

        table.refuse(new GpuMasks.PlanKey(bar(9)));
        assertSame(GpuMasks.PlanTable.REFUSED, table.find(new GpuMasks.PlanKey(bar(9))),
                "a plan rebuilt with the same rectangles finds the refusal");
        assertNull(table.find(new GpuMasks.PlanKey(bar(10))), "and a different plan does not");
    }

    @Test
    @DisplayName("a mask is a mask and a refusal is a refusal")
    void masksAndRefusalsAreApart() {
        GpuMasks.PlanTable table = new GpuMasks.PlanTable();
        GpuMasks.Placed placed = somewhere(10);
        table.place(new GpuMasks.PlanKey(bar(5)), placed);
        table.refuse(new GpuMasks.PlanKey(bar(6)));
        assertSame(placed, table.find(new GpuMasks.PlanKey(bar(5))));
        assertSame(GpuMasks.PlanTable.REFUSED, table.find(new GpuMasks.PlanKey(bar(6))));
        assertEquals(0, GpuMasks.PlanTable.REFUSED.right() - GpuMasks.PlanTable.REFUSED.left(),
                "the refusal has an empty box, which no mask has");
    }

    @Test
    @DisplayName("refusals are swept out at their bound, and the masks beside them stay")
    void refusalsAreBounded() {
        GpuMasks.PlanTable table = new GpuMasks.PlanTable();
        int masks = 20;
        for (int i = 0; i < masks; i++) {
            table.place(new GpuMasks.PlanKey(bar(4 + i)), somewhere(i * 8));
        }
        int refusals = GpuMasks.PlanTable.MAX_REFUSALS * 3 + 7;
        for (int i = 0; i < refusals; i++) {
            table.refuse(new GpuMasks.PlanKey(bar(100 + i)));
            assertTrue(table.size() <= masks + GpuMasks.PlanTable.MAX_REFUSALS,
                    "the table holds " + table.size() + " entries after " + (i + 1) + " refusals");
        }
        for (int i = 0; i < masks; i++) {
            assertEquals(somewhere(i * 8), table.find(new GpuMasks.PlanKey(bar(4 + i))),
                    "a mask outlives every sweep of the refusals");
        }
        assertSame(GpuMasks.PlanTable.REFUSED,
                table.find(new GpuMasks.PlanKey(bar(100 + refusals - 1))), "the latest refusal is kept");
    }

    @Test
    @DisplayName("emptying the table forgets masks and refusals alike")
    void clearForgetsBoth() {
        GpuMasks.PlanTable table = new GpuMasks.PlanTable();
        table.place(new GpuMasks.PlanKey(bar(5)), somewhere(0));
        table.refuse(new GpuMasks.PlanKey(bar(6)));
        table.clear();
        assertEquals(0, table.size());
        assertNull(table.find(new GpuMasks.PlanKey(bar(5))));
        assertNull(table.find(new GpuMasks.PlanKey(bar(6))));
    }
}
