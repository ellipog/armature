package dev.ellipog.armature.client.ui.kit;

import dev.ellipog.armature.client.render.RecordingRenderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scrollbar, as a control rather than as decoration.
 *
 * <h2>Where this file came from</h2>
 *
 * <p>It is {@code ScrollViewTest}, moved. The bar used to live in {@link ScrollView} and this was the
 * only test of it, written after a report that arrived from a build where the bar drew correctly and
 * could not be touched: <i>"make scrollbar draggable with mouse"</i>. The bar is its own class now
 * because the screens that draw lists had copied it three times, so the tests moved with it — a test
 * that stays behind when the code moves is a test of a copy — and the fixture lost its {@code Stack}
 * and its {@code Layout}, because a bar with no widgets in it never needed either. What the fixture
 * gained instead is the distinction the copying had blurred: a bar <b>outside</b> its region, which is
 * every {@link ScrollView}'s, and a bar <b>inside</b> one, which is what a list whose layout carved a
 * strip out of its own width draws.
 *
 * <h2>What is asserted here, and what is not</h2>
 *
 * <p>Geometry, the two gestures, the repeat, the wheel, and the three appearances. Every one of them
 * is arithmetic or a drawn rectangle, so none of it needs a client: the appearances are read back off
 * {@link RecordingRenderer}, which is the second implementation of the drawing seam and the reason a
 * hover can be asserted at all. What is <i>not</i> here is the routing — which bar a press at a point
 * belongs to is the screen's, and a screen needs a running game.
 */
@DisplayName("the scrollbar")
class ScrollBarTest {

    private static final int BODY = 200;

    /** The two colours a state test can tell apart without a theme. */
    private static final ScrollBar.Skin SKIN =
            new ScrollBar.Skin(0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444);

    /**
     * A bar whose region is {@code view} tall with {@code content} of content in it, outside the region.
     *
     * <p>Six pixels a character and ten a line would be the kit's other layout tests' yardstick, and
     * there is nothing here to measure: a viewport's content height is a number the caller states.
     */
    private static ScrollBar bar(int view, int content) {
        return new ScrollBar(viewport(view, content)).stripOutsideViewport();
    }

    private static Viewport viewport(int view, int content) {
        Viewport viewport = Viewport.fixed().bounds(0, 50, BODY, view);
        viewport.setContentSize(BODY, content);
        return viewport;
    }

    /** A mid-groove x that lands in the grab band but not on a row. */
    private static final double ON_BAR = BODY + ScrollBar.OFFSET + 1;

    @BeforeEach
    @AfterEach
    void motionOff() {
        // Both, for the reason `ThemeTest` gives: `Motion` is process-wide, so a test that leaves the
        // client's animation switched off makes the next one pass or fail on the order it ran in — and
        // the second annotation is what covers a test that never reached this one's first run.
        Motion.setEnabled(false);
    }

    @Nested
    @DisplayName("when everything fits")
    class Fits {

        @Test
        @DisplayName("there is no bar and nothing to press")
        void nothingIsDrawn() {
            // A track with a full-height grip in it says "there is more" and lies. The same condition
            // has to gate the hit test, or a press in the margin of a short list is swallowed by a bar
            // nobody drew -- which reads as a dead zone beside the list.
            ScrollBar bar = bar(300, 100);

            assertNull(bar.track(), "no track when the content fits");
            assertNull(bar.thumb(), "and no thumb");
            assertFalse(bar.hit(ON_BAR, 60),
                    "and a press in the margin belongs to nobody, so the row behind it can have it");
            assertFalse(bar.press(ON_BAR, 60, 0L), "so it starts no gesture either");
            assertFalse(bar.held());
        }

