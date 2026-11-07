package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a label is cut and where an over-long word is split: the search that replaced the character walk,
 * held against the walk itself.
 *
 * <h2>Why the old code is kept here</h2>
 *
 * <p>Because the change is meant to be invisible. {@link Measure#truncate} and {@link TextWrap}'s long-word
 * split used to walk a string a prefix at a time and stop at the first that did not fit; they now bisect,
 * and the claim is that every answer is the walk's, to the character. The walk is copied below exactly as it
 * was, and the search is asked the same questions over thousands of random strings (surrogate pairs, half
 * pairs on their own, runs of spaces and formatting codes included) through measures that do and do not
 * keep a longer prefix at least as wide as a shorter one.
 *
 * <p>The word split moved to a compiled pattern in the same change, so the old wrap is copied with its
 * {@code String.split} and the whole wrap is held against it too.
 */
@DisplayName("the longest prefix that fits")
class FittingPrefixTest {

    /** Six pixels a character, the measure the wrap tests use. */
    private static final Measure SIX = Measure.monospace(6, 10);

    /** The game font's shape, where a prefix ending inside a surrogate pair can be wider than the next. */
    private static final Measure GAME = GameShapedFont.MEASURE;

    /** The same, through a memo, which is how the controls measure. */
    private static final Measure MEMO = Measure.cached(GameShapedFont.MEASURE, () -> 0L);

    private static final List<Measure> MEASURES = List.of(SIX, GAME, MEMO);

    /** The ellipsis {@code Measure.truncate} writes, U+2026. */
    private static final String ELLIPSIS = String.valueOf((char) 0x2026);

    // ------------------------------------------------------------------
    // The retired code, verbatim in behaviour
    // ------------------------------------------------------------------

    /** The walk both {@code Measure.fittingPrefix} and {@code TextWrap.fittingPrefix} were. */
    private static int walk(String text, int width, Measure measure) {
        int fit = 0;
        while (fit < text.length() && measure.width(text.substring(0, fit + 1)) <= width) {
            fit++;
        }
        return fit;
    }

    /** {@code Measure.truncate} as it was, over the walk. */
    private static String oldTruncate(String text, int maxWidth, Measure measure) {
        if (maxWidth <= 0) {
            return "";
        }
        if (measure.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = ELLIPSIS;
        int room = maxWidth - measure.width(ellipsis);
        if (room <= 0) {
            return text.substring(0, walk(text, maxWidth, measure));
        }
        String prefix = text.substring(0, walk(text, room, measure));
        if (prefix.isEmpty()) {
            return ellipsis;
        }
        return prefix + ellipsis;
    }

    /** {@code TextWrap.wrap} as it was: the walk for long words, and a regex compiled per paragraph. */
    private static List<String> oldWrap(String text, int width, Measure measure) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.strip().split("\\s+")) {
                String remaining = word;
                while (measure.width(remaining) > width) {
                    int fit = walk(remaining, width, measure);
                    if (fit <= 0) {
                        fit = 1;
                    }
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    lines.add(remaining.substring(0, fit));
                    remaining = remaining.substring(fit);
                }
                if (remaining.isEmpty()) {
                    continue;
                }
                if (line.length() == 0) {
                    line.append(remaining);
                }
                else if (measure.width(line + " " + remaining) <= width) {
                    line.append(' ').append(remaining);
                }
                else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(remaining);
                }
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return List.copyOf(lines);
    }

    // ------------------------------------------------------------------
    // The search against the walk
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the search keeps exactly what the walk kept, over random strings and widths")
    void theSearchIsTheWalk() {
        Random random = new Random(0x5EA4C4L);
        for (int round = 0; round < 4000; round++) {
            String text = GameShapedFont.text(random, random.nextInt(round % 10 == 0 ? 400 : 90), false);
            int width = random.nextInt(round % 3 == 0 ? 60 : 900) - 3;
            for (Measure measure : MEASURES) {
                assertEquals(walk(text, width, measure), TextWrap.fittingLength(text, width, measure::width),
                        () -> "width " + width + " through " + measure + " of " + escaped(text));
            }
        }
    }

    @Test
    @DisplayName("a label is truncated exactly as it was, ellipsis and all")
    void truncateIsUnchanged() {
        Random random = new Random(0x7A11L);
        for (int round = 0; round < 3000; round++) {
            String text = GameShapedFont.text(random, random.nextInt(120), false);
            int maxWidth = random.nextInt(round % 4 == 0 ? 40 : 400) - 3;
            for (Measure measure : MEASURES) {
                assertEquals(oldTruncate(text, maxWidth, measure), Measure.truncate(text, maxWidth, measure),
                        () -> "width " + maxWidth + " through " + measure + " of " + escaped(text));
            }
        }
    }

    @Test
    @DisplayName("a wrap is the same lines as it was, long words, odd whitespace and paragraphs included")
    void wrapIsUnchanged() {
        Random random = new Random(0x3A3L);
        for (int round = 0; round < 1500; round++) {
            String text = GameShapedFont.text(random, random.nextInt(300), true);
            int width = random.nextInt(round % 3 == 0 ? 40 : 300);
            for (Measure measure : MEASURES) {
                assertEquals(oldWrap(text, width, measure), TextWrap.wrap(text, width, measure),
                        () -> "width " + width + " through " + measure + " of " + escaped(text));
                assertEquals(oldWrap(text, width, measure), TextWrap.wrapMeasured(text, width, measure).lines());
            }
        }
    }

    // ------------------------------------------------------------------
    // The two cases a plain bisection gets wrong
    // ------------------------------------------------------------------

    /** One character outside the basic plane: two chars. */
    private static final String PAIR = GameShapedFont.pair(0);

    @Test
    @DisplayName("half a pair wider than the whole stops the cut before it, as the walk stopped")
    void aWideHalfStopsTheCut() {
        // "aa" is 12; half the pair is the replacement character, 9 more; the whole pair is 5 more, because
        // U+1F601 % 7 is 0. At 18 pixels the prefix ending in the whole pair fits (17) but the walk never
        // got there: it stopped at the half (21). A bisection that only asked about the whole pair would
        // have kept it.
        String narrow = GameShapedFont.pair(1);
        String text = "aa" + narrow + "aaaa";
        assertEquals(21, GameShapedFont.width("aa" + narrow.charAt(0)));
        assertEquals(17, GameShapedFont.width("aa" + narrow));
        assertEquals(2, walk(text, 18, GAME), "the walk stops at the half");
        assertEquals(2, TextWrap.fittingLength(text, 18, GAME::width));

        // And a label: 24 pixels less the ellipsis's 6 is the same 18-pixel room.
        assertEquals(oldTruncate(text, 24, GAME), Measure.truncate(text, 24, GAME));
        assertEquals("aa" + ELLIPSIS, Measure.truncate(text, 24, GAME),
                "the label is cut where it always was");
    }

    @Test
    @DisplayName("a half that fits where the whole does not is kept, as the walk kept it")
    void aNarrowHalfIsKept() {
        // Six pixels a char: the half is 6 and the whole pair 12. At 18 pixels "aa" and its half fit and
        // the whole does not, so the walk ended inside the pair -- and so does the search, which only ever
        // probes ends outside one and then asks about the half in front of where it stopped.
        String text = "aa" + PAIR + "b";
        assertEquals(3, walk(text, 18, SIX));
        assertEquals(3, TextWrap.fittingLength(text, 18, SIX::width));
        assertEquals(oldTruncate(text, 24, SIX), Measure.truncate(text, 24, SIX));
    }

    // ------------------------------------------------------------------
    // What the search is for
    // ------------------------------------------------------------------

    /** A measure that counts what it is asked. */
    private static final class Counting implements Measure {

        private final Measure inner;
        private int asks;

        Counting(Measure inner) {
            this.inner = inner;
        }

        @Override
        public int width(String text) {
            asks++;
            return inner.width(text);
        }

        @Override
        public int lineHeight() {
            return inner.lineHeight();
        }
    }

    @Test
    @DisplayName("a long label costs a logarithmic number of measurements, not one per character kept")
    void aLongLabelIsCutInFewProbes() {
        // Two thousand characters cut at 1200 pixels keeps 199 of them: the walk measured 200 prefixes, plus
        // the whole label and the ellipsis. The search brackets the cut in three probes and bisects it in
        // seven more.
        String label = "a".repeat(2000);
        Counting counting = new Counting(SIX);
        String shown = Measure.truncate(label, 1200, counting);
        assertEquals(oldTruncate(label, 1200, SIX), shown);
        assertTrue(counting.asks <= 2 + 2 * 11, "measured " + counting.asks + " times");
    }

    /** A string with its control and surrogate characters spelled out, so a failure can be read. */
    private static String escaped(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04X", (int) c));
            }
            else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
