package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wrap rules, asserted rather than looked at.
 *
 * <p>Every case here is one of the rules {@link TextWrap}'s own javadoc states, which is the point: a
 * rule written down and not tested is a rule that quietly stops being true, and the failure mode of a
 * wrap is a scrollbar one line short rather than an exception.
 *
 * <p>The measure is {@link Measure#monospace}, so every expected line can be counted by hand. A
 * proportional fake would let a wrong-by-one break hide behind a plausible-looking width.
 */
class TextWrapTest {

    /** Six pixels a character, ten pixels a line. A line is then "as many characters as fit in sixths". */
    private static final Measure SIX = Measure.monospace(6, 10);

    @Test
    void aLineBreaksAtASpace() {
        // 30 pixels is five characters: "alpha" fits, "alpha beta" does not.
        assertEquals(List.of("alpha", "beta"), TextWrap.wrap("alpha beta", 30, SIX));
    }

    @Test
    void aWordThatFitsIsNotSplit() {
        assertEquals(List.of("abc def"), TextWrap.wrap("abc def", 42, SIX));
    }

    @Test
    void aNewlineIsHonouredEvenWhenTheTextWouldFit() {
        // The whole point of an explicit break: the author asked for it.
        assertEquals(List.of("one", "two"), TextWrap.wrap("one\ntwo", 300, SIX));
    }

    @Test
    void aBlankLineTakesHeight() {
        // Not dropped, because dropping it would silently change the paragraph spacing of every
        // description that contains one -- and the measure and the draw would then have to agree about
        // it separately, which is the defect this class exists to remove.
        assertEquals(List.of("one", "", "two"), TextWrap.wrap("one\n\ntwo", 300, SIX));
    }

    @Test
    void aSingleNewlineIsTwoEmptyParagraphs() {
        assertEquals(List.of("", ""), TextWrap.wrap("\n", 300, SIX));
    }

    @Test
    void aTrailingNewlineIsNotDiscarded() {
        // "one\n" and "one" must not measure the same: the blank line the author wrote at the end is
        // still in the file.
        assertEquals(List.of("one", ""), TextWrap.wrap("one\n", 300, SIX));
    }

    @Test
    void emptyInputYieldsNoLines() {
        assertTrue(TextWrap.wrap("", 100, SIX).isEmpty());
    }

    @Test
    void aWordWiderThanTheColumnIsSplit() {
        // Splitting is the only one of overflow/drop/split the reader can act on. Overflowing draws
        // through the edge; dropping loses content with nothing said.
        assertEquals(List.of("abcd", "efgh", "ij"), TextWrap.wrap("abcdefghij", 24, SIX));
    }

    @Test
    void aWordWiderThanTheColumnStartsOnALineOfItsOwn() {
        // The current line ends before the long word rather than carrying the two or three characters
        // that happen to fit. Both readings are defensible, and this one is chosen for a reason worth
        // stating: the alternative puts a fragment of a word on a line with the words before it, which
        // reads as a hyphenation fault rather than as a long word. Four characters fit in 24 pixels.
        //
        // My first version asserted the other behaviour — "hi a" then "bcde" — which is the shape this
        // test is named against rather than the shape the kit has. The rule is now in TextWrap's own
        // javadoc, so the code and the test say the same thing.
        assertEquals(List.of("hi", "abcd", "efgh"), TextWrap.wrap("hi abcdefgh", 24, SIX));
    }

    @Test
    void aRunOfSpacesIsOneBreak() {
        // A double space left in would make the line wider than the sum of its words, which is exactly
        // the stray pixel that turns into a scrollbar one line short.
        assertEquals(List.of("aa bb"), TextWrap.wrap("aa   bb", 100, SIX));
    }

    @Test
    void leadingAndTrailingSpaceIsDropped() {
        assertEquals(List.of("aa"), TextWrap.wrap("   aa   ", 100, SIX));
    }

    @Test
    void aColumnNarrowerThanOneCharacterStillMakesProgress() {
        // The only way to reach this is a panel dragged down to nothing. It must not hang, and a line
        // one character too wide is a better failure than a loop that never ends.
        assertEquals(List.of("a", "b"), TextWrap.wrap("ab", 0, SIX));
        assertEquals(List.of("a", "b"), TextWrap.wrap("ab", 5, SIX));
    }

    @Test
    void heightIsTheWrappedLineCount() {
        // height() calls wrap() rather than predicting it, so this test is really asserting that the
        // two have not been allowed to become separate answers.
        String text = "alpha beta gamma delta";
        int lines = TextWrap.wrap(text, 60, SIX).size();
        assertEquals(lines * SIX.lineHeight(), TextWrap.height(text, 60, SIX));
    }

    @Test
    void heightOfNothingIsNothing() {
        assertEquals(0, TextWrap.height("", 100, SIX));
    }

    @Test
    void aNullArgumentIsRefused() {
        assertThrows(NullPointerException.class, () -> TextWrap.wrap(null, 10, SIX));
        assertThrows(NullPointerException.class, () -> TextWrap.wrap("x", 10, null));
    }

    @Test
    void everyLineFitsTheColumnExceptWhenNothingCan() {
        // The general invariant, swept rather than spot-checked: at any width where the font's own
        // characters fit at all, no line comes out wider than the column.
        for (int width = 12; width <= 120; width += 6) {
            for (String line : TextWrap.wrap("the quick brown fox jumps over the lazy dog", width, SIX)) {
                assertTrue(SIX.width(line) <= width,
                        "line \"" + line + "\" is " + SIX.width(line) + " wide, column is " + width);
            }
        }
    }
}
