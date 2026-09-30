package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scroll view's bar, as a control rather than as decoration.
 *
 * <h2>What this covers, and why it needs its own file</h2>
 *
 * <p>{@link ScrollView} had no test at all before this. It was the one kit class that names the game,
 * so it could not live in the game-free group — and the consequence was that the only part of it with
 * arithmetic in it, the scrollbar, was verified by looking at it. The report that came back is the
 * evidence that was not enough: <i>"make scrollbar draggable with mouse"</i>, from a build where the
 * bar drew correctly and could not be touched.
 *
 * <p>No widget is registered in any test here, deliberately. The bar's geometry depends on the
 * viewport and the scroll offset and on nothing else, so a test that needed a real control would be
 * testing {@code AbstractWidget} rather than this — and the drag mapping, which is the part that can
 * be quietly wrong, is pure arithmetic.
 */
@DisplayName("the scroll view's scrollbar")
class ScrollViewTest {

    private static final int BODY = 200;

    /**
     * A view whose content is {@code content} tall inside a {@code view} tall viewport.
     *
     * <p>The measure is never consulted — every element here is a {@code row}, whose height is declared
     * rather than measured — but {@code Stack.build} requires one, so it is written out rather than
     * passed null. Six pixels a character matches the kit's other layout tests, so arithmetic in an
     * assertion here is checkable by hand the same way.
     */
    private static ScrollView view(int view, int content) {
        ScrollView scroll = ScrollView.of(Viewport.fixed());
        scroll.viewport().bounds(0, 50, BODY, view);
        // The one call that sizes the range and places the widgets, so the content height and the
        // scrollbar can never disagree -- see its note. A `Layout` from a stack, since that is what a
        // real caller passes.
        scroll.apply(
                Stack.stack().row("a", content, Insets.NONE).build(BODY, Measure.of(text -> text.length() * 6, 9)),
                BODY);
        return scroll;
    }

    @Nested
    @DisplayName("when everything fits")
    class Fits {

        @Test
        @DisplayName("there is no bar and nothing to press")
        void nothingIsDrawn() {
            // A track with a full-height thumb in it says "there is more" and lies. The same condition
            // has to gate the hit test, or a press in the margin of a short list is swallowed by a bar
            // nobody drew -- which reads as a dead zone beside the list.
            ScrollView scroll = view(300, 100);

            assertNull(scroll.scrollbarTrack(), "no track when the content fits");
            assertNull(scroll.scrollbarThumb(), "and no thumb");
            assertFalse(scroll.scrollbarHit(202, 60),
                    "and a press in the margin belongs to nobody, so the row behind it can have it");
        }

        @Test
        @DisplayName("a drag that somehow starts does nothing rather than throwing")
        void draggingIsHarmless() {
            // Defensive rather than speculative: `beginThumbDrag` is public and a caller can reach it
            // from a state a test cannot produce, such as a drag surviving a resize that made the
            // content fit. A silent no-op is right; an exception inside a mouse handler is a crash.
            ScrollView scroll = view(300, 100);

            scroll.beginThumbDrag(60);
            scroll.dragThumbTo(120);

            assertEquals(0, scroll.viewport().scrollY(), "nothing moved, because there is no range");
        }
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {

        @Test
        @DisplayName("the track spans the viewport, just outside its right edge")
        void theTrackSpansTheViewport() {
            ScrollView scroll = view(100, 400);
            Slot track = scroll.scrollbarTrack();

            assertNotNull(track, "content four times the viewport height must have a bar");
            assertEquals(50, track.y(), "the track starts where the viewport's visible area does");
            assertEquals(100, track.height(), "and is as tall as it");
            assertTrue(track.x() >= BODY, "and sits outside the rows rather than over them");
        }

        @Test
        @DisplayName("the thumb's height is the visible fraction of the content, floored")
        void theThumbIsProportional() {
            // A quarter of the content is visible, so the thumb is a quarter of the track -- with the
            // 16-pixel floor, which exists because a 4000-row list would otherwise give a scrollbar
            // with a hairline thumb too small to aim at.
            ScrollView quarter = view(100, 400);
            assertEquals(100 * 100 / 400, quarter.scrollbarThumb().height());

            ScrollView tiny = view(100, 100_000);
            assertEquals(16, tiny.scrollbarThumb().height(),
                    "a thumb can shrink to the floor and no further");
        }

        @Test
        @DisplayName("the thumb is at the top when unscrolled and at the bottom when fully scrolled")
        void theEndsAreTheEnds() {
            // The property a proportional bar has to have: the thumb's two extremes are the track's two
            // extremes. Off by anything at either end and the bar reports a range the list does not
            // have -- either promising rows past the last one or hiding the ones that are there.
            ScrollView scroll = view(100, 400);
            Slot track = scroll.scrollbarTrack();

            assertEquals(track.y(), scroll.scrollbarThumb().y(), "at rest the thumb is at the top");

            scroll.scrollTo(scroll.viewport().maxScrollY());

            Slot bottom = scroll.scrollbarThumb();
            assertEquals(track.bottom(), bottom.bottom(),
                    "and at the end its bottom is the track's bottom: " + bottom + " in " + track);
        }
    }

