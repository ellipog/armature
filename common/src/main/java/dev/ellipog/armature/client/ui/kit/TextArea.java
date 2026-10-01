package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * A block of text being edited: several lines, one caret, one selection.
 *
 * <h2>The same split as {@link TextField}, one dimension up</h2>
 *
 * <p>Everything worth being wrong about is arithmetic on a string -- where a newline puts the caret,
 * what Backspace deletes at a line's start, which character a click at x pixels means once the text has
 * wrapped. All of that is here, with no font, no pixels and no clock, so a test can hold it. The widget
 * owns the keystrokes and the drawing; this owns the rules.
 *
 * <h2>Wrapping is a view concern, and it is a pure function</h2>
 *
 * <p>The text is stored flat, newlines and all, and the visual lines are {@link #wrap} -- a list of
 * {@link Span}s that <b>tile the text</b>: every character is in exactly one span, in order, with the
 * newlines ending their spans. That tiling is what makes up/down and click-to-caret answerable without
 * a font: a caller measures the spans, and this maps an index to a column and back. The same function
 * the widget draws with is the one the caret math uses, so they cannot disagree about where a line
 * broke.
 *
 * <h2>Enter inserts, Escape ends</h2>
 *
 * <p>A multi-line field cannot commit on Enter -- Enter is a newline. So the commit is the blur, and
 * the widget's Escape ends the edit rather than closing the card; see {@code ArmatureTextArea}.
 */
public final class TextArea {

    private final int maxLength;

    /** How this block's text got to where it is. See {@link TextHistory}. */
    private final TextHistory history = new TextHistory();

    private String value;
    private int caret;

    /** The selection's fixed end. Equal to {@link #caret} whenever there is no selection. */
    private int anchor;

    private TextArea(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        this.value = "";
        this.caret = 0;
        this.anchor = 0;
    }

    /** An empty block that will hold at most {@code maxLength} characters. */
    public static TextArea of(int maxLength) {
        return new TextArea(maxLength);
    }

    // ------------------------------------------------------------------
    // The value and the caret
    // ------------------------------------------------------------------

    public String value() {
        return value;
    }

    public int caret() {
        return caret;
    }

    public int length() {
        return value.length();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    public boolean hasSelection() {
        return anchor != caret;
    }

    public int selectionStart() {
        return Math.min(anchor, caret);
    }

    public int selectionEnd() {
        return Math.max(anchor, caret);
    }

    public String selectedText() {
        return value.substring(selectionStart(), selectionEnd());
    }

    /** Replaces the text and puts the caret at the end. */
    public TextArea setValue(String next) {
        Objects.requireNonNull(next, "next");
        history.before(TextHistory.Edit.WHOLE, state());
        value = next.length() > maxLength ? next.substring(0, maxLength) : next;
        caret = value.length();
        anchor = caret;
        return this;
    }

    public TextArea clear() {
        if (value.isEmpty()) {
            return this;
        }
        history.before(TextHistory.Edit.WHOLE, state());
        value = "";
        caret = 0;
        anchor = 0;
        return this;
    }

    /** Puts the caret at an index, clamped, and drops any selection. */
    public TextArea caretTo(int position) {
        history.breakRun();
        caret = Math.max(0, Math.min(value.length(), position));
        anchor = caret;
        return this;
    }

    public TextArea selectAll() {
        history.breakRun();
        anchor = 0;
        caret = value.length();
        return this;
    }

    public TextArea selectTo(int position) {
        history.breakRun();
        caret = Math.max(0, Math.min(value.length(), position));
        anchor = Math.max(0, Math.min(value.length(), anchor));
        return this;
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /**
     * Inserts one character at the caret.
     *
     * <p>A newline arrives through {@link #insert(char)} like everything else -- {@code '\n'} is a
     * character the keyboard sends -- so there is no second path for Enter to disagree with.
     */
    public TextArea insert(char typed) {
        if (value.length() - (selectionEnd() - selectionStart()) >= maxLength
                || (Character.isISOControl(typed) && typed != '\n')) {
            return this;
        }
        if (typed == '\n') {
            // A newline closes the run before it and opens the step the next line joins, so one
            // Ctrl+Z takes back the Enter and the line typed after it together, as in the one-line field: what follows is a new line.
            history.breakRun();
        }
        history.before(TextHistory.Edit.TYPING, state());
        dropSelection();
        value = value.substring(0, caret) + typed + value.substring(caret);
        caret++;
        anchor = caret;
        return this;
    }

    /** Removes the marked text, or the character before the caret. */
    public TextArea backspace() {
        if (!hasSelection() && caret == 0) {
            return this;
        }
        history.before(TextHistory.Edit.DELETING, state());
        if (hasSelection()) {
            dropSelection();
            return this;
        }
        value = value.substring(0, caret - 1) + value.substring(caret);
        caret--;
        anchor = caret;
        return this;
    }

    /** Removes the marked text, or the character after the caret. */
    public TextArea deleteForward() {
        if (!hasSelection() && caret >= value.length()) {
            return this;
        }
        history.before(TextHistory.Edit.DELETING, state());
        if (hasSelection()) {
            dropSelection();
            return this;
        }
        value = value.substring(0, caret) + value.substring(caret + 1);
        anchor = caret;
        return this;
    }

    /** Left, or to the selection's left edge. */
    public TextArea left() {
        history.breakRun();
        caret = hasSelection() ? selectionStart() : Math.max(0, caret - 1);
        anchor = caret;
        return this;
    }

    /** Right, or to the selection's right edge. */
    public TextArea right() {
        history.breakRun();
        caret = hasSelection() ? selectionEnd() : Math.min(value.length(), caret + 1);
        anchor = caret;
        return this;
    }

    /** To the start of the caret's own line -- the hard line, not the wrapped one. */
    public TextArea home() {
        history.breakRun();
        caret = lineStart(caret);
        anchor = caret;
        return this;
    }

    /** To the end of the caret's own line. */
    public TextArea end() {
        history.breakRun();
        caret = lineEnd(caret);
        anchor = caret;
        return this;
    }

    /**
     * Up one <b>visual</b> line, keeping the column where it was.
     *
     * <p>Visual, so the spans are the caller's: with the text wrapped, "up" means the line above on
     * screen, and a caret that jumped a paragraph would be a caret that ignored the wrapping the
     * reader can see. The column is kept as a distance from the line's start, clamped to the target
     * line's length -- which is what every editor does at a short line.
     */
    public TextArea up(List<Span> lines) {
        history.breakRun();
        int index = lineOf(caret, lines);
        if (index <= 0) {
            return caretTo(0);
        }
        int column = caret - lines.get(index).start();
        Span above = lines.get(index - 1);
        caret = Math.min(above.start() + column, above.end());
        anchor = caret;
        return this;
    }

    /** Down one visual line, keeping the column. See {@link #up}. */
    public TextArea down(List<Span> lines) {
        history.breakRun();
        int index = lineOf(caret, lines);
        if (index >= lines.size() - 1) {
            return caretTo(value.length());
        }
        int column = caret - lines.get(index).start();
        Span below = lines.get(index + 1);
        caret = Math.min(below.start() + column, below.end());
        anchor = caret;
        return this;
    }

    /**
     * The caret index nearest a point in the wrapped text: a visual line and a distance into it.
     *
     * <p>The nearest boundary wins, the same rule {@link TextField#caretForWidth} uses and for the
     * same reason -- a click in the gap between two letters lands on the nearer of them. A point past
     * the end of a line lands at the line's end, which is where a click to the right of the text
     * means.
     */
    public TextArea caretAt(int line, double x, List<Span> lines, ToIntFunction<String> widthOf) {
        history.breakRun();
        return caretTo(indexAt(line, x, lines, widthOf));
    }

    /**
     * Which character index a point means, <b>without touching the caret or the mark</b>.
     *
     * <h2>Why this is separate from {@link #caretAt}, and why it had to be</h2>
     *
     * <p>Because the two gestures that share this arithmetic need two different things done with the
     * answer. A <b>click</b> puts the caret there and drops whatever was marked. A <b>drag</b> moves the
     * caret there and keeps the mark's other end where the press put it -- and the block's drag was
     * written as {@code selectTo(caretAt(...))}, which is a selection that can never be anything: the
     * {@code caretAt} inside it ends in {@link #caretTo}, and {@code caretTo} drops the anchor, so
     * {@code selectTo} was handed a fresh anchor equal to the caret on every step. The mark came out empty
     * every time, which is why dragging selected nothing in the description while the one-line field --
     * whose {@code TextField.caretForWidth} is pure -- selected fine.
     *
     * <p>So the point-to-index arithmetic is here, pure, and the callers choose: {@code caretTo} for a
     * click, {@code selectTo} for a drag. One arithmetic, two gestures.
     */
    public int indexAt(int line, double x, List<Span> lines, ToIntFunction<String> widthOf) {
        if (lines.isEmpty()) {
            return 0;
        }
        Span span = lines.get(Math.max(0, Math.min(line, lines.size() - 1)));
        String text = span.text(value);
        int best = 0;
        double bestDistance = Math.abs(x);
        for (int i = 1; i <= text.length(); i++) {
            double distance = Math.abs(x - widthOf.applyAsInt(text.substring(0, i)));
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return span.start() + best;
    }

    /**
     * Which visual line an index sits on.
     *
     * <p>The <b>last span that starts at or before the index</b>, and the direction matters: a wrap
     * boundary belongs to the line it starts, not to the line it ended -- a caret at index 4 in
     * "one two" (spans [0,4) and [4,7)) is at the start of "two", and attributing it to the line above
     * made Down move by zero and Up skip a line. An empty line's span starts where the previous one
     * ended, so the empty line owns its index -- which is what makes the caret land on a blank line
     * rather than on the end of the line before it.
     */
    /**
     * Undoes the last edit of this block's session, caret and mark and all.
     *
     * <p>The same split as the one-line field: this is the session's history, and what the author
     * *committed* on blur is the chapter's history, which is the screen's Ctrl+Z and lives on the server.
     * While the block has the keyboard, this is the Z.
     */
    public TextArea undo() {
        TextHistory.State state = history.undo(state());
        if (state != null) {
            restore(state);
        }
        return this;
    }

    /** Forward again, after an undo. Dropped by any new edit. */
    public TextArea redo() {
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

    /** Forgets the history. Called where a block is opened, so its first value is not undoable. */
    public TextArea clearHistory() {
        history.clear();
        return this;
    }

    /** The whole of this block's state, which is what a history step records and restores. */
    private TextHistory.State state() {
        return new TextHistory.State(value, caret, anchor);
    }

    private void restore(TextHistory.State state) {
        value = state.value();
        caret = state.caret();
        anchor = state.anchor();
    }

    public static int lineOf(int index, List<Span> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (index >= lines.get(i).start()) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Where a visual line starts, measured from the top of the first: a line height per line above it,
     * plus one {@code paragraphGap} for every hard break between.
     *
     * <h2>Why the advance is here and not in the widget's loop</h2>
     *
     * <p>Because the caller that needs a pitch of its own is drawing prose at a reader's -- the quest
     * editor's description stands in for text the overlay laid out at {@code LINE_HEIGHT} a line with
     * {@code PARAGRAPH_GAP} between paragraphs -- and a line drawn one pixel or one gap away from where
     * the reader draws it moves every time the field is clicked. The arithmetic is geometry, so it is
     * asserted without a client, and the widget only adds pixels.
     *
     * <p>A soft wrap is not a hard line: a wrapped paragraph's own lines are {@code lineHeight} apart,
     * and only a newline costs the gap.
     */
    public static int lineTop(List<Span> lines, int index, int lineHeight, int paragraphGap) {
        int clamped = Math.max(0, Math.min(index, lines.size() - 1));
        int top = 0;
        for (int i = 1; i <= clamped; i++) {
            top += lineHeight + (startsParagraph(lines, i) ? paragraphGap : 0);
        }
        return top;
    }

    /** Whether a visual line begins a hard line -- a paragraph, rather than a wrap of the one above. */
    private static boolean startsParagraph(List<Span> lines, int index) {
        return index > 0 && lines.get(index - 1).hard();
    }

    /**
     * The visual line a point is on, given the same advance: the first line whose bottom edge is below
     * it, so a point in a paragraph gap belongs to the line underneath the gap.
     *
     * <p>Past the block is its last line, and above it is the first: a click outside the text still has
     * to mean the nearest end of it rather than nothing.
     */
    public static int lineAt(List<Span> lines, int y, int lineHeight, int paragraphGap) {
        for (int i = 0; i < lines.size(); i++) {
            if (y < lineTop(lines, i, lineHeight, paragraphGap) + lineHeight) {
                return i;
            }
        }
        return Math.max(0, lines.size() - 1);
    }

    /**
     * How many lines starting at {@code from} fit in {@code pixels}, by the same advance.
     *
     * <p>Zero is a real answer -- no room is no lines -- and deliberately not clamped to one here: a
     * caller that draws decides its own floor, because how many lines to draw when none fit is a
     * drawing decision and not this rule's.
     */
    public static int linesThatFit(List<Span> lines, int from, int pixels, int lineHeight, int paragraphGap) {
        int base = lineTop(lines, from, lineHeight, paragraphGap);
        int count = 0;
        for (int i = Math.max(0, from); i < lines.size(); i++) {
            if (lineTop(lines, i, lineHeight, paragraphGap) - base >= pixels) {
                break;
            }
            count++;
        }
        return count;
    }

    /** The start of the hard line containing an index. */
    public int lineStart(int index) {
        int at = Math.max(0, Math.min(value.length(), index));
        int newline = value.lastIndexOf('\n', Math.max(0, at - 1));
        return newline + 1;
    }

    /** The end of the hard line containing an index -- where its newline is, or the text's end. */
    public int lineEnd(int index) {
        int at = Math.max(0, Math.min(value.length(), index));
        int newline = value.indexOf('\n', at);
        return newline < 0 ? value.length() : newline;
    }

    /**
     * Whether the caret should be drawn at {@code nowMillis}.
     *
     * <p>On for the first half of each period so a block that has just been focused shows its caret at
     * once. The same rule and the same reason as {@link TextField#caretVisible}: a comparison against
     * a clock, which is the shape of thing that gets written twice and then differs.
     */
    public boolean caretVisible(long nowMillis) {
        return (nowMillis / 500L) % 2 == 0;
    }

    private void dropSelection() {
        if (!hasSelection()) {
            return;
        }
        int from = selectionStart();
        value = value.substring(0, from) + value.substring(selectionEnd());
        caret = from;
        anchor = from;
    }

    // ------------------------------------------------------------------
    // The visual lines
    // ------------------------------------------------------------------

    /**
     * One visual line: a span of the text, and whether it ends because of a newline or a wrap.
     *
     * <p>{@code end} is exclusive and never includes the newline character; the next span starts just
     * after it. The spans tile the text in order, so the caret index is the only coordinate anything
     * needs.
     */
    public record Span(int start, int end, boolean hard) {

        public String text(String whole) {
            return whole.substring(start, end);
        }
    }

    /**
     * The text's visual lines at a width: hard lines from newlines, each wrapped by the measure.
     *
     * <p>Wrapping is <b>lossless</b>: no character is dropped, no space is swallowed, and the spans
     * tile the text. That is the property the caret math depends on -- a wrapper that trimmed a
     * leading space (as a prose wrapper may) would shift every index after it and a click would land
     * on the wrong character. A word longer than the width is hard-broken, because the alternative is
     * a line that runs off the box.
     */
    public static List<Span> wrap(String text, int width, ToIntFunction<String> widthOf) {
        List<Span> spans = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int newline = text.indexOf('\n', start);
            int hardEnd = newline < 0 ? text.length() : newline;
            wrapHardLine(text, start, hardEnd, Math.max(1, width), widthOf, spans);
            if (newline < 0) {
                break;
            }
            start = newline + 1;
            if (start > text.length()) {
                break;
            }
        }
        if (spans.isEmpty()) {
            spans.add(new Span(0, 0, true));
        }
        return List.copyOf(spans);
    }

    /** Wraps one hard line -- between two newlines -- into spans, greedily at spaces. */
    private static void wrapHardLine(String text, int from, int to, int width,
                                     ToIntFunction<String> widthOf, List<Span> spans) {
        int at = from;
        while (true) {
            String remaining = text.substring(at, to);
            if (widthOf.applyAsInt(remaining) <= width) {
                spans.add(new Span(at, to, true));
                return;
            }
            // The furthest prefix that fits, then back up to the last space in it so words survive.
            int fits = 0;
            for (int i = 1; i <= remaining.length(); i++) {
                if (widthOf.applyAsInt(remaining.substring(0, i)) > width) {
                    break;
                }
                fits = i;
            }
            if (fits <= 0) {
                fits = 1;   // a single character wider than the box still gets its own line
            }
            int space = remaining.lastIndexOf(' ', fits - 1);
            if (space > 0) {
                // Break at the space and keep it as the span's last character: the spans have to
                // tile the text, and a swallowed space would shift every index after it -- a click
                // would land one character off for the rest of the block.
                spans.add(new Span(at, at + space + 1, false));
                at += space + 1;
            }
            else {
                spans.add(new Span(at, at + fits, false));
                at += fits;
            }
        }
    }
}
