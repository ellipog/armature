package dev.ellipog.armature.client.render;

/**
 * The pixels of a one-pixel line, and the fills that draw them. Game-free, so the rule can be tested.
 *
 * <h2>The rule</h2>
 *
 * <p>A segment from its start to {@code (dx, dy)} past it takes {@code c = max(|dx|, |dy|)} steps. Step
 * {@code i}, from 0 to {@code c}, is the pixel {@link #step}{@code (dx, i, c)}, {@link #step}{@code (dy, i, c)}
 * from the start, where a pixel is named by its top-left corner. The longer axis moves one pixel per step, so
 * each column (or row) of a segment holds exactly one pixel. A segment after the first leaves out step 0,
 * which is the last step of the segment before it.
 *
 * <p>It is the walk a straight line has always been drawn with, so a caller that used to compute this walk
 * and send its runs as fills gets the same pixels from {@link GuiRenderer#hairline}.
 *
 * <h2>Why the GPU can draw it</h2>
 *
 * <p>{@link #step} rounds the float quotient {@code (float) d * i / c}. Below {@link #EXACT_STEPS} steps that
 * equals the exact quotient rounded half up: the product is exact in a float, the exact quotient is at least
 * {@code 1 / (2c)} away from any half it does not equal, and the float's rounding error is less than that. So a
 * shader can work the same pixel out in integers, {@link #exactStep}, and agree with the CPU everywhere. A
 * longer segment is drawn as fills.
 */
public final class Hairlines {

    /** Segments shorter than this many steps round the same in floats and in integers. See the class note. */
    public static final int EXACT_STEPS = 2896;

    private Hairlines() {
    }

    /** Where step {@code i} of {@code c} lands along an axis that moves {@code d} in all. */
    public static int step(int d, int i, int c) {
        return c == 0 ? 0 : Math.round((float) d * i / c);
    }

    /**
     * The same, in integers: {@code d * i / c} rounded half up. What the shader computes, and equal to
     * {@link #step} for {@code c < EXACT_STEPS}.
     */
    public static int exactStep(int d, int i, int c) {
        if (c == 0) {
            return 0;
        }
        long num = 2L * d * i + c;
        long den = 2L * c;
        return (int) Math.floorDiv(num, den);
    }

    /** How many steps a segment takes: its longer axis. */
    public static int steps(int dx, int dy) {
        return Math.max(Math.abs(dx), Math.abs(dy));
    }

    /**
     * Whether the pixel {@code (x, y)} from a segment's start is one of its steps. The shader's test, in Java,
     * with the same integer arithmetic, so the tests can hold the shader to {@link #step}.
     */
    static boolean onSegment(int x, int y, int dx, int dy) {
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        if (ax >= ay) {
            int i = dx > 0 ? x : -x;
            return i >= 0 && i <= ax && y == roundedQuotient(dy * i, ax);
        }
        int i = dy > 0 ? y : -y;
        return i >= 0 && i <= ay && x == roundedQuotient(dx * i, ay);
    }

    /** {@code n / c} rounded half up, for {@code c > 0}, written as the shader writes it. */
    private static int roundedQuotient(int n, int c) {
        int top = 2 * n + c;
        int bottom = 2 * c;
        return top >= 0 ? top / bottom : -((bottom - 1 - top) / bottom);
    }

    /**
     * The quad a GPU draws a segment along neither axis through, as four corners from the segment's start, in
     * a fill's order (top left, bottom left, bottom right, top right): {@code x, y} for each into {@code out}.
     *
     * <p>Along the longer axis it covers every cell a step fills from step {@code first}. Across it, it
     * reaches 1.5 pixels before the exact line and 2.5 past it, which holds every pixel of the walk: a pixel's
     * top edge is within half a pixel of the line at the start of its column, and the line moves at most one
     * pixel across a column. The shader then keeps only the pixels {@link #onSegment} names.
     */
    static void band(int dx, int dy, int first, float[] out) {
        boolean wide = Math.abs(dx) >= Math.abs(dy);
        int along = wide ? dx : dy;
        float slope = (float) (wide ? dy : dx) / along;
        // The cells run from the start to the end; the start cell is left out when the segment before drew it.
        float a0 = along > 0 ? first : along;
        float a1 = along > 0 ? along + 1 : 1 - first;
        float c0 = slope * a0;
        float c1 = slope * a1;
        if (wide) {
            out[0] = a0;
            out[1] = c0 - 1.5F;
            out[2] = a0;
            out[3] = c0 + 2.5F;
            out[4] = a1;
            out[5] = c1 + 2.5F;
            out[6] = a1;
            out[7] = c1 - 1.5F;
        }
        else {
            out[0] = c0 - 1.5F;
            out[1] = a0;
            out[2] = c1 - 1.5F;
            out[3] = a1;
            out[4] = c1 + 2.5F;
            out[5] = a1;
            out[6] = c0 + 2.5F;
            out[7] = a0;
        }
    }

    /**
     * Draws a route's pixels as fills: per segment, one rectangle per run of pixels that share a row (a
     * segment wider than tall) or a column (taller than wide).
     */
    public static void fill(GuiRenderer r, int x, int y, int[] route, int argb) {
        int points = route.length / 2;
        if (points < 2) {
            return;
        }
        for (int k = 0; k + 1 < points; k++) {
            int sx = x + route[2 * k];
            int sy = y + route[2 * k + 1];
            int dx = route[2 * k + 2] - route[2 * k];
            int dy = route[2 * k + 3] - route[2 * k + 1];
            segment(r, sx, sy, dx, dy, k == 0 ? 0 : 1, argb);
        }
    }

    /**
     * One segment's pixels from step {@code first} on, as runs along its longer axis. A segment of no length
     * is its start pixel, and only when it is the first one: otherwise that pixel is already drawn.
     */
    static void segment(GuiRenderer r, int sx, int sy, int dx, int dy, int first, int argb) {
        int c = steps(dx, dy);
        if (first > c) {
            return;
        }
        boolean wide = Math.abs(dx) >= Math.abs(dy);
        int runStart = first;
        for (int i = first; i <= c; i++) {
            boolean last = i == c;
            // The cross-axis offset is what a run shares; it changes at most once per step.
            int across = wide ? step(dy, i, c) : step(dx, i, c);
            if (!last && (wide ? step(dy, i + 1, c) : step(dx, i + 1, c)) == across) {
                continue;
            }
            int along0 = wide ? step(dx, runStart, c) : step(dy, runStart, c);
            int along1 = wide ? step(dx, i, c) : step(dy, i, c);
            int low = Math.min(along0, along1);
            int high = Math.max(along0, along1) + 1;
            if (wide) {
                r.fill(sx + low, sy + across, sx + high, sy + across + 1, argb);
            }
            else {
                r.fill(sx + across, sy + low, sx + across + 1, sy + high, argb);
            }
            runStart = i + 1;
        }
    }
}
