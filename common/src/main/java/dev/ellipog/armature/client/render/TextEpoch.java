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

    /** The epoch as it stands. Equal to a previous reading exactly when nothing that changes a width has. */
    public static long now() {
        Minecraft minecraft = Minecraft.getInstance();
        int font = minecraft == null || minecraft.font == null
                ? 0
                : System.identityHashCode(minecraft.font);
        return (font * 31L) + Double.doubleToLongBits(TextScale.get());
    }
}
