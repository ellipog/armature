package dev.ellipog.armature.client.ui.kit;

import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * A line of text being edited: what it says, where the caret is, and the edits that move one or both.
 *
 * <h2>Why the text and the carets are not the widget's</h2>
 * <p>
 * Because everything worth being wrong about here is arithmetic on a string, and arithmetic on a string
 * is the one thing a test can hold without a client, a font, or a window. A field whose state lived
 * inside a widget would be a field whose editing rules could only be checked by typing into a running
 * game — which is a slow loop, and one nobody runs for the eleventh case.
 *
 * <p>So this holds the text and the caret and knows nothing else: no font, no pixels, no keyboard, no
 * clock. A widget owns one of these and translates keystrokes into calls on it; the drawing asks it for
 * the text and the caret's position in it. {@code ArmatureTextField} is that widget.
 *
 * <h2>Selection, and what changed its mind</h2>
 * <p>
 * This class used to say selection was deliberately absent: a range and an anchor would double every
 * method below, and nothing in either mod needed one. What made it needed was a report from play — *"double
 * clicking the text in text fields should mark it"* — and that is the ordinary reason a decision like this
 * reverses: not a new requirement invented by the code, but a gesture a person reached for and found
 * missing.
 *
 * <p>So the doubling is paid. There is an <b>anchor</b> as well as a caret, a selection is the pair when
 * they differ, and every edit replaces it first — which is the whole of what a selection means to an edit.
 * The rule that keeps it from becoming two states instead of one: <b>an operation that is not about the
 * selection collapses it</b>. A click, Home, End, {@code setValue} — anything that means "the caret is
 * here now" leaves one caret behind, and only {@code selectTo}, {@code selectWordAt} and {@code selectAll}
 * leave two.
 *
 * <p>Arrow keys are the interesting case and they follow every editor: they <i>move to the selection's
 * edge</i> rather than stepping past it, so a selection can be dismissed in the direction a person is
 * already going.
 */
public final class TextField {

    /** How long the caret is on, and off, in the usual rhythm. */
    private static final long BLINK_MILLIS = 500L;

    private final int maxLength;

    /** How this field's text got to where it is. See {@link TextHistory}. */
    private final TextHistory history = new TextHistory();

    private String value;
    private int caret;

    /** The selection's fixed end. Equal to {@link #caret} whenever there is no selection. */
    private int anchor;

    private TextField(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        this.value = "";
        this.caret = 0;
        this.anchor = 0;
    }

    /**
     * An empty field that will hold at most {@code maxLength} characters.
     *
     * <p>A limit rather than none because a field with no limit is a field a player can paste a novel
     * into, and everything downstream of it — a command, a name, a width — then has to cope. A limit of
     * zero is allowed and yields a field that refuses everything, which is what a caller that has not
     * decided yet should get rather than an exception.
     */
    public static TextField of(int maxLength) {
        return new TextField(maxLength);
    }

    /** The text, never null and never longer than the limit. */
    public String value() {
        return value;
    }

    /** Where the caret sits, in characters from the start. Always 0..{@link #length()}. */
    public int caret() {
        return caret;
    }

