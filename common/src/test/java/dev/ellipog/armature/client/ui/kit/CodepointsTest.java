package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The character rule the two text models share: one code point is one character, however many
 * {@code char}s that takes.
 *
 * <p>Asserted here as well as through the models because it is the class the two models call, and a
 * rule with two callers is the kind that drifts unless it has one home with its own cases -- the
 * clamping, the lone surrogate and the limit between two halves are all easier to read as answers about
 * a string than as answers about a field.
 */
@DisplayName("a character to a caret")
class CodepointsTest {

    /** One character, two chars. */
    private static final String EMOJI = "\uD83D\uDE00";   // check_glyphs: allow -- the subject is the pair, never drawn

    @Test
    @DisplayName("before and after step over a pair whole, and clamp at both ends")
    void steps() {
        String text = "a" + EMOJI + "b";

        assertEquals(3, Codepoints.after(text, 1), "after the pair is three chars in");
        assertEquals(1, Codepoints.before(text, 3), "and before it, from the other side");
        assertEquals(1, Codepoints.after(text, 0), "a plain character is one step");
        assertEquals(0, Codepoints.before(text, 0), "at the start there is nowhere to go");
        assertEquals(4, Codepoints.after(text, 4), "and at the end the same");
        assertEquals(3, Codepoints.before(text, 4), "the 'b' is one character back");
        assertEquals(4, Codepoints.after(text, 99), "an index past the end is clamped, not thrown");
    }

    @Test
    @DisplayName("a lone surrogate is one char, not half a character with a missing half")
    void loneSurrogates() {
        // A truncated clipboard can hold one; it is not a pair, so it is not two chars.
        String lone = "a\uD83Db";   // check_glyphs: allow -- the lone half is the subject, never drawn

        assertEquals(2, Codepoints.after(lone, 1), "a high half with no low half after it steps one");
        assertEquals(1, Codepoints.before(lone, 2));
    }

    @Test
    @DisplayName("a position inside a pair snaps to the pair's start")
    void snapping() {
        String text = "a" + EMOJI + "b";

        assertEquals(1, Codepoints.snap(text, 2), "the middle of a pair is not a place a caret can be");
        assertEquals(3, Codepoints.snap(text, 3), "a real boundary is left alone");
        assertEquals(0, Codepoints.snap(text, -4), "and clamping still applies");
        assertEquals(4, Codepoints.snap(text, 99));
    }

    @Test
    @DisplayName("a prefix never ends in half a character")
    void prefixes() {
        String text = "a" + EMOJI + "b";

        assertEquals("ab", Codepoints.prefix("ab", 5), "nothing to cut when it already fits");
        assertEquals("ab", Codepoints.prefix("ab", 2), "and a limit exactly at the length keeps it");
        assertEquals("a", Codepoints.prefix(text, 2), "a limit between the halves keeps neither half");
        assertEquals("a" + EMOJI, Codepoints.prefix(text, 3), "one past them keeps the pair");
        assertEquals("", Codepoints.prefix(text, 0));
        assertEquals("", Codepoints.prefix(text, -1), "a negative limit is none of it, not an error");
    }
}
