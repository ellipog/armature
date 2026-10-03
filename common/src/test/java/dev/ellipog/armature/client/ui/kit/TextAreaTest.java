package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-line editor's rules: what Enter does, where a click lands once the text has wrapped, and
 * what up and down mean across a wrap.
 *
 * <h2>Why the tiling property is asserted before anything else</h2>
 *
 * <p>Because every other answer is derived from it. The spans are the only thing joining an index to a
 * screen position, and a wrapper that swallowed a space or dropped an empty line would shift every
 * index after it -- a click one character off, for the rest of the block, with nothing thrown. So the
 * first test asks the property itself: the spans cover the text in order, and the newlines are exactly
 * the characters between them.
 */
@DisplayName("the multi-line text model")
class TextAreaTest {

    /** Six pixels a character: the same stand-in the TextField tests measure with. */
    private static int width(String text) {
        return text.length() * 6;
    }

    private static List<TextArea.Span> wrap(String text, int pixels) {
        return TextArea.wrap(text, pixels, TextAreaTest::width);
    }

    // ------------------------------------------------------------------
    // The wrapping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the spans tile the text: every character in one span, in order, newlines between")
    void spansTileTheText() {
        String[] samples = {
                "", "one line", "one\ntwo", "one\n\ntwo\n", "\n", "a very long line that will have to wrap somewhere",
                "word word word word word word word word", "leading  double  spaces", "trailing space \n"
        };
        for (String text : samples) {
            for (int pixels : new int[] {6, 36, 60, 1000}) {
                List<TextArea.Span> spans = wrap(text, pixels);
                assertFalse(spans.isEmpty(), () -> "no spans for \"" + text + "\"");

                int expected = 0;
                for (TextArea.Span span : spans) {
                    assertEquals(expected, span.start(),
                            () -> "a gap or an overlap before a span in \"" + text + "\"");
                    assertTrue(span.end() >= span.start(), "an inverted span");
                    // Every skipped character is a newline: that is what "newlines between spans" means.
                    for (int i = expected; i < span.start(); i++) {
                        assertEquals('\n', text.charAt(i), "a non-newline between spans");
                    }
                    expected = span.end() + (span.hard() && span.end() < text.length()
                            && text.charAt(span.end()) == '\n' ? 1 : 0);
                    if (!span.hard()) {
                        expected = span.end();
                    }
                }
                // Whatever the last span left over is newlines or nothing.
                for (int i = Math.min(expected, text.length()); i < text.length(); i++) {
                    assertEquals('\n', text.charAt(i), "a non-newline after the last span");
                }
            }
        }
    }

    @Test
    @DisplayName("a long line breaks at its last space that fits, and the space stays in the span")
    void breaksAtSpaces() {
        // 36px = six characters a line. "one two three" breaks after "one " and "two ".
        List<TextArea.Span> spans = wrap("one two three", 36);
        assertEquals(3, spans.size());
        assertEquals("one ", spans.get(0).text("one two three"), "the space rides at the line's end");
        assertEquals("two ", spans.get(1).text("one two three"));
        assertEquals("three", spans.get(2).text("one two three"));
    }

    @Test
    @DisplayName("a word longer than the line is hard-broken, not run off the box")
    void hardBreaksLongWords() {
        List<TextArea.Span> spans = wrap("abcdefghij", 24);
        assertEquals(List.of("abcd", "efgh", "ij"), spans.stream()
                .map(span -> span.text("abcdefghij")).toList());
        assertFalse(spans.get(0).hard() || spans.get(1).hard(),
                "the first two breaks are wraps -- the flag says \"this line ended the hard line\"");
        assertTrue(spans.get(2).hard(), "and the last one ends it, because the text does");
    }

    @Test
    @DisplayName("empty lines and a trailing newline are lines, not nothing")
    void emptyLinesAreLines() {
        List<TextArea.Span> spans = wrap("a\n\nb", 600);
        assertEquals(3, spans.size());
        assertEquals("", spans.get(1).text("a\n\nb"), "the empty line is there to put the caret on");
        assertEquals("b", spans.get(2).text("a\n\nb"));

        assertEquals(2, wrap("a\n", 600).size(), "a trailing newline leaves an empty line after it");
    }

