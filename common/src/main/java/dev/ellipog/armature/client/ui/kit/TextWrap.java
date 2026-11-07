package dev.ellipog.armature.client.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

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
     * What a paragraph's words are split on: any run of whitespace.
     *
     * <p>Compiled once rather than per paragraph. {@code String.split} only skips the regex engine for a
     * one-character pattern, so {@code split("\\s+")} compiled this pattern again on every call, for every
     * paragraph of every re-wrap. {@code String.split(regex)} is specified as
     * {@code Pattern.compile(regex).split(this, 0)}, which is what {@link Pattern#split(CharSequence)} is,
     * so the words are the same ones.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
            wrapParagraph(paragraph, width, measure, lines, null);
        }
        return List.copyOf(lines);
    }

    /**
     * The wrap and each line's width together, for callers that need both.
     *
     * <p>A stack laying out a paragraph needs the lines <i>and</i> how wide the widest is: wrapping
     * and then re-measuring every line walks each one twice. The widths here are recorded as each
     * line is emitted — one measurement per line, never two — so a layout that sizes to content
     * pays the same as one that only wraps.
     */
    public record Wrapped(List<String> lines, List<Integer> widths) {
    }

    /**
     * Wraps like {@link #wrap}, and remembers how wide each line was.
     *
     * <p>The same single pass, not a wrap followed by a measuring loop: see {@link Wrapped}.
     */
    public static Wrapped wrapMeasured(String text, int width, Measure measure) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(measure, "measure");
        List<String> lines = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        if (!text.isEmpty()) {
            // Same split as `wrap`, including the trailing-newline paragraph.
            for (String paragraph : text.split("\n", -1)) {
                wrapParagraph(paragraph, width, measure, lines, widths);
            }
        }
        return new Wrapped(List.copyOf(lines), List.copyOf(widths));
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

    private static void wrapParagraph(String paragraph, int width, Measure measure, List<String> out,
                                       List<Integer> widthsOrNull) {
        if (paragraph.isBlank()) {
            emit(out, widthsOrNull, measure, "");
            return;
        }

        StringBuilder line = new StringBuilder();

        // \\s+ rather than " ", so a tab or a double space is one break. A double space left in would
        // make the line wider than the sum of its words, which is the sort of pixel that turns into a
        // scrollbar that is one line short.
        for (String word : WHITESPACE.split(paragraph.strip())) {
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
                    emit(out, widthsOrNull, measure, line.toString());
                    line.setLength(0);
                }
                emit(out, widthsOrNull, measure, remaining.substring(0, fit));
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
                emit(out, widthsOrNull, measure, line.toString());
                line.setLength(0);
                line.append(remaining);
            }
        }

        if (line.length() > 0) {
            emit(out, widthsOrNull, measure, line.toString());
        }
    }

    /**
     * One wrapped line, and its width when somebody is keeping score.
     *
     * <p>The width is measured on emission rather than re-derived later: the caller that sizes to
     * content (a stack) gets it for the price of the wrap, and the caller that only wraps (everyone
     * else) pays nothing — a null collector skips the measurement entirely.
     */
    private static void emit(List<String> out, List<Integer> widthsOrNull, Measure measure, String line) {
        out.add(line);
        if (widthsOrNull != null) {
            widthsOrNull.add(measure.width(line));
        }
    }

    /**
     * How many characters of {@code text} fit in {@code width}, at least zero.
     *
     * <p>May return zero, and the caller must handle it: a column narrower than one character admits
     * no prefix at all, and treating zero as "none of it fits, give up" would loop forever.
     */
    private static int fittingPrefix(String text, int width, Measure measure) {
        return fittingLength(text, width, measure::width);
    }

    // ------------------------------------------------------------------
    // The fit search. Shared with Measure.truncate and TextArea's wrap.
    // ------------------------------------------------------------------

    /**
     * How far the first probe of a fit search reaches: about a line of the card's prose.
     *
     * <p>A search for where a line ends starts here and doubles until it overshoots, then bisects what it
     * bracketed. Sixty-four characters is a little more than a line of a description at the widths it is
     * shown at, so a typical line costs one probe to bracket and six to bisect, and a short label's
     * first probe is simply the whole label.
     */
    static final int FIRST_STRIDE = 64;

    /**
     * How many characters of {@code text} the character walk would keep: the prefixes measured in order,
     * stopping at the first that does not fit. At least zero.
     *
     * <p>This is the answer {@link Measure#truncate} and the long-word split have always given: one walk
     * asked about every prefix up to the cut, which for a long label is a substring and a memo probe per
     * character. It is now found in logarithmic probes, with the same answer, by {@link #furthestFitting}
     * and {@link #walkedFit}; their notes say what the answer rests on. Both callers go through here, so
     * they agree by construction about what "fits" means.
     */
    static int fittingLength(String text, int width, ToIntFunction<String> widthOf) {
        int furthest = furthestFitting(text, 0, text.length(), width, widthOf);
        return walkedFit(text, 0, furthest, text.length(), width, widthOf);
    }

    /**
     * The furthest end in {@code [from, to]} whose slice {@code [from, end)} fits, among the ends that do
     * not fall inside a surrogate pair. {@code from} when none does.
     *
     * <h2>Why this can bisect, and why it skips the middle of a pair</h2>
     *
     * <p>A bisection is only the walk's answer if a longer prefix is never narrower than a shorter one. The
     * game's font gives that for every prefix that ends between characters: advances are never negative,
     * there is no kerning, and a formatting code styles only what comes after it (a trailing section sign
     * is drawn as nothing, and so is the code it would have read). It does <b>not</b> give it for a prefix
     * that ends between the two halves of a surrogate pair: half a pair is drawn as the replacement
     * character, which can be wider than the whole glyph the pair makes. So the search only ever probes
     * ends that are not inside a pair, where the order holds, and {@link #walkedFit} accounts for the
     * halves.
     *
     * <p>The first probe is {@link #FIRST_STRIDE} characters out, then twice that, and so on, until one
     * does not fit or the probe is {@code to}; the bracket is then bisected. A line costs a handful of
     * probes no longer than about twice itself, rather than probes over the rest of the text. When the
     * answer is {@code to}, the probe that said so was the whole slice, which is how a caller learns that
     * the rest fits without measuring it separately. The empty slice is taken to fit without being
     * measured, as the walk never measured it either.
     */
    static int furthestFitting(String text, int from, int to, int width, ToIntFunction<String> widthOf) {
        if (to <= from) {
            return from;
        }
        // `lo` fits (or is `from`, the empty prefix) and `hi`, once known, does not. Both are always ends
        // outside a pair, so the order the bisection needs holds between them.
        int lo = from;
        int hi;
        int stride = FIRST_STRIDE;
        while (true) {
            int probe = to - from <= stride ? to : from + stride;
            if (insidePair(text, from, to, probe)) {
                probe++;                         // the end after the pair, which is at most `to`
            }
            if (widthOf.applyAsInt(text.substring(from, probe)) <= width) {
                if (probe == to) {
                    return to;
                }
                lo = probe;
                stride *= 2;
            }
            else {
                hi = probe;
                break;
            }
        }
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (insidePair(text, from, to, mid)) {
                // Either neighbour of the middle of a pair is outside one: the character before it is a high
                // surrogate and the one after it a low surrogate, and neither can be the middle of another.
                if (mid - 1 > lo) {
                    mid--;
                }
                else if (mid + 1 < hi) {
                    mid++;
                }
                else {
                    break;                       // the only end left between them is inside the pair
                }
            }
            if (widthOf.applyAsInt(text.substring(from, mid)) <= width) {
                lo = mid;
            }
            else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Where the character walk over {@code [from, to)} stopped, given {@link #furthestFitting}'s answer.
     *
     * <p>Every prefix up to {@code furthest} that ends outside a pair fits, so the only ones that could have
     * stopped the walk sooner are the half-pairs below it, and each is asked about in order. Past
     * {@code furthest}, the next end outside a pair does not fit, so the walk took at most the half-pair in
     * front of it, which it did when that half fits, and that is a line ending in half a character,
     * exactly as the walk always ended one. A text without surrogate pairs asks nothing here.
     */
    static int walkedFit(String text, int from, int furthest, int to, int width,
                         ToIntFunction<String> widthOf) {
        for (int end = from + 1; end < furthest; end++) {
            if (insidePair(text, from, to, end) && widthOf.applyAsInt(text.substring(from, end)) > width) {
                return end - 1;
            }
        }
        if (insidePair(text, from, to, furthest + 1)
                && widthOf.applyAsInt(text.substring(from, furthest + 1)) <= width) {
            return furthest + 1;
        }
        return furthest;
    }

    /** Whether an end inside {@code (from, to)} falls between the two halves of a surrogate pair. */
    private static boolean insidePair(String text, int from, int to, int end) {
        return end > from && end < to
                && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end));
    }
}
