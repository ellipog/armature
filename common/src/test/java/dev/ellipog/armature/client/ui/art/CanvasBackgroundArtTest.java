package dev.ellipog.armature.client.ui.art;

import dev.ellipog.armature.client.render.RecordingRenderer;
import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.kit.Viewport;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lattice maths, the two fades, the stability of each space, and the image path.
 *
 * <h2>Why a recorder, at this size</h2>
 *
 * <p>Every claim this class makes is about numbers: how many fills, where the repeats land, whether
 * they move when the view does, and at what point the pattern gives up. None of those is visible in
 * a screenshot — a dot grid at the wrong phase looks exactly like a dot grid — and all of them are
 * assertions against a recorder. The image tests add the same questions one level up: a tile that
 * does not abut its neighbour shows as a seam, and a tint that carried the ink's RGB would show as a
 * colour cast, and both are numbers in a recorded call.
 */
@DisplayName("CanvasBackgroundArt")
class CanvasBackgroundArtTest {

    private static final int INK = 0x33FFFFFF;

    private static final ResourceLocation TILED =
            ResourceLocation.fromNamespaceAndPath("armature", "textures/gui/art_tile.png");

    private static final ResourceLocation FIXED =
            ResourceLocation.fromNamespaceAndPath("armature", "textures/gui/art_fixed.png");

    private static final ResourceLocation COVERED =
            ResourceLocation.fromNamespaceAndPath("armature", "textures/gui/art_cover.png");

    private static Viewport view(int width, int height) {
        return Viewport.of(0.05F, 8F).bounds(0, 0, width, height);
    }

    private static int fills(RecordingRenderer r) {
        return r.fills().size();
    }

    /** The distinct fill colour, asserted to be one. */
    private static int colourOf(RecordingRenderer r) {
        return r.fills().get(0).argb();
    }

    private static CanvasBackground procedural(CanvasBackground.Kind kind, int spacing,
                                               CanvasBackground.Space space,
                                               CanvasBackground.Tuning tuning) {
        return new CanvasBackground(kind, space, spacing, tuning, CanvasBackground.Image.NONE);
    }

    private static CanvasBackground image(ResourceLocation id, CanvasBackground.Fit fit,
                                          CanvasBackground.Space space, int tile) {
        return new CanvasBackground(CanvasBackground.Kind.IMAGE, space, 24,
                CanvasBackground.Tuning.DEFAULT, new CanvasBackground.Image(id.toString(), fit, tile));
    }

    private static boolean has(List<RecordingRenderer.Call> calls, int x, int y) {
        return calls.stream().anyMatch(call -> call.x() == x && call.y() == y);
    }

    private static boolean hasStep(List<RecordingRenderer.Call> calls, int dx, int dy) {
        return calls.stream().anyMatch(call -> has(calls, call.x() + dx, call.y() + dy));
    }

    @Test
    @DisplayName("a flat canvas, an invisible ink and a degenerate rectangle all draw nothing")
    void nothingToDraw() {
        RecordingRenderer none = RecordingRenderer.create();
        CanvasBackgroundArt.draw(none, view(100, 60), CanvasBackground.NONE, INK);
        assertEquals(0, fills(none), "a flat canvas is the case every theme starts in");

        RecordingRenderer invisible = RecordingRenderer.create();
        CanvasBackgroundArt.draw(invisible, view(100, 60),
                new CanvasBackground(CanvasBackground.Kind.DOTS, CanvasBackground.Space.GRAPH, 24), 0x00FFFFFF);
        assertEquals(0, fills(invisible), "a fully transparent ink is not a pattern");

        RecordingRenderer empty = RecordingRenderer.create();
        CanvasBackgroundArt.draw(empty, Viewport.of(0.05F, 8F).bounds(5, 5, 0, 0),
                new CanvasBackground(CanvasBackground.Kind.DOTS, CanvasBackground.Space.GRAPH, 24), INK);
        assertEquals(0, fills(empty));
    }

