package dev.ellipog.armature.client.ui.shape;

import java.util.Arrays;
import java.util.Objects;

/**
 * The pixel transforms that make a node's layers fit inside one another.
 *
 * <h2>Why a table transform rather than a second sampling</h2>
 *
 * <p>A node is drawn as a stack: a ring one pixel outside the outline, the outline itself, a fill one
 * pixel inside it, and a wash and a stand-in block inside that. Every layer used to be the <b>same shape
 * function sampled at another size</b> — the ring at {@code size + 2}, the fill at {@code size - 2} —
 * and nothing related those tables to the panel's. So wherever a feature that depends on the size
 * steps, the tables disagree: the gear's tooth count changes at 32 pixels, a rounded rectangle's
 * integer radius every four, a tome's notch every eight. A 30-pixel gear was drawn with a ring sampled
 * from the 32-pixel gear — eight teeth round a six-tooth node — so the ring had gaps where the panel had
 * teeth and teeth where it had gaps, and the fill, sampled from the 28-pixel gear, reached outside the
 * outline through the gaps. That is a node whose outline does not close, and it is why this class exists.
 *
 * <p>Now there is one table per node — the outline the panel is drawn from — and every other layer is
 * that table transformed by whole pixels. Containment is then true by construction, for every shape,
 * every size, and a turned node as much as an upright one, because a transform cannot introduce a
 * feature the panel does not have.
 *
 * <h2>The offsets are part of the contract</h2>
 *
 * <p>{@link #eroded} assumes its layer is drawn at {@code (x + by, y + by)} in a box {@code 2 * by}
 * smaller — a fill, a wash, a stand-in block — and {@link #dilated} assumes {@code (x - by, y - by)} in a
 * box {@code 2 * by} larger — a ring. The transforms bake those offsets in, and that is what makes the
 * two containment properties exact rather than approximate:
 *
 * <ul>
 *   <li>every pixel an eroded table covers is inside the panel, one pixel to its down-right;</li>
 *   <li>every pixel of the panel is covered by a dilated table, one pixel to its up-left.</li>
 * </ul>
 *
 * <p>{@code ShapeTest} asserts both, over every built-in, every size and a set of angles, which is the
 * check that cannot pass while any size-dependent feature is free to disagree with its own layers.
 */
public final class Outlines {

    private Outlines() {
    }

    /**
     * The outline {@code by} pixels in, for a layer drawn at {@code (x + by, y + by)} in a box
     * {@code 2 * by} smaller: the fill of a panel, a wash, a stand-in block.
     *
     * <p>A layer pixel stands for a whole block of the panel — itself and the {@code by} pixels to its
     * down-right, in both axes — so the layer covers it only when <b>every</b> row of that block covers
     * it, each span losing {@code by} pixels at each end. That is what keeps the border at least one
     * pixel wide on a diagonal as well as on a straight edge: the shifted-row version left the outline
     * under a pixel wherever the row above was wider, which is most of a curve.
     *
     * @param panel the panel's own table, asked at {@code panelSize} and never at the layer's size
     * @param panelSize the panel's size in pixels
     * @param by one for a one-pixel fill; zero or less returns the panel's table unchanged
     */
    public static Shapes.SpansOf eroded(Shapes.SpansOf panel, int panelSize, int by) {
        Objects.requireNonNull(panel, "panel");
        int inset = Math.max(0, by);
        if (inset == 0) {
            return panel;
        }
        Shapes.SpansOf raw = (row, size) -> {
            if (size <= 0 || row < 0 || row >= size) {
                return null;
            }
            // Every panel row the layer's own row covers, not just the one it is shifted to: a fill is
            // only inside the panel if the whole block it stands for is. Shifting one row is what left
            // the outline under a pixel on a diagonal -- the boundary pixel whose up-left neighbour
            // happened to be inside the same row was covered by the fill, because the row above was
            // never consulted. Six of the ten shapes showed it, worst at 156 pixels on one size.
            int[] out = null;
            for (int source = row; source <= row + inset * 2; source++) {
                int[] spans = panel.spansOf(source, panelSize);
                if (spans == null) {
                    return null;   // a row with no material above or below: nothing can be inside here
                }
                int[] kept = new int[spans.length];
                int count = 0;
                for (int i = 0; i < spans.length; i += 2) {
                    int from = spans[i];
                    int to = spans[i + 1] - inset * 2;
                    if (to > from) {
                        kept[count * 2] = from;
                        kept[count * 2 + 1] = to;
                        count++;
                    }
                }
                if (count == 0) {
                    return null;
                }
                out = out == null ? Arrays.copyOf(kept, count * 2) : intersect(out, kept, count);
                if (out == null) {
                    return null;
                }
            }
            return out;
        };
        return Shapes.ofSpans(raw)::spans;
    }