    // ------------------------------------------------------------------
    // The editing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Enter is a character, and Backspace at a line's start joins the lines")
    void enterAndJoin() {
        TextArea area = TextArea.of(200).setValue("one");

        area.insert('\n');
        assertEquals("one\n", area.value());
        assertEquals(4, area.caret(), "the caret sits on the new empty line");

        area.insert('t');
        area.insert('w');
        assertEquals("one\ntw", area.value());

        area.home();
        assertEquals(4, area.caret(), "home is the start of the caret's own line");
        area.backspace();
        assertEquals("onetw", area.value(), "backspace at a line's start joins the lines");
        assertEquals(3, area.caret());
    }

    @Test
    @DisplayName("home and end are the hard line's ends, not the wrapped one's")
    void homeAndEndAreHardLines() {
        TextArea area = TextArea.of(200).setValue("first\nsecond line");
        area.caretTo(9);   // inside "second"

        area.home();
        assertEquals(6, area.caret());
        area.end();
        assertEquals(17, area.caret());
        assertEquals("first\nsecond line".length(), area.caret());
    }

    @Test
    @DisplayName("up and down move a visual line and keep the column, clamped at short lines")
    void upAndDownFollowTheWrap() {
        // Six characters a line: "one two" wraps to "one " [0,4) + "two" [4,7), then "three" [8,13).
        TextArea area = TextArea.of(200).setValue("one two\nthree");
        List<TextArea.Span> spans = wrap(area.value(), 36);
        assertEquals(List.of("one ", "two", "three"), spans.stream()
                .map(span -> span.text(area.value())).toList());

        // Column 0 of "three" goes up to column 0 of "two", then of "one ".
        area.caretTo(8);
        area.up(spans);
        assertEquals(4, area.caret(), "up from 'three' lands on 'two' -- the wrapped line above");
        area.up(spans);
        assertEquals(0, area.caret(), "and up again to the first visual line");

        // A column past the shorter line's width clamps to its end: column 4 of "three" is index 12,
        // and the line above is only three characters long.
        area.caretTo(12);
        area.up(spans);
        assertEquals(7, area.caret(), "the column clamps to the shorter line's end");

        // Down past the last visual line goes to the end of the text.
        area.caretTo(0);
        area.down(spans);
        area.down(spans);
        area.down(spans);
        assertEquals(area.value().length(), area.caret());
    }

    @Test
    @DisplayName("a click maps to the nearest character boundary on its line")
    void clicksLandOnTheNearestBoundary() {
        String text = "one two\nthree";
        List<TextArea.Span> spans = wrap(text, 600);
        TextArea area = TextArea.of(200).setValue(text);

        area.caretAt(1, 0, spans, TextAreaTest::width);
        assertEquals(8, area.caret(), "x=0 on the second line is its start");
        area.caretAt(1, 12, spans, TextAreaTest::width);
        assertEquals(10, area.caret(), "12px is two characters in");
        area.caretAt(1, 999, spans, TextAreaTest::width);
        assertEquals(text.length(), area.caret(), "past the end is the line's end");
        area.caretAt(0, 0, spans, TextAreaTest::width);
        assertEquals(0, area.caret());
    }

    @Test
    @DisplayName("a selection is replaced by typing, and an edit collapses it")
    void selectionsBehave() {
        TextArea area = TextArea.of(200).setValue("hello world");
        area.caretTo(5);
        area.selectTo(11);
        assertEquals(" world", area.selectedText());

        area.insert('!');
        assertEquals("hello!", area.value(), "typing replaces the selection");
        assertFalse(area.hasSelection());

        area.selectAll();
        area.backspace();
        assertEquals("", area.value(), "backspace with everything marked clears the block");
    }

    @Test
    @DisplayName("the block respects its length limit, newlines included")
    void theLimitHolds() {
        TextArea area = TextArea.of(5).setValue("abc");
        area.insert('d');
        area.insert('e');
        area.insert('f');
        assertEquals("abcde", area.value(), "the sixth character is refused, not truncated silently");
        area.insert('\n');
        assertEquals("abcde", area.value(), "a newline is a character like any other");
    }

    // ------------------------------------------------------------------
    // The clipboard, and a character outside the basic plane
    // ------------------------------------------------------------------

    /** One character, two chars: what an input method outside the basic plane commits. */
    private static final String EMOJI = "\uD83D\uDE00";   // check_glyphs: allow -- the subject is the pair, never drawn

