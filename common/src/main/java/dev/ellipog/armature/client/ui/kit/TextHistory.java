package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A text field's undo history: the states an editor has been in, and which one is next.
 *
 * <h2>Why the state is the whole field, not the change</h2>
 *
 * <p>Each entry is everything a field was before one edit — the text, the caret and the mark's other end.
 * Undoing an edit has to put back not just the characters but the place in them, or the caret comes out at
 * the end of the text instead of at the character that was being worked on, and an undo that moves the
 * cursor somewhere else is an undo the author has to correct before they can continue.
 *
 * <h2>A run of one kind of edit is one step</h2>
 *
 * <p>Typing a word and pressing Ctrl+Z should take the word back, not the last letter of it — so a
 * {@code TYPING} edit that follows another {@code TYPING} edit does not push a new step, and the same for
 * {@code DELETING}. Anything else breaks the run: a wholesale replace, and any navigation at all, which is
 * what {@link #breakRun()} is for. The *caller* knows when the caret moved for a reason that has nothing
 * to do with the edit in progress, so the model says so and the next edit starts a step of its own.
 *
 * <p><b>No clocks.</b> A pause could end a run too, and it would make the history depend on how fast
 * somebody types — a rule nobody can hold in their head, and one that cannot be asserted without a clock.
 * The kinds are enough, and they are testable.
 *
 * <h2>Bounded, with both ends honest</h2>
 *
 * <p>{@link #LIMIT} steps, the oldest dropped. A field holds a description or a hex code, not a document:
 * the bound is there so a long editing session cannot grow without limit, and what it costs is the far
 * past, which is the end a person expects to lose first.
 *
 * <p>The redo side is thrown away by a new edit, always — including an edit that merely continues a run.
 * Redo after a keystroke is the "forward" of a history that no longer describes what happened, and every
 * editor ever written drops it for the same reason.
 */
public final class TextHistory {

    /**
     * How many steps are kept.
     *
     * <p>128, which is a paragraph's worth of typing at one step per run and several wholesale edits: deep
     * enough that nobody reaches the end by accident, shallow enough that the memory is a rounding error
     * beside the text itself.
     */
    public static final int LIMIT = 128;

    /** What an edit was. Two edits continue one run only if they are the same kind, and not {@code WHOLE}. */
    public enum Edit {
        /** Characters typed. Consecutive typing is one step. */
        TYPING,
        /** Characters removed by backspace or delete. Consecutive deleting is one step. */
        DELETING,
        /** A wholesale change — a paste, a set, a clear. Never continues anything. */
        WHOLE
    }

    /** One point in a field's editing: the text, the caret, and the mark's other end. */
    public record State(String value, int caret, int anchor) {
    }

    private final Deque<State> back = new ArrayDeque<>();
    private final Deque<State> forward = new ArrayDeque<>();

    /** The kind of the edit in progress, or null when the next edit starts a new step. */
    private Edit last;

    /**
     * Records the state an edit is about to change, unless this edit continues the run it belongs to.
     *
     * @param edit    what is about to happen
     * @param current the field as it stands, which is what an undo of this edit restores
     */
    public void before(Edit edit, State current) {
        if (edit != Edit.WHOLE && edit == last) {
            // One step for the run; only the redo side changes, and it changes on every keystroke.
            forward.clear();
            return;
        }
        back.push(current);
        while (back.size() > LIMIT) {
            back.removeLast();
        }
        forward.clear();
        last = edit;
    }

    /**
     * The state to go back to, having remembered the one left behind — or null when there is nothing to
     * undo, which the caller answers by doing nothing rather than by guessing.
     */
    public State undo(State current) {
        if (back.isEmpty()) {
            return null;
        }
        forward.push(current);
        last = null;
        return back.pop();
    }

    /** The state to go forward to, having remembered the one left behind — or null when there is nothing. */
    public State redo(State current) {
        if (forward.isEmpty()) {
            return null;
        }
        back.push(current);
        last = null;
        return forward.pop();
    }

    public boolean canUndo() {
        return !back.isEmpty();
    }

    public boolean canRedo() {
        return !forward.isEmpty();
    }

    /**
     * Ends the run in progress: the next edit is a new step, even if it is the same kind as the last one.
     *
     * <p>Called by every navigation — a caret move, a selection, a click — because "typed, looked
     * somewhere else, typed again" is two edits to a person, and one step to a rule that only counts kinds.
     */
    public void breakRun() {
        last = null;
    }

    /** Forgets everything. Where a field's history starts: the value it was opened with. */
    public void clear() {
        back.clear();
        forward.clear();
        last = null;
    }
}
