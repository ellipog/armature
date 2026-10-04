package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.RecordingRenderer;
import dev.ellipog.armature.client.ui.Themes;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slider's drawing and its input, read back from a renderer that keeps its calls.
 *
 * <p>The arithmetic is {@code SliderTest}'s; what this answers is the pair of faults a widget can have
 * that arithmetic cannot see: something that is placed but never drawn, and a pointer that lands
 * somewhere other than where the thumb was put. The first version of the settings card shipped a
 * switch with exactly the first fault.
 */
@DisplayName("ArmatureSlider")
class ArmatureSliderTest {

    private static final int X = 10;
    private static final int Y = 4;
    private static final int W = 200;

    @BeforeEach
    void resetTheme() {
        ArmatureTheme.resetCurrent();
        ArmatureTheme.setCurrent(Themes.MODERN);
    }

    private static ArmatureSlider slider(double value) {
        return new ArmatureSlider(X, Y, W, 1.0D, 1.5D, 0.05D, value)
                .label(Component.literal("Text size"));
    }

    /** The track's left edge, as the widget lays it out: half the control, never under MIN_TRACK. */
    private static int trackLeft() {
        return X + W - Math.max(ArmatureSlider.MIN_TRACK, W / 2);
    }

    /** The thumb is the tallest accent fill; the travelled part of the track is four pixels tall. */
    private static int thumbLeft(RecordingRenderer r) {
        int accent = ArmatureControlStyle.fillAt(ArmatureControlStyle.Variant.ACCENT, true, false, 0F);
        return r.fills().stream()
                .filter(fill -> fill.argb() == accent && fill.y2() - fill.y() > 4)
                .map(RecordingRenderer.Call::x)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no thumb was drawn: " + r));
    }

    @Test
    @DisplayName("the message and the track are drawn, inside the control")
    void theMessageAndTheTrackAreDrawn() {
        RecordingRenderer r = RecordingRenderer.create();
        slider(1.0D).draw(r, 0L);

        assertTrue(r.texts().stream().anyMatch(text -> text.text().equals("Text size")
                        && text.x() >= X && text.y() >= Y && text.y() <= Y + ArmatureSlider.HEIGHT),
                () -> "the message is not drawn in the control: " + r);
        assertTrue(r.fills().stream().anyMatch(fill -> fill.argb() == ArmatureTheme.recessed()
                        && fill.x() >= X && fill.x2() <= X + W
                        && fill.y() >= Y && fill.y2() <= Y + ArmatureSlider.HEIGHT),
                () -> "the track's well is not drawn in the control: " + r);
    }

    @Test
    @DisplayName("the thumb sits at the track's left end at the minimum and travels right with the value")
    void theThumbFollowsTheValue() {
        RecordingRenderer low = RecordingRenderer.create();
        slider(1.0D).draw(low, 0L);
        assertEquals(trackLeft(), thumbLeft(low), "at the minimum the thumb is at the left end");

        RecordingRenderer high = RecordingRenderer.create();
        slider(1.5D).draw(high, 0L);
        assertTrue(thumbLeft(high) > trackLeft(), "at the maximum it has travelled right");
    }

    @Test
    @DisplayName("a click sets the value, and the arrows step by one")
    void input() {
        ArmatureSlider slider = slider(1.0D);

        slider.onClick(trackLeft() + 1, Y + 4);
        assertEquals(1.0D, slider.value(), 1e-9, "a click at the track's left end is the minimum");

        slider.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertEquals(1.05D, slider.value(), 1e-9, "one step right");
        slider.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0);
        assertEquals(1.0D, slider.value(), 1e-9, "and one back");
    }
}