    @Test
    @DisplayName("a paste arrives at the caret, replaces the mark, and is one step back")
    void pasteIsOneEdit() {
        TextArea area = TextArea.of(200).setValue("one two").clearHistory();
        area.caretTo(3);

        area.pasteText("and\nthree ");
        assertEquals("oneand\nthree  two", area.value(), "at the caret, newline and all");
        area.undo();
        assertEquals("one two", area.value(), "and the whole paste is one press back, not one character");

        area.selectAll();
        area.pasteText("0123456789");
        assertEquals("0123456789", area.value(), "a paste over a mark replaces it");
    }

    @Test
    @DisplayName("a clipboard's carriage returns go, its newlines stay, and the limit holds")
    void pasteFiltersAndClamps() {
        TextArea area = TextArea.of(64);
        area.pasteText("one\r\ntwo");
        assertEquals("one\ntwo", area.value(), "a Windows clipboard's carriage return goes; the newline is a line");

        TextArea full = TextArea.of(5).setValue("abc").clearHistory();
        full.selectAll().pasteText("0123456789");
        assertEquals("01234", full.value(), "stopping at the limit rather than overflowing");
        assertEquals(5, full.caret());

        TextArea untouched = TextArea.of(64).setValue("kept").clearHistory();
        untouched.pasteText("\r");
        assertEquals("kept", untouched.value(),
                "a paste with nothing left after the filter changes nothing");
        assertFalse(untouched.canUndo());
    }

    @Test
    @DisplayName("a copy takes the mark or the whole block, and a cut takes the mark only")
    void copyAndCut() {
        TextArea area = TextArea.of(200).setValue("first\nsecond").clearHistory();
        assertEquals("first\nsecond", area.copyText(), "nothing marked: the block is the thing a copy takes");

        area.caretTo(0).selectTo(5);
        assertEquals("first", area.copyText());
        assertEquals("first", area.cutText());
        assertEquals("\nsecond", area.value(), "the mark went, nothing else");
        area.undo();
        assertEquals("first\nsecond", area.value(), "one edit, one undo");

        TextArea unmarked = TextArea.of(200).setValue("kept").clearHistory();
        assertEquals("", unmarked.cutText(), "nothing marked is nothing to cut");
        assertEquals("kept", unmarked.value());
        assertFalse(unmarked.canUndo());
    }

    @Test
    @DisplayName("a character outside the basic plane is one character to the caret and to the break")
    void aPairIsOneCharacter() {
        TextArea area = TextArea.of(200).setValue("a" + EMOJI + "b").clearHistory();
        area.caretTo(3).backspace();
        assertEquals("ab", area.value(), "backspace beside a pair takes the whole character");
        assertEquals(1, area.caret());

        TextArea snapped = TextArea.of(200).setValue("a" + EMOJI + "b");
        snapped.caretTo(2);
        assertEquals(1, snapped.caret(), "the middle of a pair is not a caret position");

        // Two characters a line (6px each): the pair moves to the next line whole rather than half a
        // glyph heading one -- and when the pair alone is wider than the line, it is taken anyway, the
        // same failure any single character too wide gets.
        assertEquals(List.of("a", EMOJI, "b"), wrap("a" + EMOJI + "b", 12).stream()
                        .map(span -> span.text("a" + EMOJI + "b")).toList(),
                "the break moved off the pair rather than through it");
        assertEquals(List.of(EMOJI, "b"), wrap(EMOJI + "b", 6).stream()
                        .map(span -> span.text(EMOJI + "b")).toList(),
                "a pair that cannot fit is one line too wide, not two lines of half a glyph");
    }

    // ------------------------------------------------------------------
    // The two gestures: a click moves the caret, a drag extends the mark
    // ------------------------------------------------------------------

