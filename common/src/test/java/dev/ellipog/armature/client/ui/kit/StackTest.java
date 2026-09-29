package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stacking engine, asserted instead of agreed by hand.
 *
 * <h2>What this file is actually about</h2>
 *
 * <p>The defect {@link Stack} exists to make unrepresentable is a measure pass and a draw pass that
 * have to be kept in step by a comment. So the assertions here are mostly the same shape: that the
 * number the layout reports and the rectangles it placed are the same computation, not two that agreed
 * once.
 *
 * <p>{@link Insets} is covered here rather than in a file of its own because the only interesting thing
 * about three integers and a flag is what they do to a slot, and that needs a stack to see.
 */
class StackTest {

    /** Ten pixels a character, ten pixels a line. Arithmetic a person can check by eye. */
    private static final Measure TEN = Measure.monospace(10, 10);

    @Test
    void anEmptyStackIsEmptyAndZeroTall() {
        Layout layout = Stack.stack().build(100, TEN);
        assertTrue(layout.isEmpty());
        assertEquals(0, layout.height());
        assertEquals(List.of(), layout.slots());
    }

    @Test
    void rowsStackInOrderWithNoOverlap() {
        Layout layout = Stack.stack()
                .row("a", 20)
                .row("b", 20)
                .row("c", 20)
                .build(100, TEN);

        assertEquals(3, layout.slots().size());
        assertEquals(0, layout.slot("a").y());
        assertEquals(20, layout.slot("b").y());
        assertEquals(40, layout.slot("c").y());

        for (int i = 1; i < layout.slots().size(); i++) {
            Slot previous = layout.slots().get(i - 1);
            Slot current = layout.slots().get(i);
            assertTrue(current.y() >= previous.bottom(),
                    "row " + i + " starts at " + current.y() + ", above " + previous.bottom());
        }
    }

    @Test
    void theReportedHeightIsTheBottomOfTheLowestSlot() {
        // The invariant, stated as the thing it is: the height and the slots cannot disagree, because
        // the height is derived from them. A stack that accumulated a height alongside its slots would
        // pass a spot check and fail this.
        for (int rows = 0; rows < 8; rows++) {
            Stack stack = Stack.stack();
            for (int i = 0; i < rows; i++) {
                stack.row("r" + i, 7 + i).gap(i);
            }
            Layout layout = stack.build(80, TEN);

            int lowest = 0;
            for (Slot slot : layout.slots()) {
                lowest = Math.max(lowest, slot.bottom());
            }
            assertEquals(lowest, layout.height(), "with " + rows + " row(s)");
        }
    }

    @Test
    void aGapTakesHeightAndPlacesNothing() {
        Layout layout = Stack.stack().row("a", 20).gap(6).row("b", 20).build(100, TEN);
        assertEquals(2, layout.slots().size());
        assertEquals(26, layout.slot("b").y());
        assertEquals(46, layout.height());
    }

    @Test
    void everySlotStaysInsideTheColumn() {
        Layout layout = Stack.stack()
                .heading("TASKS")
                .row("a", 22, Insets.symmetric(8, 0))
                .block("b", 30, 12, Stack.Align.CENTRE)
                .block("c", 30, 12, Stack.Align.RIGHT)
                .divider(4, 4)
                .build(100, TEN);

        for (Slot slot : layout.slots()) {
            assertTrue(slot.x() >= 0, slot.key() + " starts at x=" + slot.x());
            assertTrue(slot.right() <= 100, slot.key() + " ends at " + slot.right() + ", column is 100");
            assertTrue(slot.width() >= 0, slot.key() + " has negative width");
        }
    }

    @Test
    void insetsShrinkTheSlotAndLeaveTheAdvanceAlone() {
        // A block's own advance is its height plus its vertical insets; the slot inside is the height.
        // That distinction is what lets a padded row line up with its neighbours while the thing drawn
        // in it is inset -- and getting it backwards is how a list ends up with rows a few pixels apart.
        Layout layout = Stack.stack()
                .block("padded", 0, 20, Stack.Align.STRETCH, Insets.symmetric(8, 3))
                .block("next", 0, 20, Stack.Align.STRETCH)
                .build(100, TEN);

        Slot padded = layout.slot("padded");
        assertEquals(8, padded.x());
        assertEquals(84, padded.width());
        assertEquals(3, padded.y());
        assertEquals(20, padded.height());
        assertEquals(26, layout.slot("next").y());
    }

    @Test
    void alignmentIsTheElementsOwnDecision() {
        Layout layout = Stack.stack()
                .text("left", "abcd", Stack.Align.LEFT)
                .text("centre", "abcd", Stack.Align.CENTRE)
                .text("right", "abcd", Stack.Align.RIGHT)
                .text("stretch", "abcd", Stack.Align.STRETCH)
                .build(100, TEN);

        // The width is the text's own in every case but STRETCH — only the left edge moves. That is
        // what makes "centred" mean centred text rather than a centred full-width box, and it is why
        // the centre row's width is 40 and not 30. My first version asserted 30 for it while asserting
        // 30 for its x as well, which is two different quantities assumed equal.
        assertEquals(0, layout.slot("left").x());
        assertEquals(40, layout.slot("left").width());
        assertEquals(30, layout.slot("centre").x());
        assertEquals(40, layout.slot("centre").width());
        assertEquals(60, layout.slot("right").x());
        assertEquals(40, layout.slot("right").width());
        assertEquals(0, layout.slot("stretch").x());
        assertEquals(100, layout.slot("stretch").width());
    }

