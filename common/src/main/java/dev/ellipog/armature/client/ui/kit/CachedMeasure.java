package dev.ellipog.armature.client.ui.kit;

import dev.ellipog.armature.client.ui.CacheHits;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A measure that answers a string it has already been asked about, until something changes a glyph.
 *
 * <h2>Why the memo belongs here rather than at a call site</h2>
 *
 * <p>Because the expensive pattern is not one call, it is the one {@link Measure#truncate} makes: it
 * walks a string a character at a time asking how wide each prefix is, so measuring one label once is a
 * handful of font lookups in the best case and a few dozen in the worst — and a label drawn every frame
 * asks the same questions every frame, in the same order, for the same answers. Caching the
 * <b>width</b> rather than a caller's truncated result is what makes that free without any caller
 * knowing: the rules stay the rules, every site that measures text pays less, and the same applies to
 * {@link TextWrap}, which walks a paragraph the same way.
 *
 * <h2>The epoch is the caller's, and it is not optional</h2>
 *
 * <p>A measured width is valid only while the thing that produced it is the same. Two things can change
 * it without any string changing: the player's text scale, and a resource reload replacing the font.
 * Neither is visible from here — this package names no game class — so the caller supplies a number that
 * changes when either does, and the memo empties itself when that number moves. A caller that passed a
 * constant would be caching widths across a font change, which shows up as text laid out to a width it
 * no longer has.
 *
 * <h2>Bounded, and emptied rather than trimmed</h2>
 *
 * <p>The same policy {@code shape.Shapes.Tables} uses for its span tables, and for the same reason: this
 * is probe data for a frame, the bound is what stops a long session's worth of labels being a leak, and
 * a size check on every insert would cost more than the occasional refill.
 *
 * <h2>Why this is a class rather than a nested one</h2>
 *
 * <p>It was a private nested type inside {@link Measure}, which an interface does not allow: a field
 * declared in an interface is implicitly public, so `private static final int` there does not compile.
 * The interface's own members are the two rules and the factory; the memo is an implementation, and an
 * implementation belongs in a file where it can be read on its own.
 */
final class CachedMeasure implements Measure {

    /** How many distinct strings one memo holds before it starts again. */
    private static final int LIMIT = 4096;

    private final Measure delegate;
    private final LongSupplier epoch;
    private final Map<String, Integer> widths = new HashMap<>(256);

    private long heldEpoch;
    private boolean stamped;

    CachedMeasure(Measure delegate, LongSupplier epoch) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
    }

    @Override
    public int width(String text) {
        long now = epoch.getAsLong();
        if (!stamped || now != heldEpoch) {
            widths.clear();
            heldEpoch = now;
            stamped = true;
        }
        Integer hit = widths.get(text);
        // Reported for both answers, and this is the cache where the ratio is most worth reading: a width
        // is asked for per label per frame, so a rate near one is the memo doing its job and a rate near
        // zero means every measurement is being redone -- which is exactly what an epoch that moves every
        // frame would produce, and what nothing else could show. See CacheHits.
        CacheHits.asked(WIDTH_CACHE, hit != null);
        if (hit != null) {
            return hit;
        }
        int measured = delegate.width(text);
        if (widths.size() >= LIMIT) {
            widths.clear();
        }
        widths.put(text, measured);
        return measured;
    }

    /** This memo's name in the hit/miss report. A constant, so a probe allocates nothing. */
    private static final String WIDTH_CACHE = "widths";

    @Override
    public int lineHeight() {
        return delegate.lineHeight();
    }

    @Override
    public String toString() {
        return "Measure.cached(" + delegate + ")";
    }
}