    /**
     * The bug that made dragging select nothing in the description, asserted so it cannot come back.
     *
     * <p>The block's drag was {@code selectTo(caretAt(...))}, and {@code caretAt} ends in {@code caretTo},
     * which drops the anchor -- so {@code selectTo} was handed an anchor equal to the caret every time, and
     * the mark was empty every time. The one-line field was fine because its point-to-index call is pure,
     * which is exactly what {@code indexAt} is here: the arithmetic, with the caller deciding whether the
     * answer is a click's caret or a drag's other end.
     */
    @Test
    @DisplayName("a drag extends the mark from the press, and a click drops it")
    void aDragKeepsTheAnchorAndAClickDoesNot() {
        String text = "one two";
        List<TextArea.Span> spans = wrap(text, 600);
        TextArea area = TextArea.of(200).setValue(text);
        area.caretTo(0);

        // A click at index 3: the caret goes there, no mark.
        area.caretTo(area.indexAt(0, 18, spans, TextAreaTest::width));
        assertEquals(3, area.caret());
        assertFalse(area.hasSelection());

        // A drag from there to a later point: the mark runs from the click to the drag, and stays that way
        // through every step of the drag.
        area.selectTo(area.indexAt(0, 42, spans, TextAreaTest::width));
        assertEquals(7, area.caret());
        assertTrue(area.hasSelection(), "the drag left a mark");
        assertEquals(3, area.selectionStart());
        assertEquals(7, area.selectionEnd());
        assertEquals(" two", area.selectedText(), "from the press's character to the drag's");

        // Another step of the same drag extends it from the same anchor.
        area.selectTo(area.indexAt(0, 24, spans, TextAreaTest::width));
        assertEquals(3, area.selectionStart(), "the anchor is still the press");
        assertEquals(4, area.selectionEnd());

        // And `caretAt` -- the click's answer -- really does drop it, which is why a drag must not use it.
        area.caretAt(0, 42, spans, TextAreaTest::width);
        assertFalse(area.hasSelection(), "a click drops the mark");
        assertEquals(7, area.caret());
    }

    @Test
    @DisplayName("reading a point mutates nothing")
    void indexAtIsPure() {
        String text = "one two";
        List<TextArea.Span> spans = wrap(text, 600);
        TextArea area = TextArea.of(200).setValue(text);
        area.caretTo(2);

        assertEquals(5, area.indexAt(0, 30, spans, TextAreaTest::width));
        assertEquals(2, area.caret(), "the caret did not move");
        assertFalse(area.hasSelection(), "and nothing was marked");
    }

    // ------------------------------------------------------------------
    // The advance. Where each visual line sits, for a caller drawing at a pitch of its own.
    // ------------------------------------------------------------------

    /**
     * The widget draws at a caller's pitch: a line height of its own and a gap between paragraphs. The
     * caller that needs it is the editor's description, which must draw the text it is editing
     * exactly where the reader draws prose -- {@code OverlayLayout.LINE_HEIGHT} a line and
     * {@code PARAGRAPH_GAP} between paragraphs -- or every line moves the moment the field is clicked.
     *
     * <p>Asserted here rather than in the widget because it is arithmetic; the widget only adds pixels.
     */
    @Test
    @DisplayName("lines advance by the line height, and a hard line advances by the paragraph gap too")
    void paragraphGapsSeparateHardLines() {
        List<TextArea.Span> spans = wrap("one\ntwo\nthree", 600);
        assertEquals(3, spans.size(), "three hard lines, nothing wrapped");

        assertEquals(0, TextArea.lineTop(spans, 0, 10, 5));
        assertEquals(15, TextArea.lineTop(spans, 1, 10, 5), "the next paragraph starts after the gap");
        assertEquals(30, TextArea.lineTop(spans, 2, 10, 5), "the gap is between every pair, not just once");

        // A soft wrap inside one paragraph gets no gap: a paragraph's own lines are lineHeight apart.
        List<TextArea.Span> wrapped = wrap("aaaa bbbb cccc", 60);
        assertEquals(2, wrapped.size(), "one paragraph wrapped in two");
        assertEquals(0, TextArea.lineTop(wrapped, 0, 10, 5));
        assertEquals(10, TextArea.lineTop(wrapped, 1, 10, 5), "a wrapped line is not a new paragraph");

        // A wrapped paragraph followed by another: the gap comes after the whole paragraph, not its
        // first line -- which is where the reader puts the next paragraph's slot.
        List<TextArea.Span> mixed = wrap("aaaa bbbb cccc\nsecond", 60);
        assertEquals(3, mixed.size(), "two wrapped lines and a hard one");
        assertEquals(25, TextArea.lineTop(mixed, 2, 10, 5), "two lines and one gap");

        // With no gap configured it is a plain multiple, which is what a caller wanting no paragraph
        // spacing asks for.
        assertEquals(20, TextArea.lineTop(spans, 2, 10, 0));
    }