    @Test
    @DisplayName("dots land on the content lattice, one pixel each, in bounds")
    void dotsLandOnTheLattice() {
        RecordingRenderer r = RecordingRenderer.create();
        CanvasBackgroundArt.draw(r, view(100, 60),
                new CanvasBackground(CanvasBackground.Kind.DOTS, CanvasBackground.Space.GRAPH, 24), INK);

        // 0, 24, 48, 72, 96 across; 0, 24, 48 down -- the half-open rectangle excludes the edges.
        assertEquals(5 * 3, fills(r), "15 dots");
        assertEquals(INK, colourOf(r), "at full fade the ink is the theme's own");
        for (RecordingRenderer.Call fill : r.fills()) {
            assertEquals(1, fill.x2() - fill.x(), "one pixel wide");
            assertEquals(1, fill.y2() - fill.y(), "one pixel tall");
            assertTrue(fill.x() >= 0 && fill.x() < 100 && fill.y() >= 0 && fill.y() < 60,
                    "outside the view: " + fill);
            assertEquals(0, fill.x() % 24, "x is on the lattice: " + fill);
            assertEquals(0, fill.y() % 24, "y is on the lattice: " + fill);
        }
    }

    @Test
    @DisplayName("panning moves the pattern with the content; screen space does not move at all")
    void spacesAnchorWhereTheySay() {
        CanvasBackground graph = new CanvasBackground(CanvasBackground.Kind.DOTS,
                CanvasBackground.Space.GRAPH, 24);
        Viewport still = view(100, 60);
        Viewport panned = view(100, 60);
        panned.setOffset(7, 0);

        RecordingRenderer before = RecordingRenderer.create();
        CanvasBackgroundArt.draw(before, still, graph, INK);
        RecordingRenderer after = RecordingRenderer.create();
        CanvasBackgroundArt.draw(after, panned, graph, INK);

        assertEquals(7, after.fills().get(0).x() - before.fills().get(0).x(),
                "the nearest lattice point moved exactly with the pan");
        assertNotEquals(before.fills(), after.fills(), "the pattern is anchored to the graph");

        CanvasBackground screen = new CanvasBackground(CanvasBackground.Kind.DOTS,
                CanvasBackground.Space.SCREEN, 24);
        RecordingRenderer fixedBefore = RecordingRenderer.create();
        CanvasBackgroundArt.draw(fixedBefore, still, screen, INK);
        RecordingRenderer fixedAfter = RecordingRenderer.create();
        CanvasBackgroundArt.draw(fixedAfter, panned, screen, INK);
        assertEquals(fixedBefore.fills(), fixedAfter.fills(),
                "a screen-anchored pattern is a wallpaper: panning must not move it");
    }

    @Test
    @DisplayName("zoom magnifies the dots, and a repeat too close to see draws nothing")
    void zoomScalesAndThenGivesUp() {
        CanvasBackground dots = new CanvasBackground(CanvasBackground.Kind.DOTS,
                CanvasBackground.Space.GRAPH, 24);

        Viewport zoomed = view(100, 60);
        zoomed.setScale(2F);
        RecordingRenderer large = RecordingRenderer.create();
        CanvasBackgroundArt.draw(large, zoomed, dots, INK);
        assertTrue(fills(large) > 0);
        assertEquals(2, large.fills().get(0).x2() - large.fills().get(0).x(),
                "at 2x a dot is two pixels, or the surface does not magnify with the graph");

        Viewport far = view(100, 60);
        far.setScale(0.15F);
        RecordingRenderer gone = RecordingRenderer.create();
        CanvasBackgroundArt.draw(gone, far, dots, INK);
        assertEquals(0, fills(gone),
                "24 units at 0.15 zoom is a 3.6-pixel repeat: a shimmer, and it fades to nothing"
                        + " rather than drawing one");
    }

