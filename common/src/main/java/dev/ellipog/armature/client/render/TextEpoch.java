package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.TextScale;

import net.minecraft.client.Minecraft;

/**
 * When a measured width may have changed — one number, to be compared and never interpreted.
 *
 * <h2>Why this lives here rather than at each caller</h2>
 *
 * <p>Because the thing it describes is the font, and the font is this package's business. A memoized measure
 * ({@code Measure.cached}) holds answers that are only valid while whatever produced them is the same, and
 * two things change a width without changing a string: the player's text scale, and a resource reload
 * replacing the font. The kit cannot see either — it names no game class — so each memo is handed a number
 * that moves when they do.
 *
 * <p>There are now three readers of it: this package's own width memo, the book screen's measure, and the
 * viewer's truncation fit. Three copies of the number would be three descriptions of one fact, and the way
 * that goes wrong is quiet — one of them forgets the font, or spells the scale differently, and a layout
 * keeps widths measured against the face the pack was reloaded away from.
 *
 * <h2>What is in it, and what is deliberately not</h2>
 *
 * <p>The font is identified rather than compared, because a reload <b>replaces</b> the object: two different
 * objects are two different faces, and the widths measured against the old one are no longer answers. The
 * scale is taken at full precision rather than rounded, so a slider dragged to 1.01 is a different epoch from
 * 1.0 — a layout measured at one and drawn at the other is text that no longer fits its column.
 *
 * <p>The number has no meaning of its own: nothing may read it, compare it for order, or store it across a
 * session. It is only ever asked "is this the same as last time".
 */
public final class TextEpoch {

    private TextEpoch() {
    }

    /**
     * A stable serial per font object, so the epoch can name a face rather than guess at it.
     *
     * <h2>Why not {@code System.identityHashCode}</h2>
     *
     * <p>Because this number decides whether a memo of measured widths is thrown away, and an identity hash
     * is not unique: two fonts can share one, and this used to fold the hash into the epoch as though it
     * were an identity. A collision there is not a wrong colour — it is every width measured against the
     * face the pack was reloaded away from being served for the new one, for the rest of the session, with
     * nothing to notice it by. An identity hash is also not stable across a collection for an object that
     * becomes unreachable, which is a second way for one to be reused.
     *
     * <p>Identity-keyed, so a font that is garbage-collected takes its serial with it and the map cannot
     * grow with every reload a session sees. A font that is still reachable keeps the serial it was given,
     * which is the property the epoch needs: the same face reads the same number, and a replacement reads a
     * different one.
     */
    private static final java.util.Map<Object, Long> SERIALS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** The next serial to hand out. Monotonic, so a serial is never reused even after a collection. */
    private static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong();

    /**
     * The last font asked about and the serial {@link #SERIALS} gave it.
     *
     * <h2>Why this is here, when the map already answers</h2>
     *
     * <p>Because the epoch is read on every width probe (hundreds of times a frame, well over a thousand
     * with a long description open) and the font is the same object for all of them until a reload. Each
     * read was a lock and a hash lookup in a synchronized weak map for an answer that had not changed. With
     * this, the common read is a field read and one comparison, and the map is asked only when the font is
     * not the one asked about last.
     *
     * <p>The pair is one immutable object in one volatile field, so a reader sees a font together with its
     * own serial or not at all. It holds the font <b>weakly</b>, like the map: a font a reload has replaced
     * can still be collected, and a collected font reads as null, which matches no font that is asked about.
     * Nothing changes about the answers. The serial held is the one the map gave this object, and the map
     * keeps it for as long as the object is reachable (which it is, while someone is asking about it), so
     * the same object reads the same serial either way, and a different object misses here and gets its own.
     */
    private static final class Last extends java.lang.ref.WeakReference<Object> {

        private final long serial;

        private Last(Object font, long serial) {
            super(font);
            this.serial = serial;
        }
    }

    private static volatile Last last;

    private static long serialOf(Object font) {
        Last seen = last;
        if (seen != null && font != null && seen.get() == font) {
            return seen.serial;
        }
        long serial;
        // One step under the map's own lock, so two threads meeting a new font cannot both assign it a
        // serial and have the later one overwrite what the first already handed out: an object's serial is
        // decided once. The order serials are handed out in is unchanged.
        synchronized (SERIALS) {
            Long held = SERIALS.get(font);
            if (held == null) {
                held = NEXT.incrementAndGet();
                SERIALS.put(font, held);
            }
            serial = held;
        }
        last = new Last(font, serial);
        return serial;
    }

    /** The epoch as it stands. Equal to a previous reading exactly when nothing that changes a width has. */
    public static long now() {
        Minecraft minecraft = Minecraft.getInstance();
        Object font = minecraft == null ? null : minecraft.font;
        long face = font == null ? 0L : serialOf(font);
        // Mixed rather than added, so two readings that differ in either part differ in the whole. The
        // scale is taken at full precision rather than rounded: a slider dragged to 1.01 is a different
        // epoch from 1.0, because a layout measured at one and drawn at the other is text that no longer
        // fits its column.
        return face * 1_000_003L + Double.doubleToLongBits(TextScale.get());
    }

    /**
     * The epoch for widths measured through a renderer: {@link #now}, folded with which renderer measured.
     *
     * <h2>Why the renderer is part of it</h2>
     *
     * <p>A control keeps one width memo across frames and measures through whichever renderer is drawing
     * it: a screen's, a modal band's, a recording one in a test. The font and the text scale do not change
     * when the renderer does, so without the renderer in the epoch the widths one gave would be served for
     * another, and a control would draw its label at a width its own renderer never agreed to.
     * {@code MeasureCacheTest.cachedOverFollowsTheRenderer} is the case that caught it.
     *
     * <h2>Why every game renderer is one renderer here</h2>
     *
     * <p>The renderer used to be told apart by its identity, and in game that is a new
     * {@link GuiGraphicsRenderer} every frame (a widget wraps the frame's graphics in a fresh one), so the
     * epoch moved every frame: each memo emptied itself on the frame's first probe, never answered across
     * frames, and was only work in front of the memo that did answer. Every {@code GuiGraphicsRenderer}
     * measures the same way: it is a record, so nothing can override how it measures; its widths all come
     * from one static memo over the client's one font, and its line height is that font's at the player's
     * text scale. No instance state enters either. Two of them cannot disagree about a width, so they share
     * an identity, and any other renderer keeps its own.
     *
     * <p>The button, the slider and the tooltip all ask this, so the rule is written once.
     */
    public static long measuredThrough(GuiRenderer measuring) {
        return now() * 31L + identityOf(measuring);
    }

    /**
     * What the epoch knows a renderer by: one value for every {@link GuiGraphicsRenderer}, the identity
     * hash for anything else. See {@link #measuredThrough}.
     *
     * <p>The shared value is negative, which an identity hash on this JVM never is (it is 31 bits), so a
     * game renderer and some other renderer do not share an identity by coincidence.
     */
    static int identityOf(GuiRenderer measuring) {
        return measuring instanceof GuiGraphicsRenderer ? GAME_RENDERER : System.identityHashCode(measuring);
    }

    /** The identity every {@link GuiGraphicsRenderer} shares. See {@link #identityOf}. */
    private static final int GAME_RENDERER = -1;
}