    /**
     * The columns covered by both lists, each list being flattened {@code {from, to, …}} pairs.
     *
     * <p>A pair whose two rows do not overlap is dropped, and an answer with no pairs at all is null:
     * nothing is inside the panel's edge here, and the border colour shows through, which is what a
     * one-pixel tooth or a slim spike of an outline should look like.
     */
    private static int[] intersect(int[] left, int[] right, int rightCount) {
        int[] out = new int[left.length + rightCount * 2];
        int kept = 0;
        for (int i = 0; i < left.length; i += 2) {
            for (int j = 0; j < rightCount * 2; j += 2) {
                int from = Math.max(left[i], right[j]);
                int to = Math.min(left[i + 1], right[j + 1]);
                if (to > from) {
                    out[kept * 2] = from;
                    out[kept * 2 + 1] = to;
                    kept++;
                }
            }
        }
        return kept == 0 ? null : Arrays.copyOf(out, kept * 2);
    }

    /**
     * The outline {@code by} pixels out, for a layer drawn at {@code (x - by, y - by)} in a box
     * {@code 2 * by} larger: a hover or selection ring.
     *
     * <p>A window rather than a shift, because a halo has to sit beside the outline as well as above and
     * below it: the panel's row {@code q} contributes to the layer's rows {@code q} to {@code q + 2 * by},
     * each span growing {@code by} pixels at each end. Without the neighbouring rows the ring would be a
     * copy of the outline moved up and left, and it would come apart at the flat top and bottom of any
     * shape that has them.
     *
     * @param panel the panel's own table, asked at {@code panelSize} and never at the layer's size
     * @param panelSize the panel's size in pixels
     * @param by one for a one-pixel ring; zero or less returns the panel's table unchanged
     */
    public static Shapes.SpansOf dilated(Shapes.SpansOf panel, int panelSize, int by) {
        Objects.requireNonNull(panel, "panel");
        int outset = Math.max(0, by);
        if (outset == 0) {
            return panel;
        }
        Shapes.SpansOf raw = (row, size) -> {
            if (size <= 0 || row < 0 || row >= size) {
                return null;
            }
            int[] out = new int[8];
            int kept = 0;
            for (int source = row - outset * 2; source <= row; source++) {
                int[] spans = panel.spansOf(source, panelSize);
                if (spans == null) {
                    continue;
                }
                for (int i = 0; i < spans.length; i += 2) {
                    if (kept * 2 + 2 > out.length) {
                        out = Arrays.copyOf(out, out.length * 2);
                    }
                    out[kept * 2] = spans[i];
                    out[kept * 2 + 1] = spans[i + 1] + outset * 2;
                    kept++;
                }
            }
            // Pieces from different rows overlap by design; `Shape.spans` sorts and merges them, so a
            // caller can rely on one interval per piece of material exactly as it can for a shape.
            return kept == 0 ? null : Arrays.copyOf(out, kept * 2);
        };
        return Shapes.ofSpans(raw)::spans;
    }
}