        @Test
        @DisplayName("a drag that somehow starts does nothing rather than throwing")
        void draggingIsHarmless() {
            // Defensive rather than speculative: `press` is public and a caller can reach it from a
            // state a test cannot produce, such as a drag surviving a resize that made the content
            // fit. A silent no-op is right; an exception inside a mouse handler is a crash.
            ScrollBar bar = bar(300, 100);

            bar.press(ON_BAR, 60, 0L);
            bar.dragTo(120);

            assertEquals(0, bar.viewport().scrollY(), "nothing moved, because there is no range");
        }
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {

        @Test
        @DisplayName("the track spans the viewport, just outside its right edge")
        void theTrackSpansTheViewport() {
            ScrollBar bar = bar(100, 400);
            Slot track = bar.track();

            assertNotNull(track, "content four times the viewport height must have a bar");
            assertEquals(50, track.y(), "the track starts where the viewport's visible area does");
            assertEquals(100, track.height(), "and is as tall as it");
            assertTrue(track.x() >= BODY, "and sits outside the rows rather than over them");
        }

        @Test
        @DisplayName("the thumb's height is the visible fraction of the content, floored")
        void theThumbIsProportional() {
            // A quarter of the content is visible, so the thumb is a quarter of the track -- with the
            // floor, which exists because a 4000-row list would otherwise give a scrollbar with a
            // hairline thumb too small to aim at.
            assertEquals(100 * 100 / 400, bar(100, 400).thumb().height());

            assertEquals(ScrollBar.MIN_THUMB, bar(100, 100_000).thumb().height(),
                    "a thumb can shrink to the floor and no further");
        }

        @Test
        @DisplayName("the thumb is at the top when unscrolled and at the bottom when fully scrolled")
        void theEndsAreTheEnds() {
            // The property a proportional bar has to have: the thumb's two extremes are the track's two
            // extremes. Off by anything at either end and the bar reports a range the list does not
            // have -- either promising rows past the last one or hiding the ones that are there.
            ScrollBar bar = bar(100, 400);
            Slot track = bar.track();

            assertEquals(track.y(), bar.thumb().y(), "at rest the thumb is at the top");

            bar.viewport().setScrollY(bar.viewport().maxScrollY());

            Slot bottom = bar.thumb();
            assertEquals(track.bottom(), bottom.bottom(),
                    "and at the end its bottom is the track's bottom: " + bottom + " in " + track);
        }

        @Test
        @DisplayName("a bar inside the region never takes a press from the rows beside it")
        void anInsideBarStopsAtItsOwnStrip() {
            // The lists that draw their strip inside the region rather than beside it want the band to
            // stop there, so the pixels to its left -- the last few of every row -- stay the rows'.
            Viewport viewport = viewport(100, 400);
            ScrollBar inside = new ScrollBar(viewport).strip(BODY - 3, 50, 3, 100);

            assertTrue(inside.hit(BODY - 2, 60), "on the strip itself");
            assertFalse(inside.hit(BODY - 5, 60), "but not over the rows, which end at the strip");
            assertTrue(inside.hit(BODY + 4, 60),
                    "and still generously past the region's edge, where there is nothing to steal");
        }
    }

    @Nested
    @DisplayName("dragging")
    class Dragging {

        @Test
        @DisplayName("pressing the middle of the thumb and dragging it down scrolls down")
        void draggingMovesTheList() {
            ScrollBar bar = bar(100, 400);
            Slot thumb = bar.thumb();
            int middle = thumb.y() + thumb.height() / 2;

            assertEquals(0, bar.viewport().scrollY());
            assertTrue(bar.press(thumb.x() + 1, middle, 0L), "the press is the bar's");
            assertEquals(ScrollBar.State.DRAGGING, bar.state());
            bar.dragTo(middle + 20);

            assertTrue(bar.viewport().scrollY() > 0,
                    "dragging the thumb down 20 pixels should scroll the list");
        }

        @Test
        @DisplayName("the thumb stays where the pointer grabbed it, at every point in the drag")
        void theGrabOffsetIsHeld() {
            // The property that makes a hand-rolled scrollbar feel right rather than almost right. If
            // the thumb snapped to put its *top* under the pointer, grabbing its middle would make the
            // list jump on the first pixel of movement -- and a jump at the start of a drag reads as
            // the list being over-sensitive rather than as an off-by-a-grab arithmetic.
            ScrollBar bar = bar(100, 400);
            Slot thumb = bar.thumb();
            int grabbed = thumb.y() + thumb.height() - 2;   // near the bottom edge of the thumb

            bar.press(thumb.x() + 1, grabbed, 0L);
            for (int y = grabbed; y <= grabbed + 40; y += 8) {
                bar.dragTo(y);
                Slot moved = bar.thumb();
                assertEquals(y, moved.y() + moved.height() - 2,
                        "the point grabbed should still be under the pointer at y=" + y);
            }
        }