    @Test
    @DisplayName("grid lines are one fill each, full width or full height")
    void gridLinesAreCheap() {
        RecordingRenderer r = RecordingRenderer.create();
        CanvasBackgroundArt.draw(r, view(100, 60),
                new CanvasBackground(CanvasBackground.Kind.GRID_LINES, CanvasBackground.Space.GRAPH, 24), INK);

        assertEquals(5 + 3, fills(r), "five verticals and three horizontals, one fill each");
        long verticals = r.fills().stream().filter(fill -> fill.x2() - fill.x() == 1).count();
        long horizontals = r.fills().stream().filter(fill -> fill.y2() - fill.y() == 1).count();
        assertEquals(5, verticals);
        assertEquals(3, horizontals);
        for (RecordingRenderer.Call fill : r.fills()) {
            if (fill.x2() - fill.x() == 1) {
                assertEquals(0, fill.y());
                assertEquals(60, fill.y2(), "a vertical is the view's full height");
            }
            else {
                assertEquals(0, fill.x());
                assertEquals(100, fill.x2(), "a horizontal is the view's full width");
            }
        }
    }

    @Test
    @DisplayName("speckle is sparse, deterministic, and inside the view")
    void speckleIsStable() {
        CanvasBackground speckle = new CanvasBackground(CanvasBackground.Kind.SPECKLE,
                CanvasBackground.Space.GRAPH, 16);

        RecordingRenderer first = RecordingRenderer.create();
        CanvasBackgroundArt.draw(first, view(200, 120), speckle, INK);
        RecordingRenderer second = RecordingRenderer.create();
        CanvasBackgroundArt.draw(second, view(200, 120), speckle, INK);

        // 13 x 8 cells, one in five carries a speck: a range, because which cells is the hash's job.
        assertTrue(fills(first) >= 8 && fills(first) <= 40,
                "expected a sparse field, got " + fills(first));
        assertEquals(first.fills(), second.fills(),
                "the same view must draw the same specks, or the field crawls between frames");
        for (RecordingRenderer.Call fill : first.fills()) {
            assertTrue(fill.x() >= 0 && fill.x() < 200 && fill.y() >= 0 && fill.y() < 120,
                    "outside the view: " + fill);
        }
    }

    @Test
    @DisplayName("size thickens every mark it names")
    void sizeThickensMarks() {
        CanvasBackground.Tuning heavy = new CanvasBackground.Tuning(3, 5, false);

        RecordingRenderer dots = RecordingRenderer.create();
        CanvasBackgroundArt.draw(dots, view(100, 60), procedural(CanvasBackground.Kind.DOTS, 24,
                CanvasBackground.Space.SCREEN, heavy), INK);
        assertTrue(fills(dots) > 0, "there was something to measure");
        for (RecordingRenderer.Call fill : dots.fills()) {
            assertEquals(3, fill.x2() - fill.x(), "a dot's edge is size: " + fill);
            assertEquals(3, fill.y2() - fill.y(), "a dot's edge is size: " + fill);
        }

        RecordingRenderer grid = RecordingRenderer.create();
        CanvasBackgroundArt.draw(grid, view(100, 60), procedural(CanvasBackground.Kind.GRID_LINES, 24,
                CanvasBackground.Space.SCREEN, heavy), INK);
        assertTrue(grid.fills().stream().anyMatch(fill -> fill.x2() - fill.x() == 3),
                "a vertical line's thickness is size");
        assertTrue(grid.fills().stream().anyMatch(fill -> fill.y2() - fill.y() == 3),
                "a horizontal line's thickness is size");

        RecordingRenderer speckle = RecordingRenderer.create();
        CanvasBackgroundArt.draw(speckle, view(200, 120), procedural(CanvasBackground.Kind.SPECKLE, 16,
                CanvasBackground.Space.SCREEN, heavy), INK);
        assertTrue(fills(speckle) > 0, "there was something to measure");
        for (RecordingRenderer.Call fill : speckle.fills()) {
            assertEquals(3, fill.x2() - fill.x(), "a speck's edge is size: " + fill);
            assertEquals(3, fill.y2() - fill.y(), "a speck's edge is size: " + fill);
        }
    }

