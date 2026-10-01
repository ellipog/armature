package dev.ellipog.armature.client.ui.inspect;

import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inspector's composition: rows into a column, and every control inside the row that names it.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The same questions the other lists in this codebase ask of their own compositions, because they are
 * the questions that went wrong when they were only ever looked at: do the rows stack without overlap,
 * does the height count them all, does every control strip sit inside its own row with room for a label
 * beside it, and does a slot mapped through a viewport land where the viewport says it should. All of it
 * is answerable without a client -- which is why the composition is here and the drawing is not.
 */
@DisplayName("the property inspector's layout")
class InspectLayoutTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);

    /** A panel's worth of rows: every kind at least once, in the order a real panel would show them. */
    private static List<InspectRow> rows() {
        return List.of(
                InspectRow.heading("identity", "Identity"),
                InspectRow.value("inspect:id", "Id", "stone_age"),
                InspectRow.field("title", "Title", "The Stone Age"),
                InspectRow.heading("placement", "Placement"),
                InspectRow.field("x", "X", "0"),
                InspectRow.field("y", "Y", "0"),
                InspectRow.stepper("size", "Node size", "32"),
                InspectRow.toggle("showTitle", "Show title"),
                InspectRow.warning("raw", "Unknown type"),
                InspectRow.raw("raw:json", "addon:custom", "{\"custom\":true}"),
                InspectRow.action("add", "Add dependency"));
    }

    @Test
    @DisplayName("rows stack in order without overlap, and the height is the bottom of the last row")
    void rowsStackWithoutOverlap() {
        for (int width : List.of(288, 200, 180, 120, 60)) {
            List<InspectRow> rows = rows();
            Layout layout = InspectLayout.build(rows, width, MEASURE);

            List<Slot> slots = layout.slots();
            assertEquals(rows.size(), slots.size(), "one slot per row, at width " + width);
            int lowest = 0;
            for (int i = 0; i < slots.size(); i++) {
                final int at = i;
                Slot slot = slots.get(at);
                assertEquals(rows.get(at).key(), slot.key(), "the rows come out in the order given");
                assertTrue(slot.width() >= 0 && slot.height() > 0, () -> "an empty row: " + slot);
                if (at > 0) {
                    assertTrue(slot.y() >= slots.get(at - 1).bottom(),
                            () -> "row " + at + " overlaps the one before it: " + slot);
                }
                lowest = Math.max(lowest, slot.bottom());
            }
            assertEquals(lowest, layout.height(), "the height is the bottom of the last row placed");
        }
    }

    @Test
    @DisplayName("every control strip is inside its own row, and the label owns the row left of it")
    void stripsSitInsideTheirRows() {
        Layout layout = InspectLayout.build(rows(), 288, MEASURE);

        for (InspectRow row : rows()) {
            if (!row.hasStrip()) {
                continue;
            }
            Slot slot = layout.slot(row.key());
            assertNotNull(slot, () -> "no slot for " + row.key());
            Slot strip = InspectLayout.strip(slot);
            assertTrue(strip.x() >= slot.x(),
                    () -> "the strip of " + row.key() + " left its row's left edge: " + strip);
            assertTrue(strip.right() <= slot.right(),
                    () -> "the strip of " + row.key() + " left its row: " + strip);
            assertTrue(strip.y() >= slot.y() && strip.bottom() <= slot.bottom(),
                    () -> "the strip of " + row.key() + " left its row vertically: " + strip);
        }
    }

    @Test
    @DisplayName("nothing leaves its row, even at a column too narrow for the strip")
    void narrowColumnsKeepEverythingInside() {
        // A column narrower than the strip's reservation: the row is narrowed to nothing by its own
        // insets, and whatever is left must still be inside. A strip that could overrun its row would
        // draw its field over the label beside it -- or over the row above.
        Layout layout = InspectLayout.build(List.of(InspectRow.field("x", "X", "0")), 40, MEASURE);
        Slot slot = layout.slot("x");
        Slot strip = InspectLayout.strip(slot);

        assertTrue(slot.width() >= 0 && strip.width() >= 0, "and nothing goes negative");
        assertTrue(strip.x() >= slot.x() && strip.right() <= slot.right(),
                "the strip stays inside its row, however narrow the column");
    }

    @Test
    @DisplayName("at the designed width a field's label has real room beside its strip")
    void labelsHaveRoomAtTheDesignedWidth() {
        Layout layout = InspectLayout.build(List.of(InspectRow.field("x", "X", "0")), 288, MEASURE);
        Slot slot = layout.slot("x");
        Slot strip = InspectLayout.strip(slot);

        assertTrue(strip.x() - slot.x() >= 40, "the label owns the row left of the strip");
    }

    @Test
    @DisplayName("headings are their own band, and a section is separated from what precedes it")
    void headingsBandTheirSections() {
        Layout layout = InspectLayout.build(rows(), 288, MEASURE);

        Slot heading = layout.slot("identity");
        Slot first = layout.slot("inspect:id");
        Slot placement = layout.slot("placement");
        Slot beforePlacement = layout.slot("title");

        assertEquals(InspectLayout.HEADING_HEIGHT, heading.height(), "a heading is its own band");
        assertEquals(InspectLayout.ROW_HEIGHT, first.height(), "a row is a row");
        assertTrue(placement.y() >= beforePlacement.bottom() + InspectLayout.ROW_GAP,
                "a section's name is separated from the section before it");
        assertTrue(placement.y() >= heading.bottom(), "and never rides up into its own heading");
    }

    @Test
    @DisplayName("folding is the caller passing fewer rows, and leaves no gap where the section was")
    void foldingLeavesNoStrayGap() {
        List<InspectRow> everything = rows();
        List<InspectRow> folded = everything.stream().filter(row -> !row.key().startsWith("title")
                && !row.key().equals("identity")).toList();

        Layout open = InspectLayout.build(everything, 288, MEASURE);
        Layout shut = InspectLayout.build(folded, 288, MEASURE);

        assertTrue(shut.height() < open.height(), "a folded panel is shorter");
        List<Slot> slots = shut.slots();
        for (int i = 1; i < slots.size(); i++) {
            final int at = i;
            assertTrue(slots.get(at).y() >= slots.get(at - 1).bottom(),
                    () -> "a gap opened where the folded section was: " + slots.get(at));
        }
    }

    @Test
    @DisplayName("a slot is put where the viewport says it is, scroll and all")
    void rowsMapThroughTheViewport() {
        Layout layout = InspectLayout.build(rows(), 200, MEASURE);
        Slot row = layout.slot("x");

        Viewport view = Viewport.fixed().bounds(40, 60, 200, 120);
        Slot mapped = InspectLayout.onScreen(view, row);
        assertEquals(40 + row.x(), mapped.x());
        assertEquals(60 + row.y(), mapped.y());

        view.setOffset(0, -30);
        Slot scrolled = InspectLayout.onScreen(view, row);
        assertEquals(40 + row.x(), scrolled.x());
        assertEquals(60 + row.y() - 30, scrolled.y());
    }

    @Test
    @DisplayName("nothing here needs a width to be positive, and zero is a panel that holds nothing")
    void zeroWidthHoldsNothing() {
        List<InspectRow> given = rows();
        Layout layout = InspectLayout.build(given, 0, MEASURE);
        assertEquals(given.size(), layout.slots().size(), "the rows are placed, however narrow");
        for (Slot slot : layout.slots()) {
            assertTrue(slot.y() >= 0, () -> "a row left the column: " + slot);
        }
    }
}
