package dev.ellipog.armature.client.ui.kit;

import java.util.Objects;

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
 * <h2>What is deliberately not here</h2>
 * <p>
 * <b>Selection.</b> A range and an anchor would double every method below — every edit has to replace
 * the selection first, and the caret has to know whether it is an end of one — and nothing in either mod
 * needs it yet. What is here is what typing, deleting and arrowing do; a caller wanting
 * select-and-replace wants a different class, not a flag on this one.
 */
public final class TextField {

    /** How long the caret is on, and off, in the usual rhythm. */
    private static final long BLINK_MILLIS = 500L;

    private final int maxLength;
    private String value;
    private int caret;

    private TextField(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        this.value = "";
        this.caret = 0;
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

    /**
     * Replaces the text and puts the caret at the end.
     *
     * <p>At the end, and that is the choice rather than a detail: this is called to fill a field that
     * was empty — an invite box that remembers who was typed last, a field reset to a default — and the
     * useful place to continue from is after what is there.
     */
    public TextField setValue(String next) {
        Objects.requireNonNull(next, "next");
        value = next.length() > maxLength ? next.substring(0, maxLength) : next;
        caret = value.length();
        return this;
    }

    /** Empties it, caret and all. */
    public TextField clear() {
        value = "";
        caret = 0;
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
     */
    public TextField insert(char typed) {
        if (value.length() >= maxLength || Character.isISOControl(typed)) {
            return this;
        }
        value = value.substring(0, caret) + typed + value.substring(caret);
        caret++;
        return this;
    }

    /** Removes the character before the caret, if there is one. */
    public TextField backspace() {
        if (caret == 0) {
            return this;
        }
        value = value.substring(0, caret - 1) + value.substring(caret);
        caret--;
        return this;
    }

    /** Removes the character after the caret, if there is one. {@code Delete}, not Backspace. */
    public TextField deleteForward() {
        if (caret >= value.length()) {
            return this;
        }
        value = value.substring(0, caret) + value.substring(caret + 1);
        return this;
    }

    public TextField home() {
        caret = 0;
        return this;
    }

    public TextField end() {
        caret = value.length();
        return this;
    }

    public TextField left() {
        caret = Math.max(0, caret - 1);
        return this;
    }

    public TextField right() {
        caret = Math.min(value.length(), caret + 1);
        return this;
    }

    /** Puts the caret at a character position, clamped — what a click on the text means. */
    public TextField caretTo(int position) {
        caret = Math.max(0, Math.min(value.length(), position));
        return this;
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
    public int caretForWidth(double offset, java.util.function.ToIntFunction<String> widthOf) {
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

    public boolean caretVisible(long nowMillis) {
        return (nowMillis / BLINK_MILLIS) % 2 == 0;
    }
}
