package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canvas transform, asserted against a known mapping rather than judged in a screenshot.
 *
 * <h2>Why this is the file that makes the extraction worth it</h2>
 *
 * <p>Zoom-about-the-pointer is three lines of algebra that everyone gets wrong once, and the wrong
 * version looks exactly like the right one until somebody scrolls. In a screen it could only be judged
 * by eye, and "the zoom ran away from the cursor" is the sort of report that leads to guessing. Here the
 * contract is stated as an assertion: the content point under the pointer is the same point after.
 *
 * <p>{@link #zoomAtKeepsTheContentPointUnderThePointer()} sweeps it from several pans and scales
 * instead of checking one, because the arithmetic simplifies to a correct-looking identity at the
 * origin and goes wrong everywhere else.
 */
class ViewportTest {

    /**
     * Half a pixel's worth of content at a scale of one.
     *
     * <p>A pan offset is an integer, so it lands within half a screen pixel of what the arithmetic
     * wants — which is half a pixel of <i>content</i> divided by the scale. The sweep below computes its
     * own from the scale it is testing, because this number only holds at and above 100%, and that is
     * exactly how a tolerance of 0.51 passed at 1.9 (where the error is 0.23) and failed at 0.4 (where
     * it is 1.09).
     */
    private static final float TOLERANCE = 0.51F;

    private static Viewport canvas() {
        return Viewport.of(0.35F, 2.2F).bounds(100, 50, 400, 300);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @Test
    void aViewportRefusesAScaleRangeItCannotHonour() {
        assertThrows(IllegalArgumentException.class, () -> Viewport.of(0F, 1F));
        assertThrows(IllegalArgumentException.class, () -> Viewport.of(-1F, 1F));
        assertThrows(IllegalArgumentException.class, () -> Viewport.of(2F, 1F));
    }

    @Test
    void aFixedViewportHasExactlyOneScale() {
        Viewport view = Viewport.fixed().bounds(0, 0, 100, 100);
        assertFalse(view.setScale(2F));
        assertEquals(1F, view.scale());
        assertFalse(view.zoomAt(50, 50, 1.15F), "a scroll-only view must not zoom");
    }

    @Test
    void boundsAreReadBackAsGivenIncludingTheFarEdges() {
        Viewport view = canvas();
        assertEquals(100, view.originX());
        assertEquals(50, view.originY());
        assertEquals(500, view.viewRight());
        assertEquals(350, view.viewBottom());
    }

    // ------------------------------------------------------------------
    // Scale
    // ------------------------------------------------------------------

    @Test
    void scaleIsClampedToItsRange() {
        Viewport view = canvas();
        view.setScale(99F);
        assertEquals(2.2F, view.scale());
        view.setScale(0.01F);
        assertEquals(0.35F, view.scale());
    }

    @Test
    void setScaleReportsWhetherAnythingMoved() {
        Viewport view = canvas();
        assertTrue(view.setScale(1.5F));
        assertFalse(view.setScale(1.5F));
        assertTrue(view.setScale(99F));
        assertFalse(view.setScale(99F), "already at the limit, so nothing changed");
    }

    // ------------------------------------------------------------------
    // Offset: unbounded by default, clamped once the content size is known
    // ------------------------------------------------------------------

    @Test
    void anUnboundedViewportMayBeDraggedOffIntoEmptySpace() {
        // Deliberate: a player exploring a large graph should not be stopped at an invisible edge,
        // and a node's bounding box is not a wall.
        Viewport view = canvas();
        view.panBy(-10_000, -10_000);
        assertEquals(-10_000, view.offsetX());
        assertEquals(-10_000, view.offsetY());
    }

    @Test
    void boundingTheContentClampsTheOffset() {
        Viewport view = canvas();
        view.setContentSize(400, 900);

        view.setOffset(0, 0);
        assertEquals(0, view.offsetY(), "the top of the content is as far up as it goes");

        view.setOffset(0, -10_000);
        assertEquals(300 - 900, view.offsetY(), "the bottom of the content stops at the view's bottom");

        view.setOffset(-10_000, 0);
        assertEquals(400 - 400, view.offsetX(), "content exactly as wide as the view cannot pan in x");
    }

    @Test
    void contentThatFitsIsPinnedRatherThanCentred() {
        // A short list that moves when the window resizes reads as a layout fault, and there is nothing
        // to scroll either way.
        Viewport view = canvas();
        view.setOffset(-500, -500);
        view.setContentSize(100, 100);
        assertEquals(0, view.offsetX());
        assertEquals(0, view.offsetY());
        assertEquals(0, view.maxScrollY());
    }

    @Test
    void scrollIsPositiveDownwardsAndClamped() {
        Viewport view = canvas();
        view.setContentSize(400, 1000);

        assertEquals(0, view.scrollY());
        view.setScrollY(200);
        assertEquals(200, view.scrollY());
        assertEquals(-200, view.offsetY(), "the content origin sits above the view");

        view.setScrollY(-50);
        assertEquals(0, view.scrollY(), "overshooting the top clamps rather than bouncing");

        view.setScrollY(10_000);
        assertEquals(700, view.scrollY());
        assertEquals(700, view.maxScrollY());
    }

    @Test
    void scrollClampsAgainstTheScaledContentNotTheAuthoredHeight() {
        // The clamp is in screen pixels, so a zoomed-in content scrolls further. Getting this wrong is
        // a thumb that stops short at 200% and a last line that cannot be reached.
        Viewport view = canvas();
        view.setContentSize(400, 1000);
        view.setScale(2F);
        assertEquals(1000 * 2 - 300, view.maxScrollY());
    }

    @Test
    void changingTheScaleReclampsTheOffset() {
        // Zoom out at the bottom of a long document and the offset is now past the end. Leaving it
        // there shows empty space below the content until the next scroll.
        //
        // Its own viewport rather than canvas(), because the interesting case needs a scale below
        // canvas()'s 0.35 floor: content that has become shorter than the view entirely. My first
        // version asserted exactly that at 0.35 — and 1000 * 0.35 is 350 pixels in a 300-pixel view, so
        // it still had 50 pixels to scroll and the expectation was simply wrong about the arithmetic.
        Viewport view = Viewport.of(0.1F, 2F).bounds(100, 50, 400, 300);

        view.setContentSize(400, 1000);
        view.setScrollY(700);
        assertEquals(700, view.scrollY());

        view.setScale(0.2F);
        assertEquals(0, view.scrollY(),
                "at 20% the content is 200 tall in a 300 view, so there is nothing left to scroll");

        // And where it is still taller than the view, the offset is clamped to the new end rather than
        // left past it — which is the half of this that was right the first time.
        view.setScale(1F);
        view.setScrollY(700);
        view.setScale(0.35F);
        assertEquals(50, view.scrollY(), "350 tall in a 300 view leaves 50");
    }

    // ------------------------------------------------------------------
    // The mapping
    // ------------------------------------------------------------------

    @Test
    void contentAndScreenCoordinatesRoundTrip() {
        Viewport view = canvas();
        view.setOffset(-120, -40);
        view.setScale(1.5F);

        assertEquals(view.screenX(80), view.screenX(Math.round(view.contentX(view.screenX(80)))));
        assertEquals(100 - 120 + 120, view.screenX(80), "origin + offset + content * scale");
        assertEquals(50 - 40 + 60, view.screenY(40));
    }

    @Test
    void aContentLengthScalesByTheZoom() {
        Viewport view = canvas();
        assertEquals(50, view.scaled(50));
        view.setScale(2F);
        assertEquals(100, view.scaled(50));
    }

    @Test
    void theVisibleRectangleIsTheContentUnderTheView() {
        Viewport view = canvas();
        view.setContentSize(10_000, 10_000);
        view.setOffset(-200, -100);
        view.setScale(2F);

        assertEquals(100F, view.visibleLeft(), 0.001F);
        assertEquals(50F, view.visibleTop(), 0.001F);
        assertEquals(100F + 400 / 2F, view.visibleRight(), 0.001F);
        assertEquals(50F + 300 / 2F, view.visibleBottom(), 0.001F);
    }

    @Test
    void aClickOutsideTheViewIsRefused() {
        Viewport view = canvas();
        assertTrue(view.containsScreen(100, 50));
        assertTrue(view.containsScreen(499, 349));
        // Half-open: the far edges are outside, so a click on the boundary belongs to the surface past
        // it rather than to both.
        assertFalse(view.containsScreen(500, 200));
        assertFalse(view.containsScreen(200, 350));
        assertFalse(view.containsScreen(99, 200));
    }

    // ------------------------------------------------------------------
    // Zoom about a point
    // ------------------------------------------------------------------

    @Test
    void zoomAtKeepsTheContentPointUnderThePointer() {
        for (int startPan = -400; startPan <= 400; startPan += 200) {
            for (float startScale : new float[] {0.4F, 1F, 1.9F}) {
                Viewport view = canvas();
                view.setOffset(startPan, startPan / 2);
                view.setScale(startScale);

                double[] pointers = {100.0, 250.0, 499.0, 140.0};
                for (double pointer : pointers) {
                    float beforeX = view.contentX(pointer);
                    float beforeY = view.contentY(pointer);

                    view.zoomAt(pointer, pointer, 1.15F);

                    // Derived from the scale rather than chosen. The offset is an int, so it lands within
                    // half a screen pixel of the arithmetic's answer -- and half a screen pixel is half a
                    // pixel of *content* divided by the new scale. A fixed threshold is not a valid bound
                    // across the sweep at all, which is how 0.51 passed at 1.9 and failed at 0.4.
                    float tolerance = 0.51F / view.scale();

                    assertEquals(beforeX, view.contentX(pointer), tolerance,
                            "x under the pointer moved, pan " + startPan + " scale " + startScale);
                    assertEquals(beforeY, view.contentY(pointer), tolerance,
                            "y under the pointer moved, pan " + startPan + " scale " + startScale);
                }
            }
        }
    }

    @Test
    void draggingKeepsTheGrabbedPointUnderThePointer() {
        for (int startPan = -400; startPan <= 400; startPan += 200) {
            for (float startScale : new float[] {0.4F, 1F, 1.9F}) {
                Viewport view = canvas();
                view.setOffset(startPan, startPan / 2);
                view.setScale(startScale);

                float grabX = view.contentX(300.0);
                float grabY = view.contentY(200.0);
                view.dragTo(340.0, 260.0, grabX, grabY);

                float tolerance = 0.51F / view.scale();
                assertEquals(grabX, view.contentX(340.0), tolerance,
                        "the grabbed x left the pointer, pan " + startPan + " scale " + startScale);
                assertEquals(grabY, view.contentY(260.0), tolerance,
                        "the grabbed y left the pointer, pan " + startPan + " scale " + startScale);
            }
        }
    }

    @Test
    void aZoomDuringADragIsNotUndoneByTheNextDragEvent() {
        // The regression this method was written for. The first version of the canvas drag anchored to
        // the *offset* captured at press, so a mouse move after a wheel notch put that offset back and
        // threw the zoom away -- holding the canvas and turning the wheel made the view jump back to
        // where the drag had started, once per notch. Holding the *content point* instead makes the two
        // agree: the zoom pivots on the point being held, and the drag re-places that same point.
        Viewport view = canvas();
        view.setOffset(0, 0);

        double pointerX = 300.0;
        double pointerY = 200.0;
        float grabX = view.contentX(pointerX);
        float grabY = view.contentY(pointerY);

        view.zoomAt(pointerX, pointerY, 1.5F);
        view.dragTo(pointerX, pointerY, grabX, grabY);

        float tolerance = 0.51F / view.scale();
        assertEquals(grabY, view.contentY(pointerY), tolerance,
                "the point the drag holds left the pointer, so the zoom had been undone");
        assertEquals(1.5F, view.scale(), 0.0001F, "and the zoom itself stands");
        assertEquals(grabX, view.contentX(pointerX), tolerance, "the same on the other axis");
    }

    @Test
    void zoomingInThenOutComesBack() {
        Viewport view = canvas();
        view.setOffset(-77, -31);
        view.setScale(1.0F);

        view.zoomAt(314, 220, 1.15F);
        view.zoomAt(314, 220, 1F / 1.15F);

        assertEquals(1.0F, view.scale(), 0.0001F);

        // Within a pixel rather than exactly. Each of the two steps rounds the offset to a whole pixel,
        // so the round trip lands within one of where it started and the value it lands on is a
        // coincidence of these three numbers. Asserting -77 exactly would pass here and break on an
        // unrelated change, which retires the test rather than failing it.
        assertTrue(Math.abs(view.offsetX() + 77) <= 1, "x came back within a pixel, was " + view.offsetX());
        assertTrue(Math.abs(view.offsetY() + 31) <= 1, "y came back within a pixel, was " + view.offsetY());
    }

    @Test
    void aZoomAlreadyAtALimitDoesNotDrift() {
        // The version that recomputes is a view that walks a pixel per wheel notch while the scale does
        // not move -- invisible until somebody scrolls at the maximum for a while.
        Viewport view = canvas();
        view.setScale(2.2F);
        view.setOffset(-33, -44);

        for (int i = 0; i < 20; i++) {
            assertFalse(view.zoomAt(300, 200, 1.15F));
        }
        assertEquals(-33, view.offsetX());
        assertEquals(-44, view.offsetY());
        assertEquals(2.2F, view.scale());
    }

    @Test
    void zoomingAboutTheCentreUsesTheViewsOwnCentre() {
        Viewport view = canvas();
        float centreContentX = view.contentX(300);
        assertTrue(view.zoomAboutCentre(2F));
        assertEquals(centreContentX, view.contentX(300), TOLERANCE);
    }

    // ------------------------------------------------------------------
    // Fitting
    // ------------------------------------------------------------------

    @Test
    void centringPutsTheBoundingBoxMiddleAtTheViewMiddle() {
        Viewport view = canvas();
        view.centreOn(-200, -200, 200, 200);

        // The content's centre is (0, 0), and it should land at the view's centre (300, 200).
        assertEquals(300, view.screenX(0));
        assertEquals(200, view.screenY(0));
    }

    @Test
    void centringUsesTheBoundingBoxNotTheOrigin() {
        // A graph authored at negative coordinates is the normal case for an editor that grows in
        // every direction. Taking a size and an origin would put it off the edge with the arithmetic
        // still looking correct.
        Viewport view = canvas();
        view.centreOn(1000, 500, 1200, 700);
        assertEquals(300, view.screenX(1100));
        assertEquals(200, view.screenY(600));
    }

    @Test
    void centringDoesNotClamp() {
        // It defines where a canvas starts, so applying a clamp it was not asked for would move content
        // the caller had just positioned.
        Viewport view = canvas();
        view.setContentSize(100, 100);
        view.centreOn(0, 0, 0, 0);
        assertEquals(300, view.screenX(0));
        assertEquals(200, view.screenY(0));
    }
}
