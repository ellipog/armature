package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editing rules of a field, asserted without a keyboard.
 *
 * <h2>What these cases are for</h2>
 *
 * <p>A text field's faults are all at its edges — the caret at zero, the caret at the end, the field at
 * its limit — and every one of them is arithmetic that a running game would only show you by hand. So
 * the cases below are the edges, and the ones about the limit and the caret are the ones worth having:
 * both are places where an off-by-one is invisible until somebody is typing quickly.
 */
class TextFieldTest {

    private static TextField typed(String text) {
        TextField field = TextField.of(64);
        for (char each : text.toCharArray()) {
            field.insert(each);
        }
        return field;
    }

    @Test
    void typingPutsCharactersWhereTheCaretIs() {
        TextField field = typed("ac");
        field.left().insert('b');

        assertEquals("abc", field.value());
        assertEquals(2, field.caret(), "and the caret follows what was typed");
    }

    @Test
    void backspaceAtTheStartDoesNothing() {
        TextField field = typed("ab").home();

        assertSame(field, field.backspace(), "the call is a no-op rather than an error");
        assertEquals("ab", field.value(), "which is what stops a held backspace eating the text behind it");
        assertEquals(0, field.caret());
    }

    @Test
    void deleteForwardRemovesWhatIsAfterTheCaret() {
        TextField field = typed("abc").home().deleteForward();

        assertEquals("bc", field.value(), "Delete takes the character the caret is in front of");
        assertEquals(0, field.caret(), "and the caret does not move, which is what makes it different "
                + "from Backspace");
    }

    @Test
    void deleteForwardAtTheEndDoesNothing() {
        TextField field = typed("abc");

        assertEquals("abc", field.deleteForward().value());
        assertEquals(3, field.caret());
    }

    @Test
    void theLimitIsRefusedRatherThanTruncating() {
        TextField field = TextField.of(3);
        for (char each : "abcd".toCharArray()) {
            field.insert(each);
        }

        assertEquals("abc", field.value(),
                "the fourth character is refused, so what is shown is what is held -- silently dropping "
                        + "the tail would lose text the player can see they typed");
        assertEquals(3, field.caret());
    }

    @Test
    void aValueLongerThanTheLimitIsCutOnTheWayIn() {
        TextField field = TextField.of(4).setValue("abcdefgh");

        assertEquals("abcd", field.value());
        assertEquals(4, field.caret(), "and the caret lands at the end of what was kept, not past it");
    }

    @Test
    void theCaretIsClampedRatherThanTrusted() {
        TextField field = typed("ab");

        assertEquals(2, field.caretTo(99).caret(), "a click past the end puts the caret at the end");
        assertEquals(0, field.caretTo(-5).caret(), "and a click before the start puts it at the start");
        assertEquals(1, field.caretTo(1).caret());
    }

    @Test
    void controlCharactersAreRefused() {
        TextField field = typed("ab");

        field.insert('\n').insert('\t');

        assertEquals("ab", field.value(),
                "a newline has to go somewhere in a single-line field, and nowhere is the honest answer");
    }

    @Test
    void clearingEmptiesTheTextAndTheCaret() {
        TextField field = typed("abc").clear();

        assertTrue(field.isEmpty());
        assertEquals(0, field.caret(), "a caret left at three in an empty field is a caret nobody can see");
        assertEquals(0, field.length());
    }

    @Test
    void theCaretBlinksAndStartsVisible() {
        TextField field = TextField.of(8);

        assertTrue(field.caretVisible(0L), "a field that has just been clicked into shows its caret now, "
                + "not half a second later");
        assertFalse(field.caretVisible(600L), "half a period on, it is off");
        assertTrue(field.caretVisible(1000L), "and on again a period later");
    }

    @Test
    void aFieldOfNoLengthRefusesEverything() {
        // Not a mistake: it is what a caller that has not decided its limit should get, and it has to be
        // a field that behaves rather than an exception that arrives while a screen is being built.
        TextField field = TextField.of(0).insert('a').setValue("hello");

        assertTrue(field.isEmpty());
    }
}