        @Test
        @DisplayName("the grab survives the pointer leaving the track, and clamps at both ends")
        void draggingClamps() {
            // Both halves of "global capture" in the arithmetic that can hold it: a pointer far outside
            // the strip is still dragging -- the gesture is the button, not the pointer's position --
            // and the offset it produces is the track's travel rather than what the pointer asked for.
            // Without the clamp the list would need somebody else to fix it, and the somebody is the
            // drawing pass, which is where this fault used to live.
            ScrollBar bar = bar(100, 400);
            Slot thumb = bar.thumb();
            bar.press(thumb.x() + 1, thumb.y() + 2, 0L);

            bar.dragTo(9_999);
            assertEquals(bar.viewport().maxScrollY(), bar.viewport().scrollY(),
                    "dragged far past the bottom, the list is at its end and no further");

            bar.dragTo(-9_999);
            assertEquals(0, bar.viewport().scrollY(),
                    "and dragged far past the top, it is at its start");
        }

        @Test
        @DisplayName("moves before the drag begins are ignored, and a doubled release is harmless")
        void theDragHasToStart() {
            // `dragTo` is called from `mouseDragged`, which also fires for a canvas pan. Without the
            // guard, panning the graph would scroll the sidebar -- which is the same fault the region
            // routing in `mouseScrolled` exists to prevent, arriving by a different route.
            ScrollBar bar = bar(100, 400);

            bar.dragTo(200);
            assertEquals(0, bar.viewport().scrollY(), "no drag in progress, so nothing moved");

            Slot thumb = bar.thumb();
            bar.press(thumb.x() + 1, thumb.y() + 2, 0L);
            assertTrue(bar.held());
            assertTrue(bar.release(), "the first release ends the drag");
            assertFalse(bar.release(), "and the second reports that there was nothing to end");
            assertFalse(bar.held());
        }

        @Test
        @DisplayName("a press in the grab band starts a drag; one in the rows does not")
        void theGrabBandIsOutsideTheRows() {
            // The two halves of the routing decision, asserted against the same rectangle the drawing
            // uses. A band that reached *into* the viewport would take clicks from the last few pixels
            // of every row, which is a bug nobody would connect to the scrollbar.
            ScrollBar bar = bar(100, 400);
            Slot track = bar.track();

            assertTrue(bar.hit(track.x() + 1, track.y() + 10), "on the bar itself");
            assertTrue(bar.hit(track.right() + 2, track.y() + 10),
                    "and a little past it, because three pixels is a target you miss");
            assertFalse(bar.hit(BODY - 1, track.y() + 10),
                    "but not over the rows: a press there belongs to a row");
            assertFalse(bar.hit(track.x() + 1, track.y() - 10),
                    "and not above the track, which is the header");
            assertFalse(bar.hit(track.x() + 1, track.bottom() + 10),
                    "nor below it, which is the panel's edge");
        }
    }

    @Nested
    @DisplayName("pressing the groove")
    class Gutter {

        @Test
        @DisplayName("a press below the grip pages down, and one above it pages up")
        void aPressPagesTowardThePointer() {
            // The gesture the spec asks for: the pointer's side of the grip is the direction, and it
            // moves by a page rather than jumping the grip under the pointer. One screen less a row,
            // so the row at the top of the new page is one the reader has already seen.
            ScrollBar bar = bar(100, 400);
            assertEquals(70, bar.pageStep(), "a hundred-pixel region less the thirty-pixel pitch");

            assertTrue(bar.press(ON_BAR, 140, 0L), "below the grip, which is at the top");
            assertEquals(ScrollBar.State.GUTTER, bar.state());
            assertEquals(70, bar.viewport().scrollY(), "paged down by exactly one page");

            bar.release();
            bar.viewport().setScrollY(bar.viewport().maxScrollY());
            Slot grip = bar.thumb();
            assertTrue(bar.press(ON_BAR, grip.y() - 5, 0L), "now above the grip, which is at the bottom");
            assertTrue(bar.viewport().scrollY() < 300, "paged up");
        }

