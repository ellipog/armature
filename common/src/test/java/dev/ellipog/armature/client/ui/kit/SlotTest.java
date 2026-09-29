package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placed rectangle, and the two decisions in it that every other class in this kit depends on.
 *
 * <p>Both are small enough to look like details and neither is. The half-open interval is what makes
 * two adjacent rows unambiguous, and the null key is what makes "drawn but not clickable" expressible
 * at all — every layout, every hit test and the whole scroll view are written on top of those two
 * answers, so they are asserted here rather than assumed everywhere else.
 */
class SlotTest {

    @Test
    void theFarEdgesAreOutside() {
        Slot slot = new Slot("row", 10, 20, 30, 40);

        assertTrue(slot.contains(10, 20), "the near corner is inside");
        assertTrue(slot.contains(39, 59), "one pixel short of the far corner is inside");

        assertFalse(slot.contains(40, 30), "the right edge is not");
        assertFalse(slot.contains(20, 60), "the bottom edge is not");
        assertFalse(slot.contains(9, 30));
        assertFalse(slot.contains(20, 19));
    }

    @Test
    void adjacentSlotsDoNotBothClaimTheSharedEdge() {
        // The reason for the half-open interval, stated as the thing it is for: a click on the boundary
        // between two rows belongs to exactly one of them, and the same one every time.
        Slot above = new Slot("above", 0, 0, 10, 10);
        Slot below = new Slot("below", 0, 10, 10, 10);

        assertTrue(above.contains(5, 9));
        assertFalse(above.contains(5, 10));
        assertTrue(below.contains(5, 10));
    }

    @Test
    void aNullKeyMeansDrawnButNotInteractive() {
        assertFalse(new Slot(null, 0, 0, 10, 10).interactive());
        assertTrue(new Slot("button", 0, 0, 10, 10).interactive());
        // Zero, not null. A key of 0 is a perfectly good index, which is why the key is an Object and
        // why interactivity is decided by nullness rather than by falsiness.
        assertTrue(new Slot(0, 0, 0, 10, 10).interactive());
    }

    @Test
    void movedKeepsEverythingButThePosition() {
        Slot moved = new Slot("row", 10, 20, 30, 40).moved(-5, 7);
        assertEquals(5, moved.x());
        assertEquals(27, moved.y());
        assertEquals(30, moved.width());
        assertEquals(40, moved.height());
        assertEquals("row", moved.key());
    }

    @Test
    void rightAndBottomArePastTheFarEdge() {
        Slot slot = new Slot("row", 10, 20, 30, 40);
        assertEquals(40, slot.right());
        assertEquals(60, slot.bottom());
    }

    @Test
    void aZeroSizedSlotContainsNothingButStillHasEdges() {
        // A column narrowed to nothing produces these, and they must not behave as though they cover a
        // pixel: rows that have collapsed are not clickable.
        Slot slot = new Slot("collapsed", 5, 5, 0, 0);
        assertFalse(slot.contains(5, 5));
        assertEquals(5, slot.right());
        assertEquals(5, slot.bottom());
    }
}
