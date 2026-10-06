package dev.ellipog.armature.client.ui.kit;

import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTheme;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placement half of {@link ScrollView}: the widgets inside a region follow its scroll.
 *
 * <h2>Why this file exists, and why it did not</h2>
 *
 * <p>It is the test that was missing, and its absence is worth recording rather than tidying away. The
 * bar's arithmetic lived in {@code ScrollView} once and moved to {@link ScrollBar}, and its tests moved
 * with it — leaving <b>nothing</b> testing what a scroll does to the widgets, on the stated grounds that
 * a test of that "needs {@code AbstractWidget}s and so a client". That is not true: {@code
 * ArmatureButtonTest} builds buttons with no client at all, and a button is all this needs.
 *
 * <p>What the gap cost is the reason the file is written now. Moving the offset writes into the bar
 * dropped the old {@code scrollBy}/{@code scrollTo} guarantee that the children are re-placed, and
 * nothing noticed: the offset moved, the drawn rows moved, and the widgets stayed exactly where they
 * were. In the consumer that found it, exactly two of nine views both register widgets *and* place them
 * only during a rebuild — a tools panel's fields and buttons, and a sidebar's rows — and the other seven
 * hid the fault. So the first test here is the one that would have caught it, and it is written against
 * the <i>bar</i> rather than against {@code scrollBy}, because the bar is the way a screen actually
 * scrolls a list now.
 */
@DisplayName("a scroll view's widgets")
class ScrollViewTest {

    private static final int BODY = 100;

    /** The region is 100 wide and 50 tall, and the content is 80: thirty pixels of scroll, exactly. */
    private static final int VIEW_HEIGHT = 50;
    private static final int CONTENT_HEIGHT = 80;
    private static final int SCROLL_RANGE = CONTENT_HEIGHT - VIEW_HEIGHT;

    @BeforeEach
    @AfterEach
    void resetTheme() {
        // `ArmatureButton` reads the theme when it is *drawn*, and nothing here draws -- but a test that
        // depends on which theme another test left in force is a test that fails in a different order.
        ArmatureTheme.resetCurrent();
    }

    private static Measure measure() {
        return Measure.of(text -> text.length() * 6, 9);
    }

    /** Four rows of twenty, so the content is taller than the region and a scroll is real. */
    private static Layout fourRows() {
        return Stack.stack()
                .row("a", 20, Insets.NONE)
                .row("b", 20, Insets.NONE)
                .row("c", 20, Insets.NONE)
                .row("d", 20, Insets.NONE)
                .build(BODY, measure());
    }

    private static ArmatureButton button() {
        return new ArmatureButton(0, 0, BODY, 20, Component.literal("row"), () -> {
        });
    }

    /**
     * A view with a button in every row, placed.
     *
     * <p>The region starts at y = 50 rather than 0 on purpose: a placement that ignored the origin would
     * put every widget at the content's own y, and a fixture at the origin could not tell the difference.
     */
    private static ScrollView view() {
        ScrollView view = ScrollView.of(Viewport.fixed());
        view.viewport().bounds(0, 50, BODY, VIEW_HEIGHT);
        view.put("a", button()).put("b", button()).put("c", button()).put("d", button());
        view.apply(fourRows(), BODY);
        return view;
    }

    /** Where a row at content y 20 lands, at the scroll in force. */
    private static int expectedRowY(ScrollView view) {
        return view.viewport().screenY(20);
    }

    @Nested
    @DisplayName("placement")
    class Placement {

        @Test
        @DisplayName("a widget is placed at its slot's on-screen position")
        void widgetsLandOnTheirSlots() {
            ScrollView view = view();

            assertEquals(50, view.get("a").getY(), "the first row starts at the region's top");
            assertEquals(70, view.get("b").getY(), "and the next is one row down");
            assertEquals(BODY, view.get("b").getWidth(), "sized to its slot too");
            // Three of the four fit: the region is fifty tall and the rows start at its top, so the
            // fourth is at y 110 -- past the bottom at 100 -- and the cull is what says so.
            assertEquals(3, view.placed(), "the rows that fit are placed");
            assertEquals(1, view.culled(), "and the one that does not is culled");
        }

        @Test
        @DisplayName("a widget scrolled out of the region is hidden, not left where it was")
        void theCullHidesWhatIsOffScreen() {
            // Hiding is a correctness property rather than an optimisation: `AbstractWidget.isMouseOver`
            // is false for an invisible widget, so a control scrolled out of the region cannot be clicked
            // where it used to be drawn.
            ScrollView view = view();

            view.scrollTo(SCROLL_RANGE);

            assertFalse(view.get("a").visible, "the first row is past the top of the region");
            assertTrue(view.get("d").visible, "while the last one is in view");
            assertTrue(view.culled() > 0, "the cull ran rather than being skipped");
            assertTrue(view.placed() > 0, "and it placed the ones that are on screen");
        }

        @Test
        @DisplayName("a widget the layout has no slot for is hidden")
        void aWidgetWithNoSlotIsHidden() {
            // The rule that stops a control from a previous entry -- Submit for a task that no longer
            // exists -- from lingering on screen, invisible but clickable.
            ScrollView view = view();
            view.put("gone", button());

            view.apply(fourRows(), BODY);

            assertFalse(view.get("gone").visible, "a widget with no slot is not drawn");
            assertNull(view.placedSlot("gone"), "and asking where it is answers nothing");
        }

