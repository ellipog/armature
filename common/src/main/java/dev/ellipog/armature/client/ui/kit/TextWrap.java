package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a paragraph into lines that fit a column.
 *
 * <h2>Why this is a class and not three lines at the call site</h2>
 *
 * <p>It was three lines at the call site, in a screen, and the screen also had to compute the height
 * of what it had just wrapped -- for a scrollbar, and for a clamp. So the wrap rule existed twice: once
 * in the code that drew the text and once in the code that decided how tall it was. Two descriptions
 * of one thing, and the failure mode is not a crash: the scrollbar is subtly too short, or the content
 * is clamped before its last line, and both look like a layout bug rather than an arithmetic one.
 *
 * <p>Here there is one answer. {@link #height} calls {@link #wrap} rather than predicting it, so the
 * two cannot disagree -- they are the same computation, and the height is its size.
 *
 * <h2>The rules, since they are choices rather than conventions</h2>
 *
 * <ul>
 *   <li><b>Wrap at spaces, not between letters.</b> A line is filled word by word.</li>
 *   <li><b>A newline is honoured</b>, even where the text either side of it would fit on one line.
 *       It is how a caller writes a deliberate break, so ignoring it would make that impossible.</li>
 *   <li><b>A blank line is a line.</b> It takes height. Dropping it would silently change the
 *       paragraph spacing of every text that contains one, and the measure and the draw would then
 *       have to agree about it separately.</li>
 *   <li><b>A word wider than the column is split</b> rather than overflowed or dropped. Overflowing
 *       draws straight through the edge and reads as a rendering fault; dropping loses content
 *       silently. Splitting is at least legible, and it is the only one of the three the reader can
 *       act on. And it begins on a line of its own: the line is broken <i>before</i> the long word
 *       rather than carrying the two or three characters of it that would have fitted, because a
 *       fragment sharing a line with the words before it reads as a hyphenation fault rather than as
 *       a long word.</li>
 *   <li><b>A run of spaces is one break.</b> Leading and trailing whitespace on a line is not kept, so
 *       a line's width is its content's.</li>
 * </ul>
 *
 * <p>Empty input yields no lines at all, not one empty line: nothing in, nothing out. An input of a
 * single newline yields two, because that is two paragraphs and both are empty -- which is the
 * consistent reading of the blank-line rule rather than a special case.
 *
 * <p><b>Game-free by design.</b> Everything here is a function of a {@link Measure} and two integers,
 * which is what lets the rules above be tested rather than looked at.
 */
public final class TextWrap {

    private TextWrap() {
    }

    /**
     * Wraps {@code text} into lines no wider than {@code width}, except where a single glyph cannot
     * fit -- see {@link #fittingPrefix}.
     *
     * @param text   the paragraph or paragraphs. {@code \n} separates them.
     * @param width  the column width in pixels
     * @param measure how wide the text actually is
     * @return the lines, in order. Empty if the text is empty.
     */
    public static List<String> wrap(String text, int width, Measure measure) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(measure, "measure");
        if (text.isEmpty()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();

        // -1, so a trailing newline is a paragraph and not discarded. Without it "one\n" and "one"
        // measure the same, and the blank line the author wrote at the end of a description vanishes
        // from the height while still being there in the file.
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(paragraph, width, measure, lines);
        }
        return List.copyOf(lines);
    }

    /**
     * How tall {@code text} is at this width.
     *
     * <p>Calls {@link #wrap} rather than estimating. See the class note: an estimate here is the
     * second description of the wrap rule, and it is the one that goes wrong.
     */
    public static int height(String text, int width, Measure measure) {
        return wrap(text, width, measure).size() * measure.lineHeight();
    }

    private static void wrapParagraph(String paragraph, int width, Measure measure, List<String> out) {
        if (paragraph.isBlank()) {
            out.add("");
            return;
        }

        StringBuilder line = new StringBuilder();

        // \\s+ rather than " ", so a tab or a double space is one break. A double space left in would
        // make the line wider than the sum of its words, which is the sort of pixel that turns into a
        // scrollbar that is one line short.
        for (String word : paragraph.strip().split("\\s+")) {
            String remaining = word;

            while (measure.width(remaining) > width) {
                int fit = fittingPrefix(remaining, width, measure);
                if (fit <= 0) {
                    // Not one glyph fits: the column is narrower than the font's narrowest character.
                    // Take one anyway. The loop has to make progress, and a line one character too wide
                    // is a better failure than a hang -- the only way to reach this is a panel that has
                    // been dragged down to nothing.
                    fit = 1;
                }
                if (line.length() > 0) {
                    // The part of the word that is still on the current line would itself overflow, so
                    // the line ends here rather than later.
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(remaining.substring(0, fit));
                remaining = remaining.substring(fit);
            }

            if (remaining.isEmpty()) {
                continue;
            }
            if (line.length() == 0) {
                // Guaranteed to fit: the loop above only exits with something that does, or with one
                // character that could not. Measuring again here would be the second description.
                line.append(remaining);
            }
            else if (measure.width(line + " " + remaining) <= width) {
                line.append(' ').append(remaining);
            }
            else {
                out.add(line.toString());
                line.setLength(0);
                line.append(remaining);
            }
        }

        if (line.length() > 0) {
            out.add(line.toString());
        }
    }

    /**
     * How many characters of {@code text} fit in {@code width}, at least zero.
     *
     * <p>May return zero, and the caller must handle it: a column narrower than one character admits
     * no prefix at all, and treating zero as "none of it fits, give up" would loop forever.
     */
    private static int fittingPrefix(String text, int width, Measure measure) {
        int fit = 0;
        while (fit < text.length() && measure.width(text.substring(0, fit + 1)) <= width) {
            fit++;
        }
        return fit;
    }
}
