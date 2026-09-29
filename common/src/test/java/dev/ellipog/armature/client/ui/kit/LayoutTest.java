package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout, tested on hand-written slots rather than through a {@link Stack}.
 *
 * <p>Every case in {@code StackTest} goes through the builder, which means a fault in the builder can
 * mask a fault here or the two can cancel out. Building the slots directly is the only way to assert
 * what {@code Layout} does with a rectangle it was simply handed -- and the interesting cases, such as
 * two slots in the same place, are ones a well-formed stack would never produce.
 */
class LayoutTest {

    private static Layout layout(Slot... slots) {
        return Layout.of(List.of(slots));
    }

    @Test
    void heightIsTheBottomOfTheLowestSlotNotTheLastOne() {
        // A stack places in order so the last is usually the lowest. A layout that assumed that would
        // still pass every test written from a Stack, and would under-report the height of anything
        // that is placed out of order -- which is exactly what a caller passing explicit rectangles
        // does.
        Layout out = layout(
                new Slot("low", 0, 100, 10, 20),
                new Slot("high", 0, 0, 10, 10));
        assertEquals(120, out.height());
    }

    @Test
    void anEmptyLayoutIsZeroTallAndHasNoSlots() {
        Layout out = layout();
        assertEquals(0, out.height());
        assertTrue(out.isEmpty());
        assertEquals(List.of(), out.slots());
        assertNull(out.at(0, 0));
    }

    @Test
    void atSkipsWhatCannotBeClicked() {
        // A null key is a heading, a gap or a divider: placed, taking height, and not a thing the
        // player can act on. Returning it would make every caller filter the answer.
        Layout out = layout(
                new Slot(null, 0, 0, 100, 10),
                new Slot("button", 0, 10, 100, 10));
        assertNull(out.at(50, 5));
        assertEquals("button", out.at(50, 15).key());
    }

    @Test
    void atAnswersWithTheTopmostWhenTwoSlotsOverlap() {
        // Reverse order, because a stack draws in order and the last thing drawn is on top. Answering
        // with the first would make a hit test disagree with what the player can see.
        Layout out = layout(
                new Slot("under", 0, 0, 100, 100),
                new Slot("over", 0, 0, 100, 100));
        assertEquals("over", out.at(50, 50).key());
    }

    @Test
    void atIsHalfOpenOnBothFarEdges() {
        Layout out = layout(new Slot("row", 10, 20, 30, 40));
        assertTrue(out.at(10, 20).interactive());
        assertTrue(out.at(39, 59).interactive());
        assertNull(out.at(40, 30), "the right edge belongs to the next column");
        assertNull(out.at(20, 60), "the bottom edge belongs to the next row");
    }

    @Test
    void slotFindsAKeyAndRefusesANull() {
        Layout out = layout(new Slot("a", 0, 0, 10, 10), new Slot("b", 0, 10, 10, 10));
        assertEquals(10, out.slot("b").y());
        assertNull(out.slot("c"));
        // A null key is never findable by design, so asking is refused rather than answered with null
        // -- the difference between "no such slot" and "you asked the wrong question".
        assertThrows(NullPointerException.class, () -> out.slot(null));
    }

    @Test
    void slotReturnsTheFirstOfTwoUsesOfOneKey() {
        Layout out = layout(new Slot("dup", 0, 0, 10, 10), new Slot("dup", 0, 50, 10, 10));
        assertEquals(0, out.slot("dup").y());
    }

    @Test
    void visibleInIsAVerticalBandAndHalfOpenAtTheBottom() {
        Layout out = layout(
                new Slot("a", 0, 0, 10, 10),
                new Slot("b", 0, 10, 10, 10),
                new Slot("c", 0, 20, 10, 10));

        assertEquals(List.of("a", "b"), out.visibleIn(0, 20).stream().map(Slot::key).toList());
        assertEquals(List.of("b", "c"), out.visibleIn(10, 30).stream().map(Slot::key).toList());
        // A slot whose bottom edge is exactly at the band's top is not in it: nothing of it is on
        // screen, so drawing it would be drawing outside the band.
        assertEquals(List.of("c"), out.visibleIn(20, 30).stream().map(Slot::key).toList());
        assertTrue(out.visibleIn(100, 200).isEmpty());
    }

    @Test
    void visibleInViewportMovesSlotsByTheScrollBeforeTestingThem() {
        // The scrolled variant, and the difference from visibleIn is the whole reason it exists: a
        // slot at content y=100 with a scroll of 95 is at screen y=5, which is visible.
        Layout out = layout(
                new Slot("a", 0, 0, 10, 10),
                new Slot("b", 0, 100, 10, 10));

        List<Slot> scrolled = out.visibleInViewport(0, 50, 95);
        assertEquals(1, scrolled.size());
        assertEquals("b", scrolled.get(0).key());
        // Returned moved, so a caller draws it where it belongs rather than at its content position.
        assertEquals(5, scrolled.get(0).y());
    }

    @Test
    void movedKeepsOnlyInteractiveSlots() {
        // It maps keys to rectangles, and a null key cannot be a map key at all -- so headings and
        // gaps are dropped rather than silently colliding in one null entry.
        Layout out = layout(
                new Slot(null, 0, 0, 10, 10),
                new Slot("a", 0, 10, 10, 10),
                new Slot("b", 0, 20, 10, 10));
        var moved = out.moved(5, -5);
        assertEquals(2, moved.size());
        assertFalse(moved.containsKey(null));
        assertEquals(5, moved.get("a").x());
        assertEquals(5, moved.get("a").y());
        assertEquals(15, moved.get("b").y());
    }

    @Test
    void theSlotListIsImmutable() {
        Layout out = layout(new Slot("a", 0, 0, 10, 10));
        assertThrows(UnsupportedOperationException.class, () -> out.slots().add(new Slot("b", 0, 0, 1, 1)));
    }

    @Test
    void aLayoutDoesNotChangeWhenTheListItWasBuiltFromDoes() {
        // Built from a caller's mutable list, as a screen rebuilding on resize will. A defensive copy
        // is what stops a layout held across frames from being rewritten underneath its reader.
        java.util.List<Slot> source = new java.util.ArrayList<>();
        source.add(new Slot("a", 0, 0, 10, 10));
        Layout out = Layout.of(source);
        source.add(new Slot("b", 0, 10, 10, 10));
        assertEquals(1, out.slots().size());
        assertEquals(10, out.height());
    }
}