    @Test
    @DisplayName("density decides how many cells carry a speck")
    void densityIsHonoured() {
        RecordingRenderer sparse = RecordingRenderer.create();
        CanvasBackgroundArt.draw(sparse, view(200, 120), procedural(CanvasBackground.Kind.SPECKLE, 16,
                CanvasBackground.Space.SCREEN, new CanvasBackground.Tuning(1, 16, false)), INK);
        RecordingRenderer dense = RecordingRenderer.create();
        CanvasBackgroundArt.draw(dense, view(200, 120), procedural(CanvasBackground.Kind.SPECKLE, 16,
                CanvasBackground.Space.SCREEN, new CanvasBackground.Tuning(1, 2, false)), INK);

        assertTrue(fills(sparse) > 0, "one in sixteen still lands somewhere in 104 cells");
        assertTrue(fills(dense) > fills(sparse) * 3,
                "one in two is many times one in sixteen: " + fills(dense) + " vs " + fills(sparse));
        assertTrue(fills(dense) < 13 * 8, "and never more than one speck per cell");
    }

    @Test
    @DisplayName("hatch steps three blocks along its diagonal, and backslash mirrors the lean")
    void hatchDirection() {
        RecordingRenderer slash = RecordingRenderer.create();
        CanvasBackgroundArt.draw(slash, view(100, 60), procedural(CanvasBackground.Kind.HATCH, 24,
                CanvasBackground.Space.SCREEN, new CanvasBackground.Tuning(1, 5, false)), INK);
        assertTrue(fills(slash) > 0);
        assertEquals(1, slash.fills().get(0).x2() - slash.fills().get(0).x(),
                "a hatch step's thickness is size");
        assertTrue(hasStep(slash.fills(), 2, -2),
                "a slash tick steps up-right: " + slash.fills());
        assertTrue(!hasStep(slash.fills(), 2, 2), "and never down-right");

        RecordingRenderer backslash = RecordingRenderer.create();
        CanvasBackgroundArt.draw(backslash, view(100, 60), procedural(CanvasBackground.Kind.HATCH, 24,
                CanvasBackground.Space.SCREEN, new CanvasBackground.Tuning(1, 5, true)), INK);
        assertTrue(hasStep(backslash.fills(), 2, 2),
                "a backslash tick steps down-right: " + backslash.fills());
        assertTrue(!hasStep(backslash.fills(), 2, -2), "and never up-right");
    }

