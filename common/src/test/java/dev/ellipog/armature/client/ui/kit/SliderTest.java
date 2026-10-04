package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The slider's arithmetic, without a widget.
 *
 * <p>The cases are the edges: both ends reachable, a step that does not divide the span, a drag past
 * either edge, and a track with no width.
 */
@DisplayName("the slider's arithmetic")
class SliderTest {

    /** The card's own text-size range, so the cases below are the ones in use. */
    private static Slider textSize(double value) {
        return new Slider(1.0D, 1.5D, 0.05D, value);
    }

    @Test
    @DisplayName("a value snaps to the nearest step, and both ends are exact")
    void snapping() {
        assertEquals(1.25D, textSize(1.26D).value(), 1e-9);
        assertEquals(1.25D, textSize(1.24D).value(), 1e-9);
        assertEquals(1.0D, textSize(0.4D).value(), 1e-9, "below the range clamps to the minimum");
        assertEquals(1.5D, textSize(9.0D).value(), 1e-9, "above it clamps to the maximum");
        assertEquals(1.5D, textSize(1.5D).value(), 1e-9);
        assertEquals(1.0D, textSize(1.0D).value(), 1e-9);
    }

    @Test
    @DisplayName("a step that does not divide the span still reaches the maximum")
    void theMaximumIsReachable() {
        // 0..1 in steps of 0.3: 0.9 is the last step under the top, and the top is still reachable.
        Slider slider = new Slider(0.0D, 1.0D, 0.3D, 0.0D);
        assertEquals(1.0D, slider.withValue(1.0D).value(), 1e-9);
        assertEquals(0.9D, slider.withValue(0.95D).value(), 1e-9);
        assertEquals(0.6D, slider.withValue(0.74D).value(), 1e-9, "below the midpoint rounds down");
        assertEquals(0.9D, slider.withValue(0.76D).value(), 1e-9, "and above it rounds up");
    }

    @Test
    @DisplayName("the fraction is the position on the track, and it is exact at both ends")
    void fractions() {
        assertEquals(0.0D, textSize(1.0D).fraction(), 1e-9);
        assertEquals(1.0D, textSize(1.5D).fraction(), 1e-9);
        assertEquals(0.5D, textSize(1.25D).fraction(), 1e-9);
    }

    @Test
    @DisplayName("a click or drag past either end asks for the end, not for more")
    void draggingPastTheEdge() {
        Slider slider = textSize(1.0D);
        assertEquals(1.0D, slider.valueAt(-2.0D), 1e-9);
        assertEquals(1.5D, slider.valueAt(2.0D), 1e-9);
        assertEquals(1.25D, slider.valueAt(0.5D), 1e-9);
    }

    @Test
    @DisplayName("a pointer is read against the track it is on")
    void pointersOnATrack() {
        Slider slider = textSize(1.0D);
        assertEquals(1.0D, slider.valueAtX(10, 10, 100), 1e-9, "the left edge is the minimum");
        assertEquals(1.5D, slider.valueAtX(110, 10, 100), 1e-9, "the right edge is the maximum");
        assertEquals(1.25D, slider.valueAtX(60, 10, 100), 1e-9);
        assertEquals(1.0D, slider.valueAtX(60, 10, 0), 1e-9,
                "a track with no width owes a value rather than dividing by zero");
    }

    @Test
    @DisplayName("a range that is not a range, or a step of nothing, is refused")
    void impossibleSliders() {
        assertThrows(IllegalArgumentException.class, () -> new Slider(1.0D, 1.0D, 0.1D, 1.0D));
        assertThrows(IllegalArgumentException.class, () -> new Slider(1.5D, 1.0D, 0.1D, 1.0D));
        assertThrows(IllegalArgumentException.class, () -> new Slider(0.0D, 1.0D, 0.0D, 0.5D));
    }
}
