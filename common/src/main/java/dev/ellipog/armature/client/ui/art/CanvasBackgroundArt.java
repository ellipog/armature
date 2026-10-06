package dev.ellipog.armature.client.ui.art;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Viewport;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a theme's canvas background: the pattern over the canvas colour.
 *
 * <h2>What this is not allowed to cost</h2>
 *
 * <p>A background is decoration, and the drawing it backs is not. So every kind is emitted as a
 * bounded number of draw calls, inside the visible rectangle only, inside the caller's batch — and the
 * two escape hatches are honest rather than clever: a pattern whose repeats fall closer than
 * {@link #MIN_VISIBLE} screen pixels <b>fades out</b> instead of turning to moiré, and a pattern that
 * would exceed {@link #FILL_BUDGET} fills <b>fades out</b> instead of halving the frame rate. Both
 * fades are per-frame arithmetic on the same ink, so nothing pops: the pattern is simply less there
 * until it is not.
 *
 * <h2>The ink is the theme's alpha</h2>
 *
 * <p>{@code ink} is the theme's {@code canvasPattern} token, and its alpha is the strength: a theme
 * wanting a whisper of graph paper says {@code #22...} and one wanting a blueprint says {@code #55...}.
 * The fades above multiply into that alpha rather than replacing it, so an author always gets the
 * relationship they wrote, scaled.
 *
 * <h2>Space</h2>
 *
 * <p>In {@link CanvasBackground.Space#GRAPH} the repeats are content points, so the pattern pans and
 * zooms with the graph: {@link Viewport#contentX} decides which lattice points are on screen and
 * {@link Viewport#screenX} places them, which is one rounding per point and stable under any pan. In
 * {@link CanvasBackground.Space#SCREEN} the lattice is the screen's own, so nothing moves.
 *
 * <h2>Hatch is a field of ticks, deliberately</h2>
 *
 * <p>True forty-five-degree lines cost one fill per pixel of ink — a full-screen hatch would be tens
 * of thousands of fills <i>per frame</i> to draw a texture nobody looks at directly. So {@link
 * CanvasBackground.Kind#HATCH} draws short diagonal ticks on the lattice instead: three square steps
 * each, a constant three fills per repeat whatever the zoom. It reads as hatching at every size and
 * it cannot eat the frame; the trade is stated here because it is a real difference from the name.
 *
 * <h2>An image needs its file's size, and the painter does not go looking for it</h2>
 *
 * <p>{@link CanvasBackground.Kind#IMAGE} is the one kind whose arithmetic depends on a number the
 * theme does not carry: the texture's real pixel size. It is asked of the {@link GuiRenderer} seam —
 * never of a Minecraft singleton, so this class stays a function of its arguments — and the answer is
 * remembered per texture for the session, because a PNG cannot change under a running client and
 * re-reading a header once a frame to redraw the same wallpaper is work bought with nothing. A
 * texture whose size cannot be read draws nothing, the same way a spacing too small fades to nothing:
 * a background is decoration, and decoration does not get to guess.
 */
public final class CanvasBackgroundArt {

    /** Below this on-screen repeat distance a pattern is a shimmer, and fades towards nothing. */
    public static final int MIN_VISIBLE = 5;

    /** At or above this the pattern is drawn at the strength the theme's ink asked for. */
    public static final int FULL_VISIBLE = 12;

    /** The most fills one background may add to one frame. Past it the ink fades, not the frame rate. */
    public static final int FILL_BUDGET = 24_000;

    /** An ink faded below this alpha is not worth its fills, and draws nothing at all. */
    public static final int MIN_INK_ALPHA = 8;

    /** How many square steps one hatch tick is made of. */
    public static final int HATCH_STEPS = 3;

    private CanvasBackgroundArt() {
    }

    /**
     * Draws one background over a viewport's rectangle.
     *
     * <p>The caller owns the clip and the canvas colour — this draws only the pattern, and only what
     * the viewport shows. Nothing here allocates per repeat; the per-axis position arrays are the one
     * allocation, sized to the visible repeats.
     */
    public static void draw(GuiRenderer r, Viewport view, CanvasBackground background, int ink) {
        if (background == null || background.isNone() || Colour.invisible(ink)) {
            return;
        }
        int x0 = view.originX();
        int y0 = view.originY();
        int x1 = view.viewRight();
        int y1 = view.viewBottom();
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        switch (background.kind()) {
            case DOTS -> dots(r, view, background, ink, x0, y0, x1, y1);
            case GRID_LINES -> grid(r, view, background, ink, x0, y0, x1, y1);
            case SPECKLE -> speckle(r, view, background, ink, x0, y0, x1, y1);
            case HATCH -> hatch(r, view, background, ink, x0, y0, x1, y1);
            case IMAGE -> image(r, background, ink, x0, y0, x1, y1);
            case NONE -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // The kinds
    // ------------------------------------------------------------------

    private static void dots(GuiRenderer r, Viewport view, CanvasBackground background, int ink,
                             int x0, int y0, int x1, int y1) {
        float fade = spacingFade(stepPixels(view, background));
        if (fade <= 0F) {
            return;
        }
        int[] xs = axis(view, background, true, x0, x1);
        int[] ys = axis(view, background, false, y0, y1);
        int colour = inkAt(ink, Math.min(fade, budgetFade(xs.length * ys.length)));
        if (Colour.invisible(colour)) {
            return;
        }
        // A dot grows with the zoom the way the content does; a pattern nailed to one pixel while the
        // graph magnifies around it reads as dust on the lens rather than as the surface.
        int dot = background.tuning().size()
                + (background.space() == CanvasBackground.Space.GRAPH && view.scale() >= 1.75F ? 1 : 0);
        for (int x : xs) {
            for (int y : ys) {
                r.fill(x, y, x + dot, y + dot, colour);
            }
        }
    }

    private static void grid(GuiRenderer r, Viewport view, CanvasBackground background, int ink,
                             int x0, int y0, int x1, int y1) {
        float fade = spacingFade(stepPixels(view, background));
        if (fade <= 0F) {
            return;
        }
        int[] xs = axis(view, background, true, x0, x1);
        int[] ys = axis(view, background, false, y0, y1);
        int colour = inkAt(ink, Math.min(fade, budgetFade(xs.length + ys.length)));
        if (Colour.invisible(colour)) {
            return;
        }
        int thickness = background.tuning().size();
        for (int x : xs) {
            r.fill(x, y0, x + thickness, y1, colour);
        }
        for (int y : ys) {
            r.fill(x0, y, x1, y + thickness, colour);
        }
    }

    private static void speckle(GuiRenderer r, Viewport view, CanvasBackground background, int ink,
                                int x0, int y0, int x1, int y1) {
        float fade = spacingFade(stepPixels(view, background));
        if (fade <= 0F) {
            return;
        }
        int spacing = background.spacing();
        int density = background.tuning().density();
        boolean screen = background.space() == CanvasBackground.Space.SCREEN;
        long kx0 = cell(view, background, x0, true);
        long kx1 = cell(view, background, x1 - 1, true);
        long ky0 = cell(view, background, y0, false);
        long ky1 = cell(view, background, y1 - 1, false);
        long cells = (kx1 - kx0 + 1) * (ky1 - ky0 + 1);
        if (cells <= 0) {
            return;
        }
        int colour = inkAt(ink, Math.min(fade,
                budgetFade((int) Math.min(Integer.MAX_VALUE, cells / density))));
        if (Colour.invisible(colour)) {
            return;
        }
        int size = background.tuning().size();
        for (long kx = kx0; kx <= kx1; kx++) {
            for (long ky = ky0; ky <= ky1; ky++) {
                long h = mix(kx, ky);
                // One cell in `density` carries a speck. The hash decides which, so the field is stable
                // across frames and runs while staying irregular to the eye.
                if (Math.floorMod(h, density) != 0L) {
                    continue;
                }
                float fx = ((h >>> 16) & 0xFF) / 256F;
                float fy = ((h >>> 8) & 0xFF) / 256F;
                int px = screen
                        ? (int) (kx * spacing + Math.round(fx * spacing))
                        : view.screenX((float) (kx * spacing + fx * spacing));
                int py = screen
                        ? (int) (ky * spacing + Math.round(fy * spacing))
                        : view.screenY((float) (ky * spacing + fy * spacing));
                if (px < x0 || px >= x1 || py < y0 || py >= y1) {
                    continue;
                }
                r.fill(px, py, px + size, py + size, colour);
            }
        }
    }

    private static void hatch(GuiRenderer r, Viewport view, CanvasBackground background, int ink,
                              int x0, int y0, int x1, int y1) {
        float fade = spacingFade(stepPixels(view, background));
        if (fade <= 0F) {
            return;
        }
        int[] xs = axis(view, background, true, x0, x1);
        int[] ys = axis(view, background, false, y0, y1);
        int ticks = xs.length * ys.length;
        int colour = inkAt(ink, Math.min(fade, budgetFade(ticks * HATCH_STEPS)));
        if (Colour.invisible(colour)) {
            return;
        }
        boolean graph = background.space() == CanvasBackground.Space.GRAPH;
        int step = graph ? Math.max(1, Math.min(4, Math.round(1.5F * view.scale()))) : 2;
        int size = background.tuning().size();
        // The two diagonals are the same walk with one sign flipped. Slash is the default because a
        // "/" leaning with the reading direction is the quieter default; backslash is the mirror.
        boolean backslash = background.tuning().backslash();
        for (int x : xs) {
            for (int y : ys) {
                for (int i = 0; i < HATCH_STEPS; i++) {
                    int px = x + i * step;
                    int py = backslash ? y + i * step : y - i * step;
                    if (px >= x1 || py >= y1 || py < y0) {
                        break;
                    }
                    r.fill(px, py, px + size, py + size, colour);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    /**
     * The sizes read this session, so a wallpaper's header is not re-parsed every frame.
     *
     * <p>Keyed with the resource stamp the size was read against — see {@link #textureSizeOf} for why the
     * stamp is part of the entry rather than an invalidation hook.
     */
    private static final Map<ResourceLocation, Sized> TEXTURE_SIZES = new HashMap<>();

    /** One remembered size, and the resource stamp it was read against. */
    private record Sized(GuiRenderer.TextureSize size, long stamp) {
    }

    private static void image(GuiRenderer r, CanvasBackground background, int ink,
                              int x0, int y0, int x1, int y1) {
        CanvasBackground.Image image = background.image();
        if (image.texture().isEmpty()) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(image.texture());
        if (id == null) {
            return;
        }
        GuiRenderer.TextureSize size = textureSizeOf(r, id);
        if (size == null || size.width() <= 0 || size.height() <= 0) {
            return;
        }
        if (image.fit() == CanvasBackground.Fit.COVER) {
            cover(r, id, size, ink, x0, y0, x1, y1);
        }
        else {
            tile(r, image, id, size, ink, x0, y0, x1, y1);
        }
    }

    /**
     * The size of a texture, from this session's cache or from the renderer.
     *
     * <h2>Two things a hit has to be true of</h2>
     *
     * <p>Only hits are cached, because a miss means "not loadable right now" and a resource pack that
     * arrives later should be allowed to answer on the next frame rather than be remembered as absent
     * forever.
     *
     * <p>And a hit is only a hit while the <b>resource behind it is the same one</b>. A pack reload
     * replaces the file, and a wallpaper whose replacement has different dimensions would keep the old
     * numbers — so a tiled background tiles at the wrong pitch and a covered one is stretched to the wrong
     * aspect, for the rest of the session, with nothing to notice it by. The stamp is how the entry learns
     * that, and it is one identity comparison rather than a re-read of the file: reading the header again
     * every frame is exactly what this cache exists to avoid. See {@code GuiRenderer.textureStamp}.
     *
     * <p>A renderer that reports a constant stamp — a recording renderer, say — never invalidates, which is
     * the right answer for one that reads no resource manager: nothing it reported can go stale.
     */
    private static GuiRenderer.TextureSize textureSizeOf(GuiRenderer r, ResourceLocation id) {
        long stamp = r.textureStamp(id);
        Sized cached = TEXTURE_SIZES.get(id);
        if (cached != null && cached.stamp() == stamp) {
            return cached.size();
        }
        GuiRenderer.TextureSize read = r.textureSize(id).orElse(null);
        if (read != null) {
            TEXTURE_SIZES.put(id, new Sized(read, stamp));
        }
        else {
            // A stamp that no longer matches means the file was replaced by something unreadable, so the
            // stale entry goes rather than being kept for a frame that would draw it.
            TEXTURE_SIZES.remove(id);
        }
        return read;
    }

    /**
     * Repeats the texture across the visible rectangle, screen-fixed.
     *
     * <h2>Phase zero, and the step on each axis</h2>
     *
     * <p>The lattice starts at the visible rectangle's top-left corner rather than at the viewport
     * origin: a screen-fixed pattern has no content to be anchored to, and starting anywhere else
     * would make the first tile a partial one whose seam moves with the pan. The vertical step is the
     * destination <i>height</i>, not the width, because a rectangular texture only tiles seamlessly
     * when each repeat advances by exactly what one image covers.
     */
    private static void tile(GuiRenderer r, CanvasBackground.Image image, ResourceLocation id,
                             GuiRenderer.TextureSize size, int ink,
                             int x0, int y0, int x1, int y1) {
        int tileWidth = image.tileSize();
        int tileHeight = Math.max(1, Math.round(tileWidth * (float) size.height() / size.width()));
        float fade = spacingFade(tileWidth);
        if (fade <= 0F) {
            return;
        }
        int across = (x1 - x0 + tileWidth - 1) / tileWidth;
        int down = (y1 - y0 + tileHeight - 1) / tileHeight;
        int colour = inkAt(ink, Math.min(fade, budgetFade(across * down)));
        if (Colour.invisible(colour)) {
            return;
        }
        int tint = 0xFFFFFF | (Colour.alpha(colour) << 24);
        for (int y = y0; y < y1; y += tileHeight) {
            for (int x = x0; x < x1; x += tileWidth) {
                r.scaled(id, x, y, tileWidth, tileHeight, 0F, 0F, size.width(), size.height(),
                        size.width(), size.height(), tint);
            }
        }
    }

    /**
     * Draws the texture once, scaled to cover the visible rectangle and centred.
     *
     * <p>Which axis overflows decides the source region: the scale is the larger of the two ratios,
     * so the smaller ratio's axis is fully used and the other is cropped evenly on both sides. The
     * arithmetic is written as a source rectangle rather than a scale factor because that is what the
     * seam's blit takes, and because "what part of the file is on screen" is the thing to check.
     */
    private static void cover(GuiRenderer r, ResourceLocation id, GuiRenderer.TextureSize size,
                              int ink, int x0, int y0, int x1, int y1) {
        int colour = inkAt(ink, 1F);
        if (Colour.invisible(colour)) {
            return;
        }
        int width = x1 - x0;
        int height = y1 - y0;
        float scale = Math.max(width / (float) size.width(), height / (float) size.height());
        int sourceWidth = Math.min(size.width(), Math.max(1, Math.round(width / scale)));
        int sourceHeight = Math.min(size.height(), Math.max(1, Math.round(height / scale)));
        int u = (size.width() - sourceWidth) / 2;
        int v = (size.height() - sourceHeight) / 2;
        int tint = 0xFFFFFF | (Colour.alpha(colour) << 24);
        r.scaled(id, x0, y0, width, height, u, v, sourceWidth, sourceHeight,
                size.width(), size.height(), tint);
    }

    // ------------------------------------------------------------------
    // The shared arithmetic
    // ------------------------------------------------------------------

    /** The repeat distance in screen pixels: content units zoom, screen units do not. */
    private static float stepPixels(Viewport view, CanvasBackground background) {
        return background.space() == CanvasBackground.Space.SCREEN
                ? background.spacing()
                : background.spacing() * view.scale();
    }

    /** Fades a pattern in as its repeats grow past {@link #MIN_VISIBLE}, reaching full at {@link #FULL_VISIBLE}. */
    private static float spacingFade(float stepPixels) {
        float fade = (stepPixels - MIN_VISIBLE) / (float) (FULL_VISIBLE - MIN_VISIBLE);
        return fade < 0F ? 0F : (fade > 1F ? 1F : fade);
    }

    /** Fades a pattern out as it outgrows the fill budget, reaching nothing well before it matters. */
    private static float budgetFade(int fills) {
        return fills <= FILL_BUDGET ? 1F : FILL_BUDGET / (float) fills;
    }

    /** The ink at a fade, or zero when what is left would draw nothing worth drawing. */
    private static int inkAt(int ink, float fade) {
        int alpha = Math.round(Colour.alpha(ink) * fade);
        return alpha < MIN_INK_ALPHA ? 0 : Colour.withAlpha(ink, alpha);
    }

    /**
     * Where the repeats land on one screen axis, ascending.
     *
     * <p>Content-anchored repeats are the content lattice's own points, placed one at a time; the
     * rounding to pixels happens here, once per repeat, and both branches return screen pixels — which
     * is what lets the drawing above not know which space it is in.
     */
    private static int[] axis(Viewport view, CanvasBackground background, boolean horizontal,
                              int from, int to) {
        int capacity = (int) ((to - from) / stepPixels(view, background)) + 2;
        int[] out = new int[Math.max(1, capacity)];
        int n = 0;
        if (background.space() == CanvasBackground.Space.SCREEN) {
            int step = background.spacing();
            for (int pos = Math.floorDiv(from, step) * step; pos < to; pos += step) {
                if (pos >= from) {
                    out[n++] = pos;
                }
            }
        }
        else {
            float spacing = background.spacing();
            double content = horizontal ? view.contentX(from) : view.contentY(from);
            long k = (long) Math.ceil(content / (double) spacing);
            while (n < out.length) {
                float at = k * spacing;
                int pos = horizontal ? view.screenX(at) : view.screenY(at);
                if (pos >= to) {
                    break;
                }
                if (pos >= from) {
                    out[n++] = pos;
                }
                k++;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** The cell index one screen coordinate falls in, in the background's own space. */
    private static long cell(Viewport view, CanvasBackground background, int screen, boolean horizontal) {
        if (background.space() == CanvasBackground.Space.SCREEN) {
            return Math.floorDiv(screen, background.spacing());
        }
        double content = horizontal ? view.contentX(screen) : view.contentY(screen);
        return (long) Math.floor(content / background.spacing());
    }

    /**
     * A deterministic hash of a cell.
     *
     * <p>Not a random number: the same cell must decide the same speckle on every frame, at every pan
     * and in every session, or the field would crawl. A splitmix-shaped mix is enough — cells that
     * differ by one must land far apart in the hash, which the multiply-xor chain guarantees.
     */
    private static long mix(long x, long y) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }
}