        @Test
        @DisplayName("a widget registered after the last placement is hidden until the next one")
        void aLateWidgetIsHidden() {
            // The safe direction: a control added but not yet placed is not drawn where the last layout
            // happened to leave room for something else.
            ScrollView view = view();
            ArmatureButton late = button();

            view.put("late", late);

            assertFalse(late.visible, "a widget added after `apply` waits for the next one");
        }
    }

    @Nested
    @DisplayName("the scroll moves the widgets")
    class Scrolling {

        @Test
        @DisplayName("a wheel through the bar moves the widgets, not only the offset")
        void aWheelThroughTheBarMovesTheWidgets() {
            // **The regression test.** The offset and the widgets are two things, and moving one without
            // the other is a list whose rows scroll under controls that stay still -- which is what the
            // tools panel and the sidebar did, because both place their widgets only in a rebuild.
            ScrollView view = view();
            int before = view.get("b").getY();

            view.bar().wheel(-1);

            assertEquals(SCROLL_RANGE, view.viewport().scrollY(), "one notch down, clamped at the end");
            assertEquals(before - SCROLL_RANGE, view.get("b").getY(),
                    "and the widget moved with the scroll the bar applied");
            assertEquals(expectedRowY(view), view.get("b").getY(),
                    "which is the viewport's own answer, not a second one");
        }

        @Test
        @DisplayName("a drag through the bar moves the widgets too")
        void aDragThroughTheBarMovesTheWidgets() {
            ScrollView view = view();
            Slot grip = view.bar().thumb();
            assertNotNull(grip, "the fixture has to overflow, or there is no grip to drag");
            assertTrue(view.bar().press(grip.x() + 1, grip.y() + 2, 0L), "the grip takes the press");

            int before = view.get("b").getY();
            view.bar().dragTo(grip.y() + 12);

            assertTrue(view.viewport().scrollY() > 0, "the drag scrolled the list");
            assertEquals(before - view.viewport().scrollY(), view.get("b").getY(),
                    "and every widget followed it");
        }

        @Test
        @DisplayName("a press on the groove moves the widgets with the page it takes")
        void aGroovePressMovesTheWidgets() {
            ScrollView view = view();
            Slot track = view.bar().track();
            assertNotNull(track, "the fixture has to overflow, or there is no groove");
            assertTrue(view.bar().press(track.x() + 1, track.bottom() - 1, 0L), "below the grip");

            // A page is the region less one pitch, not the whole region: 50 - 30 = 20. The row that
            // carried over is the point of it -- the page after a page starts with something already seen.
            assertEquals(view.bar().pageStep(), view.viewport().scrollY(), "the press paged once");
            assertEquals(expectedRowY(view) + 40, view.get("d").getY(),
                    "and the last row moved with the page it took");
        }

        @Test
        @DisplayName("the old scrollBy and scrollTo still place, because other callers use them")
        void theScrollViewOwnWritesStillPlace() {
            // Not redundant with the tests above: a consumer still scrolls some views through these --
            // a sidebar's own snapping and its drag auto-scroll -- so the contract they had before the
            // bar existed has to keep holding.
            ScrollView view = view();

            view.scrollBy(20);
            assertEquals(20, view.viewport().scrollY());
            assertEquals(expectedRowY(view), view.get("b").getY(), "scrollBy placed the widgets");

            view.scrollTo(0);
            assertEquals(0, view.viewport().scrollY());
            assertEquals(70, view.get("b").getY(), "and so did scrollTo");
        }

        @Test
        @DisplayName("a new layout re-places the widgets, at the scroll it can still hold")
        void applyReplacesAtTheCurrentScroll() {
            ScrollView view = view();
            view.scrollTo(SCROLL_RANGE);
            assertEquals(SCROLL_RANGE, view.viewport().scrollY());

            // Three rows instead of four: 60 of content in a 50-tall region, so ten is all the scroll
            // there is now. The offset is clamped rather than reset -- a rebuild must not throw the reader
            // back to the top -- and that is the property this asserts.
            view.apply(Stack.stack().row("a", 20, Insets.NONE).row("b", 20, Insets.NONE)
                    .row("c", 20, Insets.NONE).build(BODY, measure()), BODY);

            assertEquals(10, view.viewport().scrollY(),
                    "the offset is clamped against the new height, not reset");
            assertEquals(expectedRowY(view), view.get("b").getY(),
                    "and the widgets are placed against the new layout");
        }
    }

    @Nested
    @DisplayName("what a caller can ask")
    class Reading {

        @Test
        @DisplayName("placedSlot reads the widget's own position, so it cannot disagree with the drawing")
        void placedSlotReadsTheWidget() {
            ScrollView view = view();

            Slot placed = view.placedSlot("b");

            assertNotNull(placed);
            assertEquals(view.get("b").getY(), placed.y(), "the answer is the widget's own y");
            assertEquals(view.get("b").getX(), placed.x());
        }

        @Test
        @DisplayName("accepts is the region's own test, which is what rejects a click outside the clip")
        void acceptsIsTheRegion() {
            ScrollView view = view();

            assertTrue(view.accepts(10, 60), "inside the region");
            assertFalse(view.accepts(10, 20), "above it");
            assertFalse(view.accepts(10, 120), "below it");
            assertFalse(view.accepts(BODY + 1, 60), "and to the right of it");
        }
    }
}