    @Test
    @DisplayName("a point maps to the line it is above, and a point in a gap to the line below it")
    void lineAtInvertsTheAdvance() {
        List<TextArea.Span> spans = wrap("one\ntwo\nthree", 600);

        assertEquals(0, TextArea.lineAt(spans, 0, 10, 5));
        assertEquals(0, TextArea.lineAt(spans, 9, 10, 5), "the last pixel of the first line is on it");
        assertEquals(1, TextArea.lineAt(spans, 10, 10, 5), "the gap above a line is that line's");
        assertEquals(1, TextArea.lineAt(spans, 15, 10, 5));
        assertEquals(1, TextArea.lineAt(spans, 24, 10, 5), "the last pixel of the second line is on it");
        assertEquals(2, TextArea.lineAt(spans, 25, 10, 5), "and the next gap belongs to the line below");
        assertEquals(2, TextArea.lineAt(spans, 999, 10, 5), "past the block is the last line");
        assertEquals(0, TextArea.lineAt(spans, -5, 10, 5), "above the block is the first line");
    }

    @Test
    @DisplayName("how many lines fit is measured with the same advance, from the line given")
    void linesThatFitUsesTheAdvance() {
        List<TextArea.Span> spans = wrap("one\ntwo\nthree", 600);

        assertEquals(3, TextArea.linesThatFit(spans, 0, 35, 10, 5), "all three: the last top is 30");
        assertEquals(2, TextArea.linesThatFit(spans, 0, 30, 10, 5), "a line whose top is the boundary is below it");
        assertEquals(1, TextArea.linesThatFit(spans, 0, 15, 10, 5), "fifteen pixels is one line and the gap");
        assertEquals(0, TextArea.linesThatFit(spans, 0, 0, 10, 5), "no room is no lines -- the caller sets its floor");
        assertEquals(1, TextArea.linesThatFit(spans, 2, 100, 10, 5), "from the last line, one fits");
        assertEquals(0, TextArea.linesThatFit(spans, 3, 100, 10, 5), "and there is no line after it");

        List<TextArea.Span> wrapped = wrap("aaaa bbbb cccc", 60);
        assertEquals(2, TextArea.linesThatFit(wrapped, 0, 20, 10, 5), "a wrapped paragraph has no gap in it");
    }

    // ------------------------------------------------------------------
    // The history
    // ------------------------------------------------------------------

    @Test
    @DisplayName("typing is one step, and an Enter starts the next one")
    void aRunIsOneStepAndANewlineEndsIt() {
        TextArea area = TextArea.of(4096);
        area.insert('a');
        area.insert('b');
        area.insert('\n');
        area.insert('c');
        area.insert('d');
        assertEquals("ab\ncd", area.value());

        area.undo();
        assertEquals("ab", area.value(), "the Enter and the line typed after it go back together");
        area.undo();
        assertEquals("", area.value(), "then the first line's run");
    }

    @Test
    @DisplayName("undo restores the caret, the mark and the text together")
    void undoPutsTheStateBack() {
        TextArea area = TextArea.of(4096).setValue("one\ntwo").clearHistory();
        area.caretTo(4);
        area.selectTo(7);                       // marks "two"
        area.backspace();                       // deletes it: one step
        assertEquals("one\n", area.value());

        area.undo();
        assertEquals("one\ntwo", area.value());
        assertTrue(area.hasSelection(), "the mark the delete replaced is back");
        assertEquals(4, area.selectionStart());
        assertEquals(7, area.selectionEnd());
    }

    @Test
    @DisplayName("a new edit drops the redo")
    void aNewEditDropsTheRedo() {
        TextArea area = TextArea.of(4096);
        area.insert('a');
        area.undo();
        assertTrue(area.canRedo());
        area.insert('b');
        assertFalse(area.canRedo());
    }

    @Test
    @DisplayName("the opened value is where the history starts")
    void theOpenedValueIsWhereTheHistoryStarts() {
        TextArea area = TextArea.of(4096).setValue("open").clearHistory();
        assertFalse(area.canUndo());
        area.insert('!');
        area.undo();
        assertEquals("open", area.value());
    }
}