    public int length() {
        return value.length();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    // ------------------------------------------------------------------
    // The selection
    // ------------------------------------------------------------------

    /** Whether anything is marked. */
    public boolean hasSelection() {
        return anchor != caret;
    }

    /** The selection's left end, which is where the caret stays when one is deleted. */
    public int selectionStart() {
        return Math.min(anchor, caret);
    }

    /** The selection's right end. */
    public int selectionEnd() {
        return Math.max(anchor, caret);
    }

    /** The marked text, or the empty string when nothing is marked. */
    public String selectedText() {
        return value.substring(selectionStart(), selectionEnd());
    }

    /** Marks everything, which is what {@code Ctrl+A} means once a selection exists. */
    public TextField selectAll() {
        history.breakRun();
        anchor = 0;
        caret = value.length();
        return this;
    }

    /**
     * Extends the selection to {@code position}, keeping the anchor.
     *
     * <p>For a drag or a shift-click. A caller with no anchor yet gets one at the caret, so a first call
     * marks from where the caret already is rather than from nowhere.
     */
    public TextField selectTo(int position) {
        history.breakRun();
        caret = Codepoints.snap(value, position);
        anchor = Codepoints.snap(value, anchor);
        return this;
    }

    /**
     * Marks the word around {@code position} — what a double click means.
     *
     * <p>A word is a run of letters, digits, {@code #} or {@code _}: {@code #} because a hex code is one
     * word to anybody who double-clicks it, and this class was asked for selection by a field holding
     * exactly that. On a separator or on empty space nothing is marked and the caret simply moves, because
     * marking the whitespace between two words is a selection nobody wants and a second gesture to undo.
     *
     * <p>An index hard against a word's right edge marks that word, since that is where a click "after the
     * last letter" lands and the character before it is the one being pointed at.
     */
    public TextField selectWordAt(int position) {
        history.breakRun();
        caret = Codepoints.snap(value, position);
        anchor = caret;
        if (value.isEmpty()) {
            return this;
        }
        int at = Math.min(caret, value.length() - 1);
        if (!wordChar(value.charAt(at)) && at > 0 && wordChar(value.charAt(at - 1))) {
            at--;
        }
        if (!wordChar(value.charAt(at))) {
            return this;
        }
        int from = at;
        while (from > 0 && wordChar(value.charAt(from - 1))) {
            from--;
        }
        int to = at + 1;
        while (to < value.length() && wordChar(value.charAt(to))) {
            to++;
        }
        anchor = from;
        caret = to;
        return this;
    }

    private static boolean wordChar(char character) {
        return Character.isLetterOrDigit(character) || character == '#' || character == '_';
    }

    // ------------------------------------------------------------------
    // The clipboard. The rules live here; the widget's only job is the OS handoff.
    // ------------------------------------------------------------------

    /**
     * What a copy takes: the mark when there is one, the whole value otherwise.
     *
     * <p>The whole value when nothing is marked is what every field here has always done, and it is what
     * makes a double click worth doing at all: a hex code, an id, a name is one value, and "copy" on a
     * field holding one thing means that thing. Empty when the field is.
     *
     * <p>Here rather than in the widget because it is a rule about text, and rules about text are what a
     * test can hold without a client; the widget carries the answer to the OS clipboard and decides
     * nothing.
     */
    public String copyText() {
        return hasSelection() ? selectedText() : value;
    }

    /**
     * What a cut takes: the mark, removed as one edit. Nothing marked is nothing taken, and nothing
     * changes.
     *
     * <p>A cut that took the whole unmarked value would empty a field on one keystroke, which no editor
     * means -- vanilla's own cut copies the highlight, and the highlight of nothing is nothing.
     */
    public String cutText() {
        if (!hasSelection()) {
            return "";
        }
        String cut = selectedText();
        history.before(TextHistory.Edit.WHOLE, state());
        dropSelection();
        return cut;
    }

    /**
     * Takes a clipboard's text as the field's new value, as one edit.
     *
     * <p>Whole-value rather than inserted at the caret, because a field here holds one thing: a hex
     * code, an id, a title, and pasting into the middle of one is not a thing anybody means to do.
     *
     * <p>Control characters are dropped -- a single-line field has nowhere for a newline, and a paste is
     * not exempt from the rule typing follows -- and the limit is the field's own. Text with nothing left
     * after that changes nothing: pasting nothing must not be a way to empty a field. A null text (a
     * clipboard that answered nothing) is the same as an empty one.
     */
    public TextField pasteText(String text) {
        String taken = pasteable(text);
        if (taken.isEmpty()) {
            return this;
        }
        return setValue(taken);
    }

    private static String pasteable(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char each = text.charAt(i);
            if (!Character.isISOControl(each)) {
                kept.append(each);
            }
        }
        return kept.toString();
    }

    /**
     * Replaces the text and puts the caret at the end.
     *
     * <p>At the end, and that is the choice rather than a detail: this is called to fill a field that
     * was empty — an invite box that remembers who was typed last, a field reset to a default — and the
     * useful place to continue from is after what is there. A selection is dropped, because setting the
     * text is a statement about all of it.
     */
    public TextField setValue(String next) {
        Objects.requireNonNull(next, "next");
        history.before(TextHistory.Edit.WHOLE, state());
        value = Codepoints.prefix(next, maxLength);
        caret = value.length();
        anchor = caret;
        return this;
    }

    /** Empties it, caret and all. */
    public TextField clear() {
        if (value.isEmpty()) {
            return this;
        }
        history.before(TextHistory.Edit.WHOLE, state());
        value = "";
        caret = 0;
        anchor = 0;
        return this;
    }

    /**
     * Inserts one character at the caret, if there is room.
     *
     * <p>A character rather than a string because that is what a keyboard delivers: {@code charTyped}
     * hands over one at a time, and a method taking a string would be a method whose every real caller
     * passes a one-character string.
     *
     * <p>Refused rather than truncated when the field is full: dropping the tail of somebody's paste
     * silently loses text they can see they typed, and refusing keeps what is on screen and what is
     * held the same thing. Control characters are refused for the same reason — a newline in a
     * single-line field has to go somewhere, and nowhere is honest.
     *
     * <p>Marked text is replaced, which is what typing over a selection means everywhere else.
     */
    public TextField insert(char typed) {
        if (value.length() - (selectionEnd() - selectionStart()) >= maxLength
                || Character.isISOControl(typed)) {
            return this;
        }
        if (typed == '\n') {
            // A newline closes the run before it and opens the step the next line joins, so one
            // Ctrl+Z takes back the Enter and the line typed after it together: what follows is a new line, and an undo that took the whole
            // paragraph back because the author pressed Enter halfway through it is not what they meant.
            history.breakRun();
        }
        history.before(TextHistory.Edit.TYPING, state());
        dropSelection();
        value = value.substring(0, caret) + typed + value.substring(caret);
        caret++;
        // The anchor travels with the caret: typing is not a selection gesture, and a stale anchor is not a
        // harmless leftover -- it is a mark, and the *next* keystroke would replace the text with it. Every
        // test in this file failed on that one line before it was here.
        anchor = caret;
        return this;
    }