    @Test
    void aTextSlotIsAsTallAsTheLinesItWrappedTo() {
        Layout layout = Stack.stack().text("t", "alpha beta gamma", Stack.Align.LEFT).build(60, TEN);
        int lines = TextWrap.wrap("alpha beta gamma", 60, TEN).size();
        assertEquals(lines * 10, layout.slot("t").height());
        assertEquals(lines * 10, layout.height());
    }

    @Test
    void aHeadingReservesItsOwnSpaceAroundItsText() {
        // gap(8) + one line + gap(4), which is what "air above, a little below" means as arithmetic.
        Layout layout = Stack.stack().heading("TASKS").row("a", 10).build(100, TEN);

        // Looked up by position rather than by key. A heading's key is null and slot(null) is refused on
        // purpose -- a null key is never findable, which is what keeps a hit test from returning
        // something that cannot be clicked. LayoutTest asserts that refusal; here it only means asking
        // by index. The heading is the first slot, because a gap produces no slot at all.
        assertEquals(8, layout.slots().get(0).y());
        assertEquals(10, layout.slots().get(0).height());
        assertEquals(22, layout.slot("a").y());
    }

    @Test
    void aDividerTakesOnePixelBetweenItsGaps() {
        Layout layout = Stack.stack().row("a", 10).divider(6, 2).row("b", 10).build(100, TEN);

        // Also by index, for the same reason as the heading above: one pixel of rule sits between the
        // two gaps, and it is the slot in the middle that is it.
        Slot rule = layout.slots().get(1);
        assertEquals(16, rule.y());
        assertEquals(1, rule.height());
        assertFalse(rule.interactive(), "a rule is drawn, not clicked");
        assertEquals(19, layout.slot("b").y());
        assertEquals(29, layout.height());
    }

    @Test
    void aZeroWidthColumnProducesClampedSlotsRatherThanNegativeOnes() {
        // Only reachable by dragging a window to nothing, and a negative-width rectangle drawn by a
        // renderer is a rectangle drawn the wrong way round rather than an exception.
        Layout layout = Stack.stack()
                .row("a", 10)
                .block("padded", 0, 10, Stack.Align.STRETCH, Insets.symmetric(20, 0))
                .build(0, TEN);

        assertEquals(0, layout.slot("a").width());
        assertEquals(0, layout.slot("padded").width());
    }

    @Test
    void aNegativeWidthOrHeightIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Stack.stack().row("a", -1));
        assertThrows(IllegalArgumentException.class, () -> Stack.stack().block("a", -1, 5, Stack.Align.LEFT));
        assertThrows(IllegalArgumentException.class, () -> Stack.stack().build(-1, TEN));
    }

    @Test
    void aMissingMeasureIsRefusedRatherThanDereferenced() {
        assertThrows(NullPointerException.class, () -> Stack.stack().build(10, null));
    }

    @Test
    void atFindsTheTopmostInteractiveSlotAndSkipsTheRest() {
        Layout layout = Stack.stack()
                // A null key, which is what heading() itself uses: placed, takes height, not clickable.
                .text(null, "TASKS", Stack.Align.LEFT)
                .row("first", 20)
                .row("second", 20)
                .build(100, TEN);

        // The heading is one line, so "first" starts at 10 and "second" at 30. My first version asked
        // about y=25 and expected "second", which is five pixels inside "first".
        assertEquals("first", layout.at(50, 15).key());
        assertEquals("second", layout.at(50, 35).key());
        // The heading is placed but nothing can click it, so a hit test never returns it.
        assertNull(layout.at(50, 5));
        assertNull(layout.at(50, 500));
    }

    @Test
    void aKeyCanBeAnything() {
        // The key is an Object rather than a String or an enum, because the kit has no business
        // deciding how a caller names its own elements. A row keyed by its index in a list is the
        // common case, and index zero has to work -- which is why interactivity is decided by the key
        // being *null* rather than by it being falsy.
        //
        // This replaces a test that asserted "the last placed wins" against two rows of a stack, which
        // was simply wrong: a stack never places two rows in the same place. The overlap rule is real
        // but it belongs to a caller handing Layout explicit rectangles, and LayoutTest asserts it
        // there, where such a layout can actually be written down.
        record TaskRef(String id, int index) {
        }

        Layout layout = Stack.stack()
                .row(0, 20)
                .row(new TaskRef("punch_a_tree", 1), 20)
                .build(100, TEN);

        assertEquals(Integer.valueOf(0), layout.at(50, 10).key());
        assertEquals(new TaskRef("punch_a_tree", 1), layout.at(50, 30).key());
        assertNotNull(layout.slot(0));
        assertNotNull(layout.slot(new TaskRef("punch_a_tree", 1)));
    }

    @Test
    void slotLookupFindsAKeyAndRefusesANull() {
        Layout layout = Stack.stack().row("a", 10).build(10, TEN);
        assertNotNull(layout.slot("a"));
        assertNull(layout.slot("b"));
        assertThrows(NullPointerException.class, () -> layout.slot(null));
    }

    @Test
    void aLayoutCanBeBuiltTwiceFromTheSameStackWithoutDrifting() {
        // A screen rebuilds its layout on every resize and every scroll clamp, so building twice must
        // be the same answer. A builder that accumulated state would pass one build and fail the second.
        Stack stack = Stack.stack().heading("TASKS").row("a", 22).gap(4).paragraph("one two");
        Layout first = stack.build(80, TEN);
        Layout second = stack.build(80, TEN);
        assertEquals(first.slots(), second.slots());
        assertEquals(first.height(), second.height());
    }
}
