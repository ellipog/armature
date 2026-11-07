package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ui.shape.Plans;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the game renderer's masks go in its one texture, and what goes in them, worked out without the texture.
 *
 * <h2>Why this half is game-free</h2>
 *
 * <p>Because both halves of it are the kind of arithmetic that goes wrong silently. A packer that hands out
 * two overlapping regions draws one shape's pixels inside another, and a mask that leaves out a texel the plan
 * fills draws a node with a hole in it; neither throws, and both look like a rendering glitch rather than a
 * bug. So the packing and the rasterising live here, where a test can hold them, and {@code GpuMasks} only
 * moves the bytes.
 *
 * <h2>Shelves, in a handful of heights</h2>
 *
 * <p>Regions are packed left to right along shelves, and a shelf is opened at one of {@link #SHELF_HEIGHTS}
 * rather than at the exact height asked for. Node sizes step by a pixel through a zoom, so exact heights would
 * open a shelf per size and spend the texture's rows on shelves holding one mask each; rounding up to the next
 * listed height costs at most a third of a shelf and keeps the count small. Every region carries a one-texel
 * gutter to its right and below, written as transparent with the region, so a sample that lands a hair past
 * a mask's edge reads nothing rather than its neighbour.
 *
 * <h2>Full is not an error</h2>
 *
 * <p>{@link #allocate} answers null when there is no room, and the caller draws that shape as fills instead.
 * Emptying the texture is the caller's decision, because only the caller knows when nothing on screen still
 * reads it.
 */
final class MaskAtlas {

    /**
     * The heights a shelf may be, gutter included. A region goes on the shortest that holds it; anything
     * taller than the last is refused, which is what bounds a mask's height.
     *
     * <p>The last is {@code GpuMasks.MAX_SIDE} and its gutter, so the biggest mask the renderer asks for always
     * has a shelf, and a test holds the two together. A shelf of the two tallest takes an eighth or a tenth of
     * a 2048 texture's height, and only the big nodes at the closest zoom open one.
     */
    static final int[] SHELF_HEIGHTS = {2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 129, 193, 257};

    /** One placed region: its texels, without the gutter. */
    record Region(int x, int y, int width, int height) {
    }

    /** One open shelf: where it starts, how tall it is, and how far along it is full. */
    private static final class Shelf {
        final int y;
        final int height;
        int used;

        Shelf(int y, int height) {
            this.y = y;
            this.height = height;
        }
    }

    private final int size;
    private final List<Shelf> shelves = new ArrayList<>();
    private int nextShelfY;

    /** An empty atlas, {@code size} texels square. */
    MaskAtlas(int size) {
        if (size < 2) {
            throw new IllegalArgumentException("an atlas needs room for one texel and its gutter: " + size);
        }
        this.size = size;
    }

    int size() {
        return size;
    }

    /** Forgets every region. The texture's texels are not touched; the next regions simply write over them. */
    void reset() {
        shelves.clear();
        nextShelfY = 0;
    }

    /**
     * Room for {@code width} by {@code height} texels and the gutter, or null when there is none.
     *
     * <p>A shelf of the right height with room left is used first, so a run of same-sized masks packs into one
     * shelf; only then is a new shelf opened below the last.
     */
    Region allocate(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        int w = width + 1;
        int h = shelfHeight(height + 1);
        if (h < 0 || w > size) {
            return null;
        }
        for (Shelf shelf : shelves) {
            if (shelf.height == h && shelf.used + w <= size) {
                Region region = new Region(shelf.used, shelf.y, width, height);
                shelf.used += w;
                return region;
            }
        }
        if (nextShelfY + h > size) {
            return null;
        }
        Shelf shelf = new Shelf(nextShelfY, h);
        nextShelfY += h;
        shelves.add(shelf);
        shelf.used = w;
        return new Region(0, shelf.y, width, height);
    }

    /** The shortest listed shelf height that holds {@code height} texels, or -1 when none does. */
    private static int shelfHeight(int height) {
        for (int candidate : SHELF_HEIGHTS) {
            if (candidate >= height) {
                return candidate;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // What goes in a mask
    // ------------------------------------------------------------------

    /**
     * A plan's bounding box, {@code {left, top, right, bottom}} measured from the plan's origin, or null for a
     * plan with nothing in it.
     */
    static int[] bounds(Plans.Plan plan) {
        int count = plan.count();
        if (count == 0) {
            return null;
        }
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            left = Math.min(left, Math.min(plan.x1(i), plan.x2(i)));
            top = Math.min(top, Math.min(plan.y1(i), plan.y2(i)));
            right = Math.max(right, Math.max(plan.x1(i), plan.x2(i)));
            bottom = Math.max(bottom, Math.max(plan.y1(i), plan.y2(i)));
        }
        return right > left && bottom > top ? new int[] {left, top, right, bottom} : null;
    }

    /**
     * Which texels of a plan's box its rectangles fill, one byte each and row by row, or null when two of its
     * rectangles cover the same texel.
     *
     * <p>Null rather than a count, because a mask can only say "inked" or "not": a texel two fills cover is
     * blended twice when drawn as fills, and once when drawn from a mask, and that is a different colour. A
     * plan merged from a shape's spans can never overlap itself (its rows are disjoint, and so are the spans
     * on a row), so this is a guard for a plan built some other way, not a case that happens.
     *
     * @param bounds the plan's box, from {@link #bounds}
     */
    static byte[] coverage(Plans.Plan plan, int[] bounds) {
        int left = bounds[0];
        int top = bounds[1];
        int width = bounds[2] - left;
        int height = bounds[3] - top;
        byte[] out = new byte[width * height];
        for (int i = 0; i < plan.count(); i++) {
            int x1 = Math.min(plan.x1(i), plan.x2(i)) - left;
            int x2 = Math.max(plan.x1(i), plan.x2(i)) - left;
            int y1 = Math.min(plan.y1(i), plan.y2(i)) - top;
            int y2 = Math.max(plan.y1(i), plan.y2(i)) - top;
            for (int y = y1; y < y2; y++) {
                int row = y * width;
                for (int x = x1; x < x2; x++) {
                    if (out[row + x] != 0) {
                        return null;
                    }
                    out[row + x] = 1;
                }
            }
        }
        return out;
    }

    /**
     * One row of marks: {@code width} texels inked from each column start, measured from the first, or null
     * when the starts are out of order or two marks share a texel.
     *
     * <p>The same refusal as {@link #coverage}, for the same reason. A lattice whose marks overlap across a row
     * is drawn as fills, which blend the overlap twice; rows overlapping is fine, because every row is the same
     * strip and two quads over one texel blend it twice exactly as two fills do.
     *
     * @return the strip, {@code xs[count - 1] + width - xs[0]} texels long
     */
    static byte[] strip(int[] xs, int count, int width) {
        if (count <= 0 || width <= 0) {
            return null;
        }
        int origin = xs[0];
        for (int i = 1; i < count; i++) {
            if (xs[i] - xs[i - 1] < width) {
                return null;
            }
        }
        byte[] out = new byte[xs[count - 1] + width - origin];
        for (int i = 0; i < count; i++) {
            int at = xs[i] - origin;
            for (int x = at; x < at + width; x++) {
                out[x] = 1;
            }
        }
        return out;
    }
}