    @Nested
    @DisplayName("dragging")
    class Dragging {

        @Test
        @DisplayName("pressing the middle of the thumb and dragging it down scrolls down")
        void draggingMovesTheList() {
            ScrollView scroll = view(100, 400);
            Slot thumb = scroll.scrollbarThumb();
            int middle = thumb.y() + thumb.height() / 2;

            assertEquals(0, scroll.viewport().scrollY());
            scroll.beginThumbDrag(middle);
            scroll.dragThumbTo(middle + 20);

            assertTrue(scroll.viewport().scrollY() > 0,
                    "dragging the thumb down 20 pixels should scroll the list");
        }

        @Test
        @DisplayName("the thumb stays where the pointer grabbed it, at every point in the drag")
        void theGrabOffsetIsHeld() {
            // The property that makes a hand-rolled scrollbar feel right rather than almost right. If
            // the thumb snapped to put its *top* under the pointer, grabbing its middle would make the
            // list jump on the first pixel of movement -- and a jump at the start of a drag reads as
            // the list being over-sensitive rather than as an off-by-a-grab arithmetic.
            ScrollView scroll = view(100, 400);
            Slot thumb = scroll.scrollbarThumb();
            int grabbed = thumb.y() + thumb.height() - 2;   // near the bottom edge of the thumb

            scroll.beginThumbDrag(grabbed);
            for (int y = grabbed; y <= grabbed + 40; y += 8) {
                scroll.dragThumbTo(y);
                Slot moved = scroll.scrollbarThumb();
                assertEquals(y, moved.y() + moved.height() - 2,
                        "the point grabbed should still be under the pointer at y=" + y);
            }
        }

        @Test
        @DisplayName("dragging past either end clamps rather than running off")
        void draggingClamps() {
            // A drag that keeps going after the thumb has hit the bottom must not scroll past the last
            // row. The clamp is in the drag rather than only in the viewport, because the *mapping*
            // from pointer to offset is what would otherwise produce an out-of-range number and rely on
            // somebody else to fix it.
            ScrollView scroll = view(100, 400);
            scroll.beginThumbDrag(scroll.scrollbarThumb().y() + 2);

            scroll.dragThumbTo(9_999);
            assertEquals(scroll.viewport().maxScrollY(), scroll.viewport().scrollY(),
                    "dragged far past the bottom, the list is at its end and no further");

            scroll.dragThumbTo(-9_999);
            assertEquals(0, scroll.viewport().scrollY(),
                    "and dragged far past the top, it is at its start");
        }

        @Test
        @DisplayName("moves before the drag begins are ignored, and a doubled release is harmless")
        void theDragHasToStart() {
            // `dragThumbTo` is called from `mouseDragged`, which also fires for a canvas pan. Without
            // the guard, panning the graph would scroll the sidebar -- which is the same fault the
            // region routing in `mouseScrolled` exists to prevent, arriving by a different route.
            ScrollView scroll = view(100, 400);

            scroll.dragThumbTo(200);
            assertEquals(0, scroll.viewport().scrollY(), "no drag in progress, so nothing moved");

            scroll.beginThumbDrag(60);
            assertTrue(scroll.draggingThumb());
            assertTrue(scroll.endThumbDrag(), "the first release ends the drag");
            assertFalse(scroll.endThumbDrag(), "and the second reports that there was nothing to end");
            assertFalse(scroll.draggingThumb());
        }

        @Test
        @DisplayName("a press in the grab band starts a drag; one in the rows does not")
        void theGrabBandIsOutsideTheRows() {
            // The two halves of the routing decision, asserted against the same rectangle the drawing
            // uses. A band that reached *into* the viewport would take clicks from the last few pixels
            // of every row, which is a bug nobody would connect to the scrollbar.
            ScrollView scroll = view(100, 400);
            Slot track = scroll.scrollbarTrack();

            assertTrue(scroll.scrollbarHit(track.x() + 1, track.y() + 10),
                    "on the bar itself");
            assertTrue(scroll.scrollbarHit(track.right() + 2, track.y() + 10),
                    "and a little past it, because three pixels is a target you miss");
            assertFalse(scroll.scrollbarHit(BODY - 1, track.y() + 10),
                    "but not over the rows: a press there belongs to a row");
            assertFalse(scroll.scrollbarHit(track.x() + 1, track.y() - 10),
                    "and not above the track, which is the header");
            assertFalse(scroll.scrollbarHit(track.x() + 1, track.bottom() + 10),
                    "nor below it, which is the panel's edge");
        }
    }
}
