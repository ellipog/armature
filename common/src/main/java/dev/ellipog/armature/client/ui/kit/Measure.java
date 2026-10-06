package dev.ellipog.armature.client.ui.kit;

import java.util.Objects;

/**
 * The only thing a layout needs to know about a font.
 *
 * <h2>Two methods, and deliberately no third</h2>
 *
 * <p>A layout answers "how wide is that" and "how tall is a line" and nothing else. Every further
 * question -- ascent, glyph bounds, whether a character has a texture -- is either the renderer's or
 * the caller's, and each one added here is one more method a test has to fake and a second
 * implementation has to keep in step.
 *
 * <h2>This is the seam that makes wrapping testable</h2>
 *
 * <p>A real font needs a running client, a texture atlas and a unicode provider. A {@code Measure}
 * needs none of them, so the wrap rules -- where a line breaks, how a blank line is counted, what
 * happens to a word wider than its column -- can be asserted against a known width per character
 * instead of judged by eye in a screenshot. {@link #monospace} is that stand-in, and it lives here
 * rather than in a test utility because the preview draws with it too: the tool and the tests must
 * not disagree about what a wrapped paragraph is, any more than the preview and the screen may
 * disagree about a control's colour.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No wrapping, no truncation, no trimming, no newline handling. {@link #width} answers for exactly
 * the string it is given -- a newline in it is meaningless to a single line of text and is therefore
 * the caller's to have removed, which is what {@link TextWrap} does when it splits on one.
 *
 * <p><b>Game-free by design.</b> The one implementation that is not -- over vanilla's font -- is
 * {@link FontMeasure}, kept in its own file so that this interface never names a game class.
 */
public interface Measure {

    /** The width of this string, in pixels. Never negative. */
    int width(String text);

    /** The height of one line of text, in pixels. What a wrapped line advances the cursor by. */
    int lineHeight();

    /**
     * Every character the same width. A stand-in for a real font, for tests and for the preview.
     *
     * <p>Deliberately the simplest possible metric rather than something proportional: a wrap test
     * that asserts "this breaks after five characters" is readable, and one that asserts a pixel count
     * produced by a system font is not. It also makes the arithmetic in a test checkable by hand,
     * which is the property that catches a broken wrap rule — a proportional fake would let a
     * wrong-by-one break hide behind a plausible-looking width.
     *
     * @param charWidth  width of every character
     * @param lineHeight height of a line
     */
    static Measure monospace(int charWidth, int lineHeight) {
        if (charWidth < 0) {
            throw new IllegalArgumentException("charWidth must not be negative: " + charWidth);
        }
        if (lineHeight < 0) {
            throw new IllegalArgumentException("lineHeight must not be negative: " + lineHeight);
        }
        return new Measure() {
            @Override
            public int width(String text) {
                return text.length() * charWidth;
            }

            @Override
            public int lineHeight() {
                return lineHeight;
            }

            @Override
            public String toString() {
                return "Measure.monospace(" + charWidth + ", " + lineHeight + ")";
            }
        };
    }