    @Test
    @DisplayName("a pattern that would overrun the fill budget fades out instead")
    void theBudgetIsRespected() {
        // A 4000x3000 view at six-unit spacing is 333,000 lattice points: an order of magnitude past
        // the budget, so the theme's own 0x33 ink rounds below the floor and nothing is drawn.
        RecordingRenderer r = RecordingRenderer.create();
        CanvasBackgroundArt.draw(r, view(4000, 3000),
                new CanvasBackground(CanvasBackground.Kind.DOTS, CanvasBackground.Space.GRAPH, 6), INK);

        assertEquals(0, fills(r),
                "a budget overrun must cost the pattern, not the frame: the ink fades below the floor"
                        + " rather than emitting a third of a million dots");
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a tiled image repeats at its destination size, phased with the view's corner")
    void imageTileIsSeamless() {
        RecordingRenderer r = RecordingRenderer.create();
        r.putTextureSize(TILED, 24, 16);
        CanvasBackground background = image(TILED, CanvasBackground.Fit.TILE,
                CanvasBackground.Space.GRAPH, 12);

        CanvasBackgroundArt.draw(r, view(100, 60), background, 0x33FF0000);

        List<RecordingRenderer.Call> tiles = r.textures();
        assertEquals(9 * 8, tiles.size(), "ceil(100/12) across by ceil(60/8) down, and nothing else");
        for (RecordingRenderer.Call tile : tiles) {
            assertEquals(12, tile.x2() - tile.x(), "the destination width is the tile size");
            assertEquals(8, tile.y2() - tile.y(), "the destination height keeps the 24:16 aspect");
            assertEquals(0x33FFFFFF, tile.argb(),
                    "the tint's RGB is white: only the ink's alpha comes through");
            assertEquals(TILED.toString(), tile.text(), "the recorded call names the file");
            assertEquals(0F, tile.source().u(), "a tile reads the whole texture");
            assertEquals(0F, tile.source().v());
            assertEquals(24, tile.source().sourceWidth());
            assertEquals(16, tile.source().sourceHeight());
        }

        assertTrue(has(tiles, 0, 0), "phase zero is the visible rectangle's corner");
        assertTrue(has(tiles, 12, 0), "columns abut");
        assertTrue(has(tiles, 0, 8), "rows abut");
        for (RecordingRenderer.Call tile : tiles) {
            if (tile.x() + 12 < 100) {
                assertTrue(has(tiles, tile.x() + 12, tile.y()),
                        "the next column starts where this one ends: " + tile);
            }
            if (tile.y() + 8 < 60) {
                assertTrue(has(tiles, tile.x(), tile.y() + 8),
                        "the next row starts where this one ends: " + tile);
            }
        }
    }

    @Test
    @DisplayName("a tiled image is a wallpaper: pan and zoom do not move it")
    void imageTileIsScreenFixed() {
        RecordingRenderer still = RecordingRenderer.create();
        still.putTextureSize(FIXED, 24, 16);
        RecordingRenderer moved = RecordingRenderer.create();
        moved.putTextureSize(FIXED, 24, 16);
        CanvasBackground background = image(FIXED, CanvasBackground.Fit.TILE,
                CanvasBackground.Space.GRAPH, 12);

        Viewport panned = view(100, 60);
        panned.setOffset(13, 7);
        panned.setScale(2F);

        CanvasBackgroundArt.draw(still, view(100, 60), background, INK);
        CanvasBackgroundArt.draw(moved, panned, background, INK);

        assertEquals(still.textures(), moved.textures(),
                "the image must ignore the viewport entirely, whatever space it was given");
    }

    @Test
    @DisplayName("an image whose texture cannot be read draws nothing, not a guess")
    void unreadableImageDrawsNothing() {
        RecordingRenderer unseeded = RecordingRenderer.create();
        CanvasBackgroundArt.draw(unseeded, view(100, 60),
                image(ResourceLocation.fromNamespaceAndPath("armature", "textures/gui/never_seeded.png"),
                        CanvasBackground.Fit.TILE, CanvasBackground.Space.SCREEN, 16), INK);
        assertEquals(0, unseeded.calls().size(), "a size the renderer cannot give is no image at all");

        RecordingRenderer blank = RecordingRenderer.create();
        CanvasBackgroundArt.draw(blank, view(100, 60),
                image(ResourceLocation.fromNamespaceAndPath("armature", "textures/gui/also_never.png"),
                        CanvasBackground.Fit.COVER, CanvasBackground.Space.SCREEN, 16), INK);
        assertEquals(0, blank.calls().size(), "and a miss draws nothing whichever fit was asked for");

        RecordingRenderer none = RecordingRenderer.create();
        CanvasBackgroundArt.draw(none, view(100, 60), new CanvasBackground(CanvasBackground.Kind.IMAGE,
                CanvasBackground.Space.SCREEN, 24, CanvasBackground.Tuning.DEFAULT,
                CanvasBackground.Image.NONE), INK);
        assertEquals(0, none.calls().size(), "an image with no file is the flat case");
    }

    @Test
    @DisplayName("cover draws one centred, cropped source that fills the view")
    void coverDrawsOneCentredSource() {
        RecordingRenderer r = RecordingRenderer.create();
        r.putTextureSize(COVERED, 64, 32);
        CanvasBackground background = image(COVERED, CanvasBackground.Fit.COVER,
                CanvasBackground.Space.SCREEN, 16);

        CanvasBackgroundArt.draw(r, view(60, 100), background, 0x33FF0000);

        List<RecordingRenderer.Call> drawn = r.textures();
        assertEquals(1, drawn.size(), "cover is one blit, however large the rectangle");
        RecordingRenderer.Call call = drawn.get(0);
        assertEquals(0, call.x());
        assertEquals(0, call.y());
        assertEquals(60, call.x2() - call.x(), "the destination is the whole visible rectangle");
        assertEquals(100, call.y2() - call.y());
        assertEquals(0x33FFFFFF, call.argb(), "the same white-RGB tint as tiling");

        RecordingRenderer.Call.Source source = call.source();
        assertTrue(source.u() >= 0 && source.v() >= 0
                        && source.u() + source.sourceWidth() <= 64
                        && source.v() + source.sourceHeight() <= 32,
                "the source is inside the texture: " + source);
        assertTrue(source.u() > 0, "the horizontal axis is the cropped one here, and it is centred");
        assertEquals(60 / 100F, source.sourceWidth() / (float) source.sourceHeight(), 0.05F,
                "the source carries the destination's aspect: " + source);
        assertEquals(64, source.textureWidth());
        assertEquals(32, source.textureHeight());
    }

    @Test
    @DisplayName("a replaced resource is re-read, and an unchanged one is not")
    void aReplacedResourceIsReRead() {
        // The one thing the size cache has to survive: the file behind an id changing while the id does
        // not. A pack reload replaces the resource, so a wallpaper whose replacement has different
        // dimensions would keep the old numbers — a tiled background at the wrong pitch, a covered one
        // stretched to the wrong aspect, for the rest of the session, with nothing to notice it by.
        //
        // The stamp is how the entry learns that, and the assertion is on the *tiles drawn* rather than on
        // the cache, because the cache is private and what matters is what reaches the screen.
        RecordingRenderer r = RecordingRenderer.create();
        r.putTextureSize(TILED, 24, 16);
        CanvasBackground background = image(TILED, CanvasBackground.Fit.TILE,
                CanvasBackground.Space.GRAPH, 12);

        CanvasBackgroundArt.draw(r, view(100, 60), background, 0x33FF0000);
        int before = r.textures().size();
        assertTrue(before > 0, "the wallpaper drew something to begin with");

        // The same resource: the size is remembered, so the same number of tiles comes back and no header
        // is re-read. This is the half that says the cache is still a cache.
        RecordingRenderer again = RecordingRenderer.create();
        CanvasBackgroundArt.draw(again, view(100, 60), background, 0x33FF0000);
        assertEquals(before, again.textures().size(),
                "an unchanged resource tiles exactly as it did, from the remembered size");

        // The resource is replaced with one of different dimensions. The old size would tile 12 by 8;
        // the new one is 24 by 24, so the tile is square and the count changes.
        r.bumpTextureStamp(TILED);
        r.putTextureSize(TILED, 24, 24);
        CanvasBackgroundArt.draw(r, view(100, 60), background, 0x33FF0000);

        List<RecordingRenderer.Call> after = r.textures();
        RecordingRenderer.Call tile = after.get(after.size() - 1);
        assertEquals(tile.x2() - tile.x(), tile.y2() - tile.y(),
                "the replacement's own aspect is used: a square texture tiles square, not at 24 by 16");
        assertEquals(24, tile.source().sourceWidth(), "and the source is the replacement's width");
        assertEquals(24, tile.source().sourceHeight(), "and its height");
    }
}