        @Test
        @DisplayName("paging stops at the ends rather than running past them")
        void aPageNeverOvershoots() {
            // A page is a scroll like any other and the viewport's clamp is the only one -- the fault
            // this replaces was an offset written unclamped in one place and corrected in the drawing
            // pass, which is a range nobody can state.
            ScrollBar bar = bar(100, 400);
            bar.viewport().setScrollY(bar.viewport().maxScrollY());
            Slot grip = bar.thumb();

            bar.press(ON_BAR, grip.y() - 5, 0L);   // up, from the bottom
            bar.release();
            assertEquals(230, bar.viewport().scrollY(), "clamped at nothing rather than at minus seventy");

            // And down, repeatedly, from wherever the grip now is: a press has to be off the grip to be
            // the groove's, so the row to aim at moves with the scroll.
            Slot now = bar.thumb();
            bar.press(ON_BAR, now.bottom() + 5, 0L);
            for (int i = 0; i < 20; i++) {
                bar.advance(1_000L + i * (ScrollBar.REPEAT_DELAY_MILLIS + ScrollBar.REPEAT_INTERVAL_MILLIS));
            }
            assertEquals(300, bar.viewport().scrollY(), "and the far end is the far end");
        }

        @Test
        @DisplayName("holding advances continuously, after a delay that a click does not outlast")
        void holdingRepeats() {
            // Minecraft has no mouse auto-repeat, so this is driven from the frame -- which is why the
            // time is a parameter and this test advances it by passing larger numbers rather than by
            // sleeping. The delay is what keeps a single click a single page.
            ScrollBar bar = bar(300, 1400);     // pageStep = 300 - 30 = 270, and room for three of them
            assertTrue(bar.press(ON_BAR, 340, 1_000L), "below the grip");
            int afterPress = bar.viewport().scrollY();
            assertEquals(270, afterPress);

            assertFalse(bar.advance(1_000L + ScrollBar.REPEAT_DELAY_MILLIS - 1),
                    "nothing yet: the delay has not elapsed");
            assertTrue(bar.advance(1_000L + ScrollBar.REPEAT_DELAY_MILLIS), "the first repeat");
            assertFalse(bar.advance(1_000L + ScrollBar.REPEAT_DELAY_MILLIS + 1),
                    "and not again a millisecond later");
            assertTrue(bar.advance(1_000L + ScrollBar.REPEAT_DELAY_MILLIS
                    + ScrollBar.REPEAT_INTERVAL_MILLIS), "but at the interval it advances again");
            assertEquals(270 * 3, bar.viewport().scrollY());

            bar.release();
            int stopped = bar.viewport().scrollY();
            assertFalse(bar.advance(9_999_999L), "a released groove does not keep paging");
            assertEquals(stopped, bar.viewport().scrollY());
        }

        @Test
        @DisplayName("a press outside the band starts nothing at all")
        void aPressElsewhereIsNotTheBars() {
            // The other half of the routing contract: the band is generous, not unlimited, and a press
            // it does not take has to be left for whatever is behind the bar.
            ScrollBar bar = bar(100, 400);

            assertFalse(bar.press(BODY - 1, 60, 0L), "on a row");
            assertFalse(bar.press(ON_BAR, 20, 0L), "above the track");
            assertFalse(bar.press(ON_BAR + 40, 60, 0L), "past the band's far edge");
            assertFalse(bar.held());
        }
    }

    @Nested
    @DisplayName("the wheel")
    class Wheel {

        @Test
        @DisplayName("a whole notch moves one pitch, and down means down")
        void oneNotchOnePitch() {
            // The sign is the part worth pinning: Minecraft sends a positive delta for a wheel turned
            // towards the user, and the list has to move up for it. Written once here rather than at
            // the eighteen sites this replaces, each of which spelled `-(int)(scrollY * 30)` itself.
            ScrollBar bar = bar(100, 400);
            bar.viewport().setScrollY(150);

            bar.wheel(1, 30);
            assertEquals(120, bar.viewport().scrollY(), "a notch forwards scrolls up");

            bar.wheel(-1, 30);
            assertEquals(150, bar.viewport().scrollY(), "and back down again");
        }