    /** Removes the marked text, or the character before the caret when nothing is marked. */
    public TextField backspace() {
        if (!hasSelection() && caret == 0) {
            return this;
        }
        history.before(TextHistory.Edit.DELETING, state());
        if (hasSelection()) {
            dropSelection();
            return this;
        }
        int from = Codepoints.before(value, caret);
        value = value.substring(0, from) + value.substring(caret);
        caret = from;
        anchor = caret;
        return this;
    }

    /** Removes the marked text, or the character after the caret. {@code Delete}, not Backspace. */
    public TextField deleteForward() {
        if (!hasSelection() && caret >= value.length()) {
            return this;
        }
        history.before(TextHistory.Edit.DELETING, state());
        if (hasSelection()) {
            dropSelection();
            return this;
        }
        int to = Codepoints.after(value, caret);
        value = value.substring(0, caret) + value.substring(to);
        anchor = caret;
        return this;
    }

    public TextField home() {
        history.breakRun();
        caret = 0;
        anchor = 0;
        return this;
    }

    public TextField end() {
        history.breakRun();
        caret = value.length();
        anchor = caret;
        return this;
    }

    /** Left, or — with something marked — to the selection's left edge, which dismisses it. */
    public TextField left() {
        history.breakRun();
        caret = hasSelection() ? selectionStart() : Codepoints.before(value, caret);
        anchor = caret;
        return this;
    }

    /** Right, or to the selection's right edge. */
    public TextField right() {
        history.breakRun();
        caret = hasSelection() ? selectionEnd() : Codepoints.after(value, caret);
        anchor = caret;
        return this;
    }

    /**
     * Puts the caret at a character position, clamped — what a click on the text means.
     *
     * <p>And drops any selection, because a click that says "the caret is here" cannot also mean "and keep
     * what was marked".
     */
    public TextField caretTo(int position) {
        history.breakRun();
        caret = Codepoints.snap(value, position);
        anchor = caret;
        return this;
    }

    /** Removes the marked text and leaves the caret where it started. */
    private void dropSelection() {
        if (!hasSelection()) {
            return;
        }
        int from = selectionStart();
        value = value.substring(0, from) + value.substring(selectionEnd());
        caret = from;
        anchor = from;
    }

    /**
     * The caret position nearest {@code offset} pixels into the value.
     *
     * <h2>Why this is here and not in the widget</h2>
     *
     * <p>Because it is arithmetic on a string and a measuring function, which makes it the one part of
     * "where did I click" that can be asserted without a client -- and it is the part that goes wrong
     * quietly, off by one character, in a way a screenshot shows as a caret that lands in the wrong place.
     * The widget supplies a font's own {@code width}; a test supplies six pixels a character.
     *
     * <p>The nearest boundary wins rather than the last one that fits, so a click in the gap between two
     * letters lands on the nearer of them, which is what every text field does.
     */
    /**
     * Undoes the last edit of this field's session, caret and mark and all.
     *
     * <p>The session's history, not the document's: what the field was opened with is where this starts
     * ({@link #clearHistory}), and the edits an author has *committed* are the chapter's history, which is
     * the screen's Ctrl+Z and belongs to the server. While a field has the keyboard, this is the Z.
     */
    public TextField undo() {
        TextHistory.State state = history.undo(state());
        if (state != null) {
            restore(state);
        }
        return this;
    }

    /** Forward again, after an undo. Dropped by any new edit. */
    public TextField redo() {
        TextHistory.State state = history.redo(state());
        if (state != null) {
            restore(state);
        }
        return this;
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    /** Forgets the history. Called where a field is opened, so its first value is not undoable. */
    public TextField clearHistory() {
        history.clear();
        return this;
    }

    /** The whole of this field's state, which is what a history step records and restores. */
    private TextHistory.State state() {
        return new TextHistory.State(value, caret, anchor);
    }

    private void restore(TextHistory.State state) {
        value = state.value();
        caret = state.caret();
        anchor = state.anchor();
    }

    public int caretForWidth(double offset, ToIntFunction<String> widthOf) {
        String text = value();
        int best = 0;
        double bestDistance = Math.abs(offset);
        for (int i = 1; i <= text.length(); i++) {
            double distance = Math.abs(offset - widthOf.applyAsInt(text.substring(0, i)));
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Whether the caret should be drawn at {@code nowMillis}.
     *
     * <p>On for the first half of each period so a field that has just been focused shows its caret at
     * once, rather than for the first time half a second after the player clicked into it.
     *
     * <p>Here rather than in the widget because it is a comparison against a clock and nothing else —
     * which is exactly the shape of thing that gets written twice, once per text field, and then differs.
     */
    public boolean caretVisible(long nowMillis) {
        return (nowMillis / BLINK_MILLIS) % 2 == 0;
    }
}
