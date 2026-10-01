package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A description's markdown, parsed into what is drawn: blocks, and styled runs over their visible text.
 *
 * <h2>What markdown is here, and what it deliberately is not</h2>
 *
 * <p><b>Blocks</b>: {@link Kind#PARAGRAPH} is prose, {@link Kind#HEADING} is a line that began with
 * {@code #}s, {@link Kind#BULLET} is a line that began with {@code - }, {@code * } or {@code + }. Blocks are
 * per <i>line</i>: a line break is a break, exactly as it always was. That is CommonMark's one rule this
 * file does not follow, and the reason is the files: quest descriptions are authored a line per array
 * element, and joining lines into flowing paragraphs would re-wrap prose that authors hand-wrapped, all of
 * it at once and for no gain.
 *
 * <p><b>Inline</b>: {@code **bold**}, {@code *italic*} / {@code _italic_}, {@code `code`} and
 * {@code [text](url)}. One level of styling: markup inside a span is shown literally rather than interpreted,
 * which is stated because the alternative (a real inline grammar) is a much larger promise to keep.
 * A {@code \} escapes the character after it. An unmatched delimiter is left as itself, because a
 * description with one stray {@code *} in it should read as prose, not as a parse error.
 *
 * <p><b>The visible text is what everything else works on.</b> Markers are not part of it: the runs index
 * the text this file produces, which is what the layout measures, what is drawn, and what a link rectangle
 * is cut from. The source string is the author's and is never rewritten — the editor edits the markdown
 * itself, and this is only how it is read.
 *
 * <p>Whitespace is normalised in the visible text — one space between words, no leading or trailing space
 * — because that is how the card's prose has always been shown ({@link TextWrap} collapses runs of spaces
 * when it wraps), and doing it once here is what lets the wrap below index the text exactly.
 *
 * <p><b>Game-free</b>, like everything else in this package: a function of a string, a width and a
 * {@link StyledWidth}, which is what lets the rules be asserted without a client.
 */
public final class RichText {

    private RichText() {
    }

    /** What a line of markdown is. */
    public enum Kind {
        /** Ordinary prose. */
        PARAGRAPH,
        /** A line that began with {@code #}s. */
        HEADING,
        /** A line that began with a bullet marker; the visible text carries a {@code "- "} of its own. */
        BULLET
    }

    /**
     * A styled span of a block's visible text: {@code [start, end)} into {@link Paragraph#text()}.
     *
     * <p>{@code code} and {@code link} are not the font's business -- a renderer draws code in its own tint
     * and a link is a region to click -- so they travel beside the two flags the font does answer to.
     */
    public record Run(int start, int end, boolean bold, boolean italic, boolean code, String link) {
    }

    /** One block: what it is, its visible text, and the runs over it (which may be empty). */
    public record Paragraph(Kind kind, String text, List<Run> runs, int level) {

        public Paragraph {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            if ((kind == Kind.HEADING) != (level >= 1 && level <= 6)) {
                // The pairing is the model's invariant, not a convention: a heading without a level has no
                // size to be drawn at, and a paragraph with one has a size nothing will read.
                throw new IllegalArgumentException("a heading's level is 1..6, and nothing else has one: "
                        + kind + " " + level);
            }
            runs = List.copyOf(runs);
        }
    }

    /** A visual line: a span of the block's visible text. */
    public record Line(int start, int end) {
    }

    /** A run as the renderer wants it: its own text, with nothing to index. */
    public record Piece(String text, boolean bold, boolean italic, boolean code, String link, float scale) {
    }

    /**
     * How much larger a heading is drawn than the prose under it.
     *
     * <p>One and a half, because the game's font is drawn at whole-pixel sizes in practice and 1.5 is the
     * smallest step that reads as a different size rather than as a rendering accident at eight pixels.
     */
    /**
     * The size each heading level is drawn at, from one hash down.
     *
     * <p>Four sizes, because the card's font has one size and the body's is its floor: {@code #} is twice
     * it, then 1.75, 1.5 and 1.25 times. Levels five and six share the smallest of those rather than being
     * drawn smaller than the prose around them -- which is what their markdown reading asks for and what a
     * single font cannot give: a heading that is not a heading is worse than one the size of the level
     * above it.
     */
    private static final float[] HEADING_SCALES = {2F, 1.75F, 1.5F, 1.25F, 1.25F, 1.25F};

    /**
     * The size a block's text is drawn at: its level's scale for a heading, and the font's own size for
     * everything else.
     *
     * <p>Here rather than at the drawing site because <b>the wrap has to know it too</b>: a heading wrapped
     * as plain and drawn scaled is a line wider than the column it was wrapped into, which is the fault
     * this file names everywhere. {@link #styledWidth} and {@link #pieces} both ask this, so the size a
     * line is measured at is the size it is drawn at.
     */
    public static float scale(Paragraph paragraph) {
        return paragraph.kind() == Kind.HEADING ? HEADING_SCALES[paragraph.level() - 1] : 1F;
    }

    /**
     * How wide text is, given its style — the one thing a caller knows and this file does not.
     *
     * <p>The same shape as the widths {@link TextArea#wrap} and {@link TextWrap} take, and for the same
     * reason: the measure belongs to whoever has a font, and the rules belong here where they are testable.
     * Bold is wider than plain in the game's font, which is why a markdown paragraph can wrap at a
     * different word than the same text without styling.
     */
    @FunctionalInterface
    public interface StyledWidth {
        int width(String text, boolean bold, boolean italic);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** The blocks a description string is, one per line. */
    public static List<Paragraph> parse(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        List<Paragraph> blocks = new ArrayList<>();
        for (String raw : markdown.split("\n", -1)) {
            blocks.add(block(raw));
        }
        return List.copyOf(blocks);
    }

    private static Paragraph block(String raw) {
        String line = raw.strip();
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes >= 1 && hashes <= 6 && hashes < line.length() && line.charAt(hashes) == ' ') {
            return scanned(Kind.HEADING, headingBody(line, hashes), hashes);
        }
        if (line.length() > 1 && isBulletMarker(line.charAt(0)) && line.charAt(1) == ' ') {
            // The marker is part of the visible text: a bullet with its dash reads as a list item, the
            // indent is drawn by the wrap, and "-" is a glyph every font this card draws with has.
            return scanned(Kind.BULLET, "- " + line.substring(2));
        }
        return scanned(Kind.PARAGRAPH, line);
    }

    /** The text of a heading whose {@code hashes} leading hashes are already counted, decoration gone. */
    private static String headingBody(String line, int hashes) {
        String body = line.substring(hashes + 1).strip();
        int closing = body.length();
        while (closing > 0 && body.charAt(closing - 1) == '#') {
            closing--;
        }
        if (closing < body.length() && closing > 0 && body.charAt(closing - 1) == ' ') {
            body = body.substring(0, closing).strip();
        }
        return body;
    }

    private static boolean isBulletMarker(char c) {
        return c == '-' || c == '*' || c == '+';
    }

    private static Paragraph scanned(Kind kind, String source) {
        return scanned(kind, source, 0);
    }

    private static Paragraph scanned(Kind kind, String source, int level) {
        StringBuilder text = new StringBuilder();
        List<Run> runs = new ArrayList<>();
        scan(source, text, runs);
        return new Paragraph(kind, text.toString(), runs, level);
    }

    /**
     * The inline scan: the visible text, with a run recorded for every styled span.
     *
     * <p>A single left-to-right pass, so the spans tile the text in order and cannot overlap. Each
     * construct is tried in turn at the position, and one that does not close is left as the characters it
     * is — which is the whole error handling: there is no error to report, only markup that did not apply.
     *
     * <p>The whitespace rule is applied here rather than in a second pass, because a second pass would have
     * to move every run's indices with the text it moved, and that remapping is exactly the kind of
     * arithmetic this file exists to keep in one place and assert. Appending through {@link Text} means the
     * indices are right by construction: a run is a span of what was appended.
     */
    private static void scan(String source, StringBuilder text, List<Run> runs) {
        Text out = new Text(text);
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length() && escapable(source.charAt(i + 1))) {
                out.append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            if (source.startsWith("**", i)) {
                int close = source.indexOf("**", i + 2);
                if (close > i + 2) {
                    out.span(source.substring(i + 2, close), runs, true, false, false, null);
                    i = close + 2;
                    continue;
                }
            }
            if (c == '`') {
                int close = source.indexOf('`', i + 1);
                if (close > i + 1) {
                    out.span(source.substring(i + 1, close), runs, false, false, true, null);
                    i = close + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = source.indexOf(']', i + 1);
                if (close > i + 1 && close + 1 < source.length() && source.charAt(close + 1) == '(') {
                    int end = source.indexOf(')', close + 2);
                    if (end > close + 2) {
                        out.span(source.substring(i + 1, close), runs, false, false, false,
                                source.substring(close + 2, end).strip());
                        i = end + 1;
                        continue;
                    }
                }
            }
            if (c == '*' || c == '_') {
                int close = source.indexOf(c, i + 1);
                // `_` only delimits between words: "snake_case_names" is prose, not an italic run.
                boolean inWord = c == '_' && i > 0 && Character.isLetterOrDigit(source.charAt(i - 1));
                if (close > i + 1 && !inWord) {
                    out.span(source.substring(i + 1, close), runs, false, true, false, null);
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }

    /**
     * The visible text being built: why it is a class rather than three lines in the scan.
     *
     * <p>Because "one space between words, nothing at the ends" is a rule about what has been appended so
     * far (a pending space, and whether anything is before it), and because a run's indices have to be
     * spans of the finished text rather than of the source. Both fall out of appending here.
     */
    private static final class Text {

        private final StringBuilder text;
        /** A space is waiting to be written: the last thing appended was whitespace. */
        private boolean pendingSpace;

        private Text(StringBuilder text) {
            this.text = text;
        }

        /** Appends one character, collapsing whitespace: one space between words, none at either end. */
        private void append(char c) {
            if (Character.isWhitespace(c)) {
                pendingSpace = text.length() > 0;
                return;
            }
            if (pendingSpace) {
                text.append(' ');
                pendingSpace = false;
            }
            text.append(c);
        }

        /** Appends a styled span and records it. Nesting is literal, so the inner text is taken raw. */
        private void span(String inner, List<Run> runs, boolean bold, boolean italic, boolean code,
                          String link) {
            // Any space waiting before the span is written outside it: a run is what the markup covered,
            // and the space before it was not covered by anything.
            if (pendingSpace && text.length() > 0) {
                text.append(' ');
            }
            pendingSpace = false;
            int start = text.length();
            for (int i = 0; i < inner.length(); i++) {
                append(inner.charAt(i));
            }
            if (text.length() > start) {
                runs.add(new Run(start, text.length(), bold, italic, code, link));
            }
        }
    }

    /** The characters a backslash may escape: the punctuation that would otherwise be markup. */
    private static boolean escapable(char c) {
        return c == '\\' || c == '*' || c == '_' || c == '`' || c == '[' || c == ']'
                || c == '(' || c == ')' || c == '#' || c == '-' || c == '+';
    }

    // ------------------------------------------------------------------
    // Wrapping
    // ------------------------------------------------------------------

    /**
     * The block's visual lines at a width, measured through the styles.
     *
     * <p>The same word rules as {@link TextWrap} — wrap at spaces, a word wider than the column split —
     * because a description is read by the same eye either way, and the only difference here is that a
     * word's width depends on what it is styled as. A caller that wants that property asserted can compare
     * this against {@code TextWrap} on unstyled text; {@code RichTextTest} does.
     */
    public static List<Line> wrap(Paragraph paragraph, int width, StyledWidth measure) {
        Objects.requireNonNull(paragraph, "paragraph");
        Objects.requireNonNull(measure, "measure");
        String text = paragraph.text();
        if (text.isEmpty()) {
            return List.of(new Line(0, 0));
        }
        List<Line> lines = new ArrayList<>();
        int column = Math.max(1, width);
        int from = 0;
        while (from < text.length()) {
            int end = fittingEnd(paragraph, from, column, measure);
            if (end <= from) {
                end = from + 1;                  // one glyph that fits nowhere still gets its own line
            }
            lines.add(new Line(from, end));
            from = end;
            while (from < text.length() && text.charAt(from) == ' ') {
                from++;                          // the break's space belongs to no line
            }
        }
        return List.copyOf(lines);
    }

    /**
     * How far a line starting at {@code from} can go: to the last space that fits, or -- for a word wider
     * than the column -- to the last character that fits, which is {@link TextWrap}'s own split rule.
     */
    private static int fittingEnd(Paragraph paragraph, int from, int width, StyledWidth measure) {
        String text = paragraph.text();
        if (styledWidth(paragraph, from, text.length(), measure) <= width) {
            return text.length();
        }
        int fits = from;
        while (fits < text.length()
                && styledWidth(paragraph, from, fits + 1, measure) <= width) {
            fits++;
        }
        if (fits <= from) {
            return from;
        }
        if (fits >= text.length() || text.charAt(fits) == ' ') {
            // What fits *ends* at a word boundary, so the line is all of it: breaking at the space before
            // the last whole word instead would drop a word that fits -- which is a line shorter than the
            // column, and the case the wrap-equals-TextWrap test found.
            return fits;
        }
        // Mid-word: the word is wider than the column, so it is split, as TextWrap splits it.
        int space = text.lastIndexOf(' ', fits - 1);
        return space > from ? space : fits;
    }

    /** The width of a span of the block's visible text, through the runs that cover it. */
    public static int styledWidth(Paragraph paragraph, int from, int to, StyledWidth measure) {
        int total = 0;
        int at = from;
        while (at < to) {
            Run run = runAt(paragraph, at);
            int end = run == null ? to : Math.min(to, run.end());
            String slice = paragraph.text().substring(at, end);
            total += Math.round(measure.width(slice, run != null && run.bold(), run != null && run.italic())
                    * scale(paragraph));
            at = end;
        }
        return total;
    }

    /** The run covering an index, or null where the text is plain. */
    private static Run runAt(Paragraph paragraph, int index) {
        for (Run run : paragraph.runs()) {
            if (index >= run.start() && index < run.end()) {
                return run;
            }
        }
        return null;
    }

    /** The pieces a line is drawn as, in order: plain gaps included, so the caller draws and moves right. */
    public static List<Piece> pieces(Paragraph paragraph, Line line) {
        List<Piece> pieces = new ArrayList<>();
        int at = line.start();
        while (at < line.end()) {
            Run run = runAt(paragraph, at);
            if (run == null) {
                int end = nextRunStart(paragraph, at, line.end());
                pieces.add(new Piece(paragraph.text().substring(at, end), false, false, false, null,
                        scale(paragraph)));
                at = end;
            }
            else {
                int end = Math.min(line.end(), run.end());
                pieces.add(new Piece(paragraph.text().substring(at, end), run.bold(), run.italic(),
                        run.code(), run.link(), scale(paragraph)));
                at = end;
            }
        }
        return List.copyOf(pieces);
    }

    private static int nextRunStart(Paragraph paragraph, int from, int limit) {
        int end = limit;
        for (Run run : paragraph.runs()) {
            if (run.start() > from) {
                end = Math.min(end, run.start());
            }
        }
        return end;
    }
}