        @Test
        @DisplayName("fractions accumulate, so a trackpad's small deltas are not thrown away")
        void fractionsAccumulate() {
            // Minecraft passes the delta through untouched when `discreteMouseScroll` is off, so a
            // smooth wheel or a trackpad arrives as a run of fractions. `(int) (0.5 * 30)` is zero, and
            // eighteen copies of that expression is a list that does not move at all under a light
            // two-finger swipe -- which is a report about the mouse that is really about truncation.
            ScrollBar bar = bar(100, 400);
            bar.viewport().setScrollY(300);

            bar.wheel(0.5, 30);
            assertEquals(300, bar.viewport().scrollY(), "half a notch is not a notch");
            bar.wheel(0.5, 30);
            assertEquals(270, bar.viewport().scrollY(), "two halves are");
            bar.wheel(-0.5, 30);
            assertEquals(270, bar.viewport().scrollY(), "and a reversal does not pay off the fraction");
            bar.wheel(-0.5, 30);
            assertEquals(300, bar.viewport().scrollY(), "it starts again from the other side");

            assertEquals(0, bar.notches(0), "a wheel that did not move is not a notch");
        }

        @Test
        @DisplayName("a list with rows passes its own pitch, so a notch lands on a row")
        void thePitchIsTheLists() {
            ScrollBar bar = bar(100, 400);
            bar.viewport().setScrollY(300);
            bar.pitch(18);

            bar.wheel(1);
            assertEquals(282, bar.viewport().scrollY(), "one row, not the default thirty pixels");

            assertEquals(100 - 18, bar.pageStep(), "and a page keeps one of those rows on screen");
        }

        @Test
        @DisplayName("the wheel loses to a drag rather than fighting it")
        void aDragOwnsTheBar() {
            // The canvas already taught this lesson the hard way -- a wheel notch mid-drag, undone by
            // the next mouse move, once per notch. The bar could be made to behave that way too, by
            // retargeting the grab on every notch; refusing is one line and cannot fight.
            ScrollBar bar = bar(100, 400);
            Slot thumb = bar.thumb();
            bar.press(thumb.x() + 1, thumb.y() + 2, 0L);
            bar.dragTo(thumb.y() + 12);
            int dragged = bar.viewport().scrollY();

            bar.wheel(-1, 30);
            assertEquals(dragged, bar.viewport().scrollY(), "the drag holds the bar");

            bar.release();
            bar.wheel(-1, 30);
            assertEquals(dragged + 30, bar.viewport().scrollY(), "and the wheel has it back afterwards");
        }
    }

    @Nested
    @DisplayName("telling its owner")
    class Telling {

        @Test
        @DisplayName("every write that moves the offset says so, and a write that does not stays quiet")
        void everyMoveTellsTheOwner() {
            // The hook is how a `ScrollView` learns that its children need re-placing, and it is the one
            // thing that stands between "the list scrolled" and "the widgets in it scrolled". It fires
            // per *move* rather than per call, so a wheel at the end of a list does not re-place the
            // children of a list that did not move -- which on a held groove would be every frame.
            ScrollBar bar = bar(100, 400);
            int[] told = {0};
            bar.onScrolled(() -> told[0]++);

            bar.wheel(-1, 30);
            assertEquals(1, told[0], "a wheel that moved says so");

            bar.viewport().setScrollY(0);
            told[0] = 0;
            bar.wheel(0, 30);
            assertEquals(0, told[0], "and a wheel that did not move says nothing");

            Slot grip = bar.thumb();
            bar.press(grip.x() + 1, grip.y() + 2, 0L);
            told[0] = 0;
            bar.dragTo(grip.y() + 12);
            assertEquals(1, told[0], "a drag that moved says so");

            bar.dragTo(grip.y() + 12);
            assertEquals(1, told[0], "and a drag that did not move again says nothing more");

            bar.release();
            bar.viewport().setScrollY(0);
            told[0] = 0;
            assertTrue(bar.press(ON_BAR, 140, 0L), "a press on the groove, below the grip");
            assertEquals(1, told[0], "a page that moved says so");
        }

