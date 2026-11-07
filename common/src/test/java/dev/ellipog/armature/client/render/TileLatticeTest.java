package dev.ellipog.armature.client.render;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tiled image handed over whole.
 *
 * <h2>What is held, and against what</h2>
 *
 * <p>The default against the loop it replaced, call for call. On 1.21.1 the game renderer keeps the default,
 * so this is the whole of what the call does here. (26.3 also holds its batched quad against vanilla's
 * {@code BlitRenderState}; that render state does not exist on this version.)
 */
@DisplayName("A tile lattice")
class TileLatticeTest {

    private static final ResourceLocation WALLPAPER =
            ResourceLocation.fromNamespaceAndPath("armature", "textures/gui/wall.png");

    @Test
    @DisplayName("defaults to one scaled call per tile, rows outer, reading only the counts it is given")
    void defaultsToTheDoubleLoop() {
        // Rows outer and columns inner is the order a tiled background was always drawn in, so a recorder sees
        // the calls it saw before the lattice existed. The arrays run past the counts on purpose.
        int[] xs = {0, 16, 32, 999};
        int[] ys = {4, 20, 999};
        RecordingRenderer r = RecordingRenderer.create();
        r.scaledLattice(WALLPAPER, xs, 3, 16, ys, 2, 12, 1F, 2F, 24, 16, 48, 32, 0x80FFFFFF);

        assertEquals(1, r.scaledLattices());
        List<String> expected = List.of("0,4", "16,4", "32,4", "0,20", "16,20", "32,20");
        assertEquals(expected, r.textures().stream().map(t -> t.x() + "," + t.y()).toList());
        for (RecordingRenderer.Call tile : r.textures()) {
            assertEquals(16, tile.x2() - tile.x(), "the tile's width");
            assertEquals(12, tile.y2() - tile.y(), "the tile's height");
            assertEquals(0x80FFFFFF, tile.argb(), "the tint");
            assertEquals(new RecordingRenderer.Call.Source(1F, 2F, 24, 16, 48, 32), tile.source());
        }
    }

    @Test
    @DisplayName("an empty lattice draws nothing at all")
    void anEmptyLatticeDrawsNothing() {
        RecordingRenderer r = RecordingRenderer.create();
        r.scaledLattice(WALLPAPER, new int[] {0, 16}, 0, 16, new int[] {0}, 1, 16, 0F, 0F, 16, 16, 16, 16, -1);
        r.scaledLattice(WALLPAPER, new int[] {0, 16}, 2, 16, new int[] {0}, 0, 16, 0F, 0F, 16, 16, 16, 16, -1);
        assertTrue(r.calls().isEmpty(), "no tiles, no calls: " + r.calls());
    }
}
