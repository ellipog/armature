package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history's own rules: what a step is, when a run continues, and what a new edit does to redo.
 *
 * <p>Asserted here, without a field, because these are the rules both text models share — the models test
 * only that they are wired to them. The states are written as short strings so the assertions read as a
 * sequence of edits rather than as snapshots of three integers.
 */
@DisplayName("a field's undo history")
class TextHistoryTest {

    private static TextHistory.State at(String value) {
        return new TextHistory.State(value, value.length(), value.length());
    }

    private static TextHistory.State at(String value, int caret) {
        return new TextHistory.State(value, caret, caret);
    }

    @Test
    @DisplayName("nothing to undo is null, not an empty state")
    void nothingToUndoIsNull() {
        TextHistory history = new TextHistory();
        assertFalse(history.canUndo());
        assertNull(history.undo(at("")));
        assertFalse(history.canRedo());
        assertNull(history.redo(at("")));
    }

    @Test
    @DisplayName("typing is one step per run, and a different kind starts a new one")
    void aRunOfOneKindIsOneStep() {
        TextHistory history = new TextHistory();
        history.before(TextHistory.Edit.TYPING, at(""));
        history.before(TextHistory.Edit.TYPING, at("a"));
        history.before(TextHistory.Edit.TYPING, at("ab"));
        // One step: the state before the first keystroke of the run.
        assertEquals(at(""), history.undo(at("abc")));

        // Deleting is a different kind, so it starts a step of its own.
        TextHistory other = new TextHistory();
        other.before(TextHistory.Edit.TYPING, at(""));
        other.before(TextHistory.Edit.DELETING, at("a"));
        assertEquals(at("a"), other.undo(at("")));
        assertEquals(at(""), other.undo(at("a")));
    }

    @Test
    @DisplayName("navigation breaks the run, so typing after it is a second step")
    void breakRunStartsANewStep() {
        TextHistory history = new TextHistory();
        history.before(TextHistory.Edit.TYPING, at(""));
        history.before(TextHistory.Edit.TYPING, at("a"));
        history.breakRun();
        history.before(TextHistory.Edit.TYPING, at("ab", 1));
        assertEquals(at("ab", 1), history.undo(at("abc", 2)));
        assertEquals(at(""), history.undo(at("ab", 1)));
    }

    @Test
    @DisplayName("a wholesale edit never continues anything")
    void wholeIsAlwaysItsOwnStep() {
        TextHistory history = new TextHistory();
        history.before(TextHistory.Edit.WHOLE, at(""));
        history.before(TextHistory.Edit.WHOLE, at("pasted"));
        assertEquals(at("pasted"), history.undo(at("pasted twice")));
        assertEquals(at(""), history.undo(at("pasted")));
    }

    @Test
    @DisplayName("redo walks forward again, and a new edit drops it")
    void redoIsDroppedByANewEdit() {
        TextHistory history = new TextHistory();
        history.before(TextHistory.Edit.TYPING, at(""));
        assertEquals(at(""), history.undo(at("one")));
        assertTrue(history.canRedo());
        assertEquals(at("one"), history.redo(at("")));

        // A keystroke after an undo, and the forward path is gone -- including one that merely continues a
        // run, which is the case a history that only cleared on WHOLE edits would get wrong.
        TextHistory other = new TextHistory();
        other.before(TextHistory.Edit.TYPING, at(""));
        other.undo(at("one"));
        other.before(TextHistory.Edit.TYPING, at(""));
        other.before(TextHistory.Edit.TYPING, at("t"));
        assertFalse(other.canRedo());
    }

    @Test
    @DisplayName("the oldest steps fall off the end at the limit")
    void theHistoryIsBounded() {
        TextHistory history = new TextHistory();
        for (int i = 0; i < TextHistory.LIMIT + 10; i++) {
            history.before(TextHistory.Edit.WHOLE, at("step " + i));
        }
        int steps = 0;
        while (history.canUndo()) {
            history.undo(at("current"));
            steps++;
        }
        assertEquals(TextHistory.LIMIT, steps, "the oldest ten were dropped");
    }

    @Test
    @DisplayName("clearing forgets everything, forward as well as back")
    void clearForgetsBothDirections() {
        TextHistory history = new TextHistory();
        history.before(TextHistory.Edit.TYPING, at(""));
        history.undo(at("typed"));
        history.clear();
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
    }
}