        @Test
        @DisplayName("a bar with no owner scrolls a bare offset, which is what a drawn list wants")
        void noOwnerIsFine() {
            // A list whose rows are drawn rather than hosted has no widgets to place: they are read from
            // `scrollY()` every frame. So the hook is optional, and a bar without one must not throw.
            ScrollBar bar = bar(100, 400);

            bar.wheel(-1, 30);
            assertEquals(30, bar.viewport().scrollY(), "the offset moved with nobody listening");
        }
    }

    @Nested
    @DisplayName("appearance")
    class Appearance {

        private static final long NOW = 1_000L;

        /** The bar's fill colours, in the order they were asked for. */
        private static List<Integer> gripFills(RecordingRenderer renderer) {
            // The track is the first fill and the grip the second, which is the order the drawing is
            // written in; a gutter press adds a wash between them and a held grip a core after.
            return renderer.fills().stream().map(RecordingRenderer.Call::argb).toList();
        }

        @Test
        @DisplayName("the track is drawn, and the resting grip is the theme's own colour")
        void idle() {
            RecordingRenderer renderer = RecordingRenderer.create();
            ScrollBar bar = bar(100, 400);

            bar.draw(renderer, SKIN, -100, -100, NOW);

            Slot track = bar.track();
            assertTrue(renderer.covered(track.x(), track.y() + 2), "the groove is drawn");
            assertEquals(List.of(SKIN.track(), SKIN.thumb()), gripFills(renderer),
                    "and the grip in the thumb colour, flat");
            assertEquals(ScrollBar.State.IDLE, bar.state());
        }

        @Test
        @DisplayName("a hovered grip is a shade of the same colour, not a new one")
        void hovered() {
            RecordingRenderer renderer = RecordingRenderer.create();
            ScrollBar bar = bar(100, 400);

            bar.draw(renderer, SKIN, ON_BAR, 60, NOW);

            assertEquals(ScrollBar.State.HOVER, bar.state());
            assertEquals(List.of(SKIN.track(), SKIN.thumbHover()), gripFills(renderer),
                    "one fill for the grip, at the hovered colour");
            assertEquals(1F, bar.hoverAmount(NOW), "and the ease has arrived, because motion is off");
        }

        @Test
        @DisplayName("a held grip is a different colour again, with a notch down its middle")
        void held() {
            // Three states that cannot be confused, which is what "distinct feedback" has to mean.
            // The grip is held rather than merely wider, deliberately: every container that draws a bar
            // reserved room for a three-pixel one, and widening it would draw over content in every
            // layout whose arithmetic was not re-derived for it.
            RecordingRenderer renderer = RecordingRenderer.create();
            ScrollBar bar = bar(100, 400);
            Slot thumb = bar.thumb();

            bar.press(thumb.x() + 1, thumb.y() + 2, NOW);
            renderer.reset();       // the press paged nothing, but the recording should start clean
            bar.draw(renderer, SKIN, thumb.x() + 1, thumb.y() + 2, NOW);

            assertEquals(ScrollBar.State.DRAGGING, bar.state());
            assertEquals(List.of(SKIN.track(), SKIN.thumbActive(), SKIN.track()), gripFills(renderer),
                    "the groove, the held grip, and the notch cut into it");
            assertEquals(0F, bar.hoverAmount(NOW), "a held bar is not a hovered one");
        }

        @Test
        @DisplayName("a held groove lights up as well as its grip")
        void heldGroove() {
            RecordingRenderer renderer = RecordingRenderer.create();
            ScrollBar bar = bar(100, 400);

            bar.press(ON_BAR, 140, NOW);
            renderer.reset();
            bar.draw(renderer, SKIN, ON_BAR, 140, NOW);

            assertEquals(ScrollBar.State.GUTTER, bar.state());
            assertEquals(4, renderer.fills().size(),
                    "the groove, its wash, the grip, and the notch: " + renderer.fills());
            assertEquals(SKIN.track(), gripFills(renderer).get(0));
            assertEquals(SKIN.thumbActive(), gripFills(renderer).get(2));
        }

        @Test
        @DisplayName("a bar with nothing to scroll draws nothing, however it is pointed at")
        void nothingWhenItFits() {
            RecordingRenderer renderer = RecordingRenderer.create();

            bar(300, 100).draw(renderer, SKIN, ON_BAR, 60, NOW);

            assertEquals(List.of(), renderer.calls(), "no groove, no grip, and no state left behind");
        }
    }
}