    /**
     * The longest prefix of {@code text} that fits, with an ellipsis only when something was cut.
     *
     * <h2>Why this is here, next to the wrap rule</h2>
     *
     * <p>Because it is the same question as {@link TextWrap} answers and was answered somewhere else.
     * Truncation lived in three places before this: a screen calling
     * {@code Font.plainSubstrByWidth} directly, a control doing the same, and — historically — a
     * character-count trim that divided a pixel width by five and used the result as a number of
     * letters. That last one is the reason this is a method with a test rather than a call: the
     * quantity was the wrong kind of thing, and an ellipsis that only appears when something was
     * removed is a rule rather than a line of arithmetic.
     *
     * <h2>The two cases that are easy to get wrong</h2>
     *
     * <p>When the whole string fits, it is returned <b>unchanged</b> rather than rebuilt — so a caller
     * comparing identity, or a test asserting no ellipsis, sees exactly what it passed in.
     *
     * <p>When not even the ellipsis fits, the ellipsis is dropped and the longest fitting prefix is
     * returned. Three characters in a four-pixel slot is a worse outcome than nothing, but a zero-width
     * label is worse than both — and the alternative, returning the ellipsis alone, draws a character
     * outside its own box.
     */
    public static String truncate(String text, int maxWidth, Measure measure) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(measure, "measure");
        if (maxWidth <= 0) {
            return "";
        }
        if (measure.width(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "\u2026";
        int room = maxWidth - measure.width(ellipsis);
        if (room <= 0) {
            // No space even for the ellipsis, so it is dropped rather than drawn past the edge.
            return fittingPrefix(text, maxWidth, measure);
        }

        String prefix = fittingPrefix(text, room, measure);
        if (prefix.isEmpty()) {
            return ellipsis;
        }
        return prefix + ellipsis;
    }

    /**
     * The longest prefix of {@code text} that fits in {@code width}.
     *
     * <p>Linear rather than binary search, and deliberately: a string here is a label or a
     * single-purpose sentence, never a document, and the linear version cannot be off by one in the
     * way that a hand-written binary search can. The same function {@link TextWrap} uses to fit a
     * fragment of an over-long word, so the two agree by construction about what "fits" means.
     */
    private static String fittingPrefix(String text, int width, Measure measure) {
        int fit = 0;
        while (fit < text.length() && measure.width(text.substring(0, fit + 1)) <= width) {
            fit++;
        }
        return text.substring(0, fit);
    }

    /**
     * A {@link Measure} over anything that can answer the two questions.
     *
     * <p>Exists so a caller holding a renderer — which is the only thing that knows how wide text is
     * on a client — can build the measure the wrap and truncate rules take, without either of those
     * rules needing to know what a renderer is. The dependency points one way: {@code TextWrap} and
     * {@code truncate} need two numbers, and this is the adapter that turns a renderer into them.
     *
     * <p>A convenience rather than a class anyone should depend on: the two methods it wraps are
     * already on {@code GuiRenderer}, and this is only here because an interface method reference and
     * a lambda can be passed where a {@code Measure} is wanted without a caller writing the adapter
     * itself at every site.
     */
    public static Measure of(WidthFn width, int lineHeight) {
        Objects.requireNonNull(width, "width");
        return new Measure() {
            @Override
            public int width(String text) {
                return width.widthOf(text);
            }

            @Override
            public int lineHeight() {
                return lineHeight;
            }

            @Override
            public String toString() {
                return "Measure.of(lineHeight " + lineHeight + ")";
            }
        };
    }

    /** A width function, so {@link #of} can be handed {@code renderer::textWidth} directly. */
    @FunctionalInterface
    public interface WidthFn {
        int widthOf(String text);
    }

    /**
     * A measure over a renderer that may change between frames, with the widths remembered.
     *
     * <h2>Why this exists rather than each control building its own</h2>
     *
     * <p>Because a control that truncates a label every frame has to keep its measure across frames for the
     * memo to be worth anything, and the renderer it measures with is the one that happens to be drawing —
     * a screen redraws a control through its own renderer, a modal band redraws it through another, and a
     * test drives it through a recording one. So the measure cannot close over a renderer; it has to read
     * whichever is in force.
     *
     * <p>{@code width} is the delegate that does that reading. It is called on every miss, and only on a
     * miss, so a control pays one lambda call per <i>new</i> string per epoch rather than per frame.
     *
     * <p>The line height comes from the renderer too, for the same reason: a measure that reported a
     * constant would be right until a text scale moved it.
     *
     * @param width      reads the width from whichever renderer is in force
     * @param lineHeight reads the line height from whichever renderer is in force
     * @param epoch      a number that changes when a glyph's width can have changed
     */
    public static Measure cachedOver(WidthFn width, java.util.function.IntSupplier lineHeight,
                                     java.util.function.LongSupplier epoch) {
        Objects.requireNonNull(width, "width");
        Objects.requireNonNull(lineHeight, "lineHeight");
        return cached(new Measure() {
            @Override
            public int width(String text) {
                return width.widthOf(text);
            }

            @Override
            public int lineHeight() {
                return lineHeight.getAsInt();
            }

            @Override
            public String toString() {
                return "Measure.cachedOver(...)";
            }
        }, epoch);
    }

    /**
     * A measure that answers a string it has already been asked about, until something changes a glyph.
     *
     * <h2>Why the memo belongs here rather than at a call site</h2>
     *
     * <p>Because the expensive pattern is not one call, it is the one {@link #truncate} makes: it walks a
     * string a character at a time asking how wide each prefix is, so measuring one label once is a
     * handful of font lookups in the best case and a few dozen in the worst — and a label that is drawn
     * every frame asks the same questions every frame, in the same order, for the same answers. Caching
     * the <b>width</b> rather than a caller's truncated result is what makes that free without any
     * caller knowing: the rules above stay the rules, every site that measures text pays less, and the
     * same applies to {@code TextWrap}, which walks a paragraph the same way.
     *
     * <h2>The epoch is the caller's, and it is not optional</h2>
     *
     * <p>A measured width is only valid while the thing that produced it is the same. Two things can
     * change it without any string changing: the player's text scale, and a resource reload replacing
     * the font. Neither is visible from here — this package names no game class — so the caller supplies
     * a number that changes when either does, and the memo empties itself when that number moves. A
     * caller that passed a constant would be caching widths across a font change, which shows up as text
     * laid out to a width it no longer has.
     *
     * <p>Bounded, and emptied rather than trimmed when it fills: the same policy
     * {@code shape.Shapes.Tables} uses, and for the same reason — this is probe data for a frame, the
     * bound is what stops it being a leak, and a size check on every insert would cost more than the
     * occasional refill.
     *
     * @param delegate what actually measures; asked once per distinct string per epoch
     * @param epoch    a number that changes when a glyph's width can have changed
     */
    public static Measure cached(Measure delegate, java.util.function.LongSupplier epoch) {
        return new CachedMeasure(delegate, epoch);
    }
}
