package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.RecordingRenderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a text field draws when it does not have the keyboard.
 *
 * <h2>The report these hold</h2>
 *
 * <p>A field is filled through {@code setValue}, which puts the caret at the end -- so the first version
 * of the drawing scrolled every field to its own tail: a chapter subtitle "Five levels, no tricks"
 * appeared as "s levels, no tricks" and read as a field whose value was corrupt. The view belongs to
 * the caret only while the field is being typed into; at rest the value starts where a reader looks.
 *
 * <p>Only the unfocused half is asserted here, and that is honest rather than lazy: the focused half
 * needs real font metrics, which is a client, and the arithmetic that decides how far a focused field
 * scrolls is {@code TextField.scrollOffset}'s, asserted without one.
 */
class ArmatureTextFieldTest {

    private static final String VALUE = "Five levels, no tricks";

    @Test
    void anUnfocusedFieldDrawsItsValueFromTheLeftEdge() {
        ArmatureTextField field = new ArmatureTextField(10, 20, 96, 18, VALUE);
        RecordingRenderer recorder = RecordingRenderer.create();

        field.render(recorder);

        RecordingRenderer.Call text = recorder.texts().stream()
                .filter(call -> call.text().equals(VALUE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the value was not drawn at all"));
        assertEquals(10 + ArmatureTextField.PAD, text.x(),
                "at rest the value starts at its own left edge, not scrolled to the caret at its end");
    }

    @Test
    void anUnfocusedFieldDrawsNoCaret() {
        ArmatureTextField field = new ArmatureTextField(10, 20, 96, 18, VALUE);
        RecordingRenderer recorder = RecordingRenderer.create();

        field.render(recorder);

        boolean caret = recorder.fills().stream()
                .anyMatch(call -> call.x2() - call.x() == 1 && call.y2() - call.y() == 10);
        assertFalse(caret, "no keyboard, no caret: the box and its text are the whole of the picture");
    }
}
