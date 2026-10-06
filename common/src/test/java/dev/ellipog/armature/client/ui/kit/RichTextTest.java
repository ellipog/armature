package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markdown rules, none of which need a font: what a block is, what the visible text is, and where the
 * lines break once styles have widths.
 *
 * <p>The measuring stand-in is six pixels a character, seven when bold — Minecraft's bold font is one pixel
 * wider per glyph, so the width here has the same shape and a test can say "the bold line breaks earlier"
 * and mean it.
 */
@DisplayName("a description's markdown")
class RichTextTest {

    private static final RichText.StyledWidth SIX = (text, bold, italic) -> text.length() * (bold ? 7 : 6);

    private static RichText.Paragraph only(String markdown) {
        List<RichText.Paragraph> blocks = RichText.parse(markdown);
        assertEquals(1, blocks.size(), "one line, one block");
        return blocks.get(0);
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a line is a block, and what kind it is comes off its first characters")
    void linesBecomeBlocks() {
        List<RichText.Paragraph> blocks = RichText.parse("plain\n# A heading\n- a bullet\n* another\n+ and one");
        assertEquals(5, blocks.size());
        assertEquals(RichText.Kind.PARAGRAPH, blocks.get(0).kind());
        assertEquals("plain", blocks.get(0).text());

        assertEquals(RichText.Kind.HEADING, blocks.get(1).kind());
        assertEquals("A heading", blocks.get(1).text(), "the hashes are not part of the text");

        assertEquals(RichText.Kind.BULLET, blocks.get(2).kind());
        assertEquals("- a bullet", blocks.get(2).text(), "the marker stays: a dash is what a list item is");
        assertEquals(RichText.Kind.BULLET, blocks.get(3).kind());
        assertEquals("- another", blocks.get(3).text());
        assertEquals(RichText.Kind.BULLET, blocks.get(4).kind());
    }

    @Test
    @DisplayName("a hash without a space is prose, and closing hashes are not words")
    void headingsAreShellsThatNeedASpace() {
        assertEquals("#hashtag", only("#hashtag").text(), "a hashtag is prose, hash and all");
        assertEquals(RichText.Kind.PARAGRAPH, only("#hashtag").kind());

        RichText.Paragraph closed = only("## Title ##");
        assertEquals(RichText.Kind.HEADING, closed.kind());
        assertEquals("Title", closed.text(), "the closing hashes are decoration");

        assertEquals(RichText.Kind.HEADING, only("###### Six").kind());
        assertEquals(RichText.Kind.PARAGRAPH, only("####### Seven").kind(), "seven hashes is prose");
    }

    @Test
    @DisplayName("the last line's break is a block too, and an empty description is one empty paragraph")
    void anEmptyDescriptionIsOneEmptyParagraph() {
        assertEquals(2, RichText.parse("one\n").size(), "the trailing newline is a line");
        RichText.Paragraph empty = only("");
        assertEquals(RichText.Kind.PARAGRAPH, empty.kind());
        assertEquals("", empty.text());
        assertEquals(List.of(new RichText.Line(0, 0)), RichText.wrap(empty, 100, SIX),
                "and it wraps to one empty line, which is the height an empty line has");
    }

    // ------------------------------------------------------------------
    // Inline
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bold, italic, code and a link are runs over the visible text, markers and all removed")
    void inlineStylesAreRuns() {
        RichText.Paragraph block = only("a **bold** b *it* c `code` d [text](https://x.y) e");
        assertEquals("a bold b it c code d text e", block.text());
        assertEquals(4, block.runs().size());

        RichText.Run bold = block.runs().get(0);
        assertEquals("bold", block.text().substring(bold.start(), bold.end()));
        assertTrue(bold.bold());

        RichText.Run italic = block.runs().get(1);
        assertEquals("it", block.text().substring(italic.start(), italic.end()));
        assertTrue(italic.italic());

        RichText.Run code = block.runs().get(2);
        assertEquals("code", block.text().substring(code.start(), code.end()));
        assertTrue(code.code());

        RichText.Run link = block.runs().get(3);
        assertEquals("text", block.text().substring(link.start(), link.end()));
        assertEquals("https://x.y", link.link());
    }

    @Test
    @DisplayName("an underscore inside a word is a character, not a delimiter")
    void underscoresInsideWordsAreLiteral() {
        RichText.Paragraph block = only("snake_case_names and _this_ is italic");
        assertEquals("snake_case_names and this is italic", block.text());
        assertEquals(1, block.runs().size());
        RichText.Run run = block.runs().get(0);
        assertEquals("this", block.text().substring(run.start(), run.end()));
    }

    @Test
    @DisplayName("markup that does not close is left as the characters it is")
    void unmatchedDelimitersAreLiteral() {
        assertEquals("2 * 3 = 6", only("2 * 3 = 6").text());
        assertEquals("a ** b", only("a ** b").text());
        assertEquals("[not a link]", only("[not a link]").text());
        assertEquals("a [text] without a target", only("a [text] without a target").text());
    }

    @Test
    @DisplayName("a backslash escapes the markup punctuation after it, and is not shown")
    void escapesHideThemselves() {
        RichText.Paragraph block = only("\\*not italic\\* and \\`not code\\`");
        assertEquals("*not italic* and `not code`", block.text());
        assertEquals(0, block.runs().size(), "nothing was marked, so nothing is styled");
        // And the other half of the rule: a backslash before anything else is not an escape, which is
        // why the prose sources name the characters it may hide rather than promising any.
        assertEquals("\\a", only("\\a").text(), "a backslash before a letter stays a backslash");
    }

    @Test
    @DisplayName("one level of styling: markup inside a span is shown as itself")
    void nestingIsLiteral() {
        RichText.Paragraph block = only("**bold *and* plain**");
        assertEquals("bold *and* plain", block.text());
        assertEquals(1, block.runs().size());
        assertTrue(block.runs().get(0).bold());
    }

    @Test
    @DisplayName("the visible text is one space between words, with nothing at the ends")
    void whitespaceIsNormalised() {
        RichText.Paragraph block = only("  a   b  *c*   d  ");
        assertEquals("a b c d", block.text());
        RichText.Run run = block.runs().get(0);
        assertEquals("c", block.text().substring(run.start(), run.end()),
                "the runs moved with the text they cover");
    }

    // ------------------------------------------------------------------
    // Wrapping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("plain text wraps exactly as the reader's own wrapper wraps it")
    void plainTextWrapsLikeTextWrap() {
        Measure plain = Measure.monospace(6, 10);
        for (int width : new int[] {60, 84, 120, 600}) {
            for (String text : List.of("one two three four five", "a very long word abcdefghijklmnopqrstuvwxyz here",
                    "short", "one two")) {
                RichText.Paragraph block = only(text);
                List<String> mine = RichText.wrap(block, width, SIX).stream()
                        .map(line -> block.text().substring(line.start(), line.end())).toList();
                assertEquals(TextWrap.wrap(text, width, plain), mine,
                        "width " + width + ": \"" + text + "\"");
            }
        }
    }

    @Test
    @DisplayName("a bold word is wider, so the line breaks earlier")
    void boldWrapsEarlierThanPlain() {
        // "aaa bbb ccc" is 66px plain and 70px with two bold words; at 66 the bold line must break sooner.
        RichText.Paragraph plain = only("aaa bbb ccc");
        RichText.Paragraph bold = only("**aaa** **bbb** ccc");
        assertEquals(List.of("aaa bbb", "ccc"), textsOf(bold, 66));
        assertEquals(List.of("aaa bbb ccc"), textsOf(plain, 66));
    }

    private static List<String> textsOf(RichText.Paragraph block, int width) {
        List<String> out = new ArrayList<>();
        for (RichText.Line line : RichText.wrap(block, width, SIX)) {
            out.add(block.text().substring(line.start(), line.end()));
        }
        return out;
    }

    @Test
    @DisplayName("a span that crosses a break is styled on both lines")
    void runsSurviveTheBreak() {
        RichText.Paragraph block = only("**one two three** four");
        List<RichText.Line> lines = RichText.wrap(block, 60, SIX);
        assertEquals(3, lines.size(), "13 bold characters at 7px need three lines at 60");

        List<RichText.Piece> first = RichText.pieces(block, lines.get(0));
        assertEquals(1, first.size());
        assertTrue(first.get(0).bold(), "the first line is the styled part");
        assertEquals("one two", first.get(0).text());

        List<RichText.Piece> second = RichText.pieces(block, lines.get(1));
        assertTrue(second.get(0).bold(), "and the continuation is still bold");
        assertEquals("three", second.get(0).text());

        List<RichText.Piece> third = RichText.pieces(block, lines.get(2));
        assertFalse(third.get(0).bold(), "while the word after the span is plain again");
        assertEquals("four", third.get(0).text());
    }

    @Test
    @DisplayName("the pieces of a line tile its text, and a link keeps its target across a break")
    void piecesTileTheLine() {
        RichText.Paragraph block = only("a [one two three](https://x.y) b");
        for (RichText.Line line : RichText.wrap(block, 66, SIX)) {
            StringBuilder rebuilt = new StringBuilder();
            for (RichText.Piece piece : RichText.pieces(block, line)) {
                rebuilt.append(piece.text());
            }
            assertEquals(block.text().substring(line.start(), line.end()), rebuilt.toString(),
                    "the pieces spell the line, in order");
        }
        boolean targetSeen = false;
        for (RichText.Line line : RichText.wrap(block, 66, SIX)) {
            for (RichText.Piece piece : RichText.pieces(block, line)) {
                if ("https://x.y".equals(piece.link())) {
                    targetSeen = true;
                }
            }
        }
        assertTrue(targetSeen, "every piece of the link knows where it goes");
    }

    @Test
    @DisplayName("the four heading levels are four sizes, and the last two share the smallest")
    void headingLevelsAreFourSizes() {
        assertEquals(2F, RichText.scale(only("# One")));
        assertEquals(1.75F, RichText.scale(only("## Two")));
        assertEquals(1.5F, RichText.scale(only("### Three")));
        assertEquals(1.25F, RichText.scale(only("#### Four")));
        assertEquals(1.25F, RichText.scale(only("##### Five")),
                "levels five and six take the smallest heading size rather than shrinking below the prose");
        assertEquals(1.25F, RichText.scale(only("###### Six")));
        assertEquals(1F, RichText.scale(only("prose")), "and prose is at the font's own size");

        // The level is part of the model, so a caller can tell them apart without parsing the text again.
        assertEquals(1, only("# One").level());
        assertEquals(4, only("#### Four").level());
        assertEquals(0, only("prose").level(), "and nothing that is not a heading has one");
    }

    @Test
    @DisplayName("a heading is measured at its own level's size, and the piece carries it")
    void aHeadingIsMeasuredAtItsLevelsSize() {
        RichText.Paragraph prose = only("A heading");
        // Nine characters at six pixels plain, and at each heading level's size.
        assertEquals(54, RichText.styledWidth(prose, 0, prose.text().length(), SIX));
        assertEquals(108, RichText.styledWidth(only("# A heading"), 0, 9, SIX));
        assertEquals(81, RichText.styledWidth(only("### A heading"), 0, 9, SIX));

        RichText.Paragraph first = only("# A heading");
        RichText.Line line = RichText.wrap(first, 600, SIX).get(0);
        RichText.Piece piece = RichText.pieces(first, line).get(0);
        assertEquals(2F, piece.scale(),
                "the piece carries the size the line was measured at, or the drawing would overflow it");
    }

    @Test
    @DisplayName("a bigger heading wraps earlier than a smaller one, because it is wider")
    void aBiggerHeadingWrapsEarlier() {
        // "A heading" is 54 plain, 81 at level three and 108 at level one: at 100 only the first two fit.
        assertEquals(1, RichText.wrap(only("A heading"), 100, SIX).size());
        assertEquals(1, RichText.wrap(only("### A heading"), 100, SIX).size());
        assertEquals(2, RichText.wrap(only("# A heading"), 100, SIX).size());
    }

    @Test
    @DisplayName("a heading without a level, or prose with one, is refused rather than guessed at")
    void theKindAndTheLevelMustAgree() {
        assertThrows(IllegalArgumentException.class,
                () -> new RichText.Paragraph(RichText.Kind.HEADING, "x", List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RichText.Paragraph(RichText.Kind.PARAGRAPH, "x", List.of(), 2));
    }
}
