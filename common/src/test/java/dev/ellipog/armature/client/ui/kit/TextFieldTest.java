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

    // ------------------------------------------------------------------
    // The selection
    // ------------------------------------------------------------------

    @Test
    void aDoubleClickMarksTheWordItLandedIn() {
        // The gesture that asked for all of this: "double clicking the text in text fields should mark it".
        for (int at : new int[] {1, 3, 5, 8}) {
            TextField field = typed("#4A90D9").selectWordAt(at);
            assertEquals("#4A90D9", field.selectedText(),
                    () -> "a click at " + at + " is inside the code, and the code is one word because '#' "
                            + "is a word character -- which is why it is one");
        }
    }

    @Test
    void aWordIsAWordAndPunctuationIsNot() {
        TextField field = typed("#4A90D9 extra").selectWordAt(11);
        assertEquals("extra", field.selectedText(), "a later word is its own");

        // Whitespace on its own has no word in it: the caret moves and nothing is marked, because marking
        // the gap between two words is a selection nobody wants and a second gesture to undo.
        TextField blank = typed("   ").selectWordAt(1);
        assertFalse(blank.hasSelection());
        assertEquals(1, blank.caret());

        // And a click in the space just after a word marks that word: it is where a click on the last
        // letter lands, and the character before the pointer is the one being pointed at.
        TextField after = typed("one two").selectWordAt(3);
        assertEquals("one", after.selectedText());

        TextField edge = typed("one two").selectWordAt(7);
        assertEquals("two", edge.selectedText(), "and the same at the end of the value");
    }

    @Test
    void typingOverAMarkReplacesIt() {
        TextField field = typed("#4A90D9").selectWordAt(2);

        field.insert('#');

        assertEquals("#", field.value(), "the mark went, the character came");
        assertEquals(1, field.caret());
    }

    @Test
    void backspaceAndDeleteRemoveTheMarkRatherThanOneCharacter() {
        TextField back = typed("one two three").selectWordAt(4).backspace();
        assertEquals("one  three", back.value(), "the word and not the character before the caret");
        assertEquals(4, back.caret(), "and the caret is where the mark began");

        TextField forward = typed("one two three").selectWordAt(4).deleteForward();
        assertEquals("one  three", forward.value());
    }

    @Test
    void selectAllThenTypeLeavesOnlyWhatWasTyped() {
        TextField field = typed("#4A90D9").selectAll();

        assertEquals("#4A90D9", field.selectedText());
        assertEquals(0, field.selectionStart());
        assertEquals(7, field.selectionEnd());

        field.insert('x');
        assertEquals("x", field.value());
    }

    @Test
    void movingTheCaretDropsTheMarkAndArrowsGoToItsEdge() {
        TextField clicked = typed("one two").selectWordAt(1).caretTo(5);
        assertFalse(clicked.hasSelection(), "a click says where the caret is and nothing else");

        // An arrow moves to the selection's edge rather than past it, so a mark can be dismissed in the
        // direction a person is already going -- which is where this differs from a plain step.
        assertEquals(3, typed("one two").selectWordAt(1).right().caret(), "right goes to the mark's end");
        TextField marked = typed("one two").selectWordAt(5);
        assertEquals(4, marked.left().caret(), "and left to its start");
        assertFalse(marked.hasSelection());
    }

    @Test
    void typingNeverLeavesAMarkBehind() {
        // The invariant the class rests on, and the one whose absence broke every test in this file when
        // the anchor was added: an edit that is not a selection gesture leaves one caret behind, because a
        // stale anchor is not a harmless leftover -- it is a mark, and the next keystroke replaces the text
        // with it.
        TextField field = typed("abc").left().insert('x').backspace().deleteForward().right();

        assertFalse(field.hasSelection(), "no step of typing, deleting or arrowing marks anything");
    }

    @Test
    void aMarkedFieldKnowsItsEdgesWhicheverWayItWasMade() {
        // The anchor is where the gesture started, and either end may be the caret; every question below
        // is about the pair, not about which of them is which.
        TextField field = typed("abcdef");
        field.caretTo(5).selectTo(2);

        assertTrue(field.hasSelection());
        assertEquals(2, field.selectionStart());
        assertEquals(5, field.selectionEnd());
        assertEquals("cde", field.selectedText());
    }

    // ------------------------------------------------------------------
    // The history
    // ------------------------------------------------------------------

    @Test
    void typingARunIsOneStepBack() {
        TextField field = TextField.of(64);
        field.insert('a');
        field.insert('b');
        field.insert('c');
        assertEquals("abc", field.value());

        field.undo();
        assertEquals("", field.value(), "the whole run goes back, not the last letter of it");
        field.redo();
        assertEquals("abc", field.value(), "and forward again");
    }

    @Test
    void undoPutsTheCaretAndTheMarkBackWhereTheEditWas() {
        TextField field = TextField.of(64);
        field.insert('a');
        field.insert('b');
        field.insert('c');
        field.caretTo(1);                       // a navigation ends the run: the next edit is its own step
        field.insert('X');
        assertEquals("aXbc", field.value());

        field.undo();
        assertEquals("abc", field.value());
        assertEquals(1, field.caret(), "the caret comes back to the edit, not to the end of the text");
        assertFalse(field.hasSelection());
    }

    @Test
    void replacingAMarkedRunUndoesAsOneStepAndComesBackMarked() {
        TextField field = TextField.of(64).setValue("hello world").clearHistory();
        field.selectAll();
        field.insert('!');
        assertEquals("!", field.value());

        field.undo();
        assertEquals("hello world", field.value());
        assertEquals(0, field.selectionStart());
        assertEquals(11, field.selectionEnd(), "the mark the edit replaced is put back too");
    }

    @Test
    void aNewEditDropsTheRedo() {
        TextField field = TextField.of(64);
        field.insert('a');
        field.undo();
        assertTrue(field.canRedo());
        field.insert('b');
        assertFalse(field.canRedo(), "redo describes a history that no longer happened");
    }

    @Test
    void theOpenedValueIsWhereTheHistoryStarts() {
        TextField field = TextField.of(64).setValue("open").clearHistory();
        assertFalse(field.canUndo(), "a just-opened field has nothing to undo");
        field.insert('!');
        field.undo();
        assertEquals("open", field.value());
        assertFalse(field.canUndo());
    }
}
