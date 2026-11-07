package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.ui.shape.Plans;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.armature.client.ui.shape.Shapes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packing and the rasterising behind the GPU masks, which are the two halves that fail without throwing.
 *
 * <h2>What is asserted, and why these</h2>
 *
 * <p>A region that overlaps another, gutter included, draws one shape's pixels inside another's. A mask that
 * differs from its plan by one texel draws a node with a notch or a speck. Neither is an exception, and both
 * look like a driver glitch in a screenshot, so both are checked here as numbers: every region against every
 * other over a few thousand random requests, and every mask texel against the plan's own rectangles for every
 * built-in shape at every size a node is drawn at.
 */
@DisplayName("MaskAtlas")
class MaskAtlasTest {

    // ------------------------------------------------------------------
    // Packing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the first region is the corner, which is where the white texel lives")
    void theFirstRegionIsTheCorner() {
        MaskAtlas atlas = new MaskAtlas(64);
        MaskAtlas.Region first = atlas.allocate(1, 1);
        assertEquals(new MaskAtlas.Region(0, 0, 1, 1), first);
    }

    @Test
    @DisplayName("no two regions share a texel, gutters included, and every one is inside the texture")
    void regionsNeverOverlap() {
        Random random = new Random(93);
        MaskAtlas atlas = new MaskAtlas(1024);
        List<int[]> placed = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            int width = 1 + random.nextInt(random.nextBoolean() ? 16 : GpuMasks.MAX_SIDE);
            int height = 1 + random.nextInt(random.nextBoolean() ? 16 : GpuMasks.MAX_SIDE);
            MaskAtlas.Region region = atlas.allocate(width, height);
            if (region == null) {
                continue;
            }
            assertEquals(width, region.width());
            assertEquals(height, region.height());
            // The region and its gutter: one texel right and one below, all inside the texture.
            int[] box = {region.x(), region.y(), region.x() + width + 1, region.y() + height + 1};
            assertTrue(box[0] >= 0 && box[1] >= 0 && box[2] <= atlas.size() && box[3] <= atlas.size(),
                    "region and gutter inside the texture: " + region);
            for (int[] other : placed) {
                boolean apart = box[2] <= other[0] || other[2] <= box[0] || box[3] <= other[1]
                        || other[3] <= box[1];
                assertTrue(apart, "regions overlap: " + region + " and " + java.util.Arrays.toString(other));
            }
            placed.add(box);
        }
        assertTrue(placed.size() > 50, "the test placed enough regions to mean something: " + placed.size());
    }

    @Test
    @DisplayName("same-sized masks pack along one shelf rather than opening one each")
    void sameSizesShareAShelf() {
        MaskAtlas atlas = new MaskAtlas(256);
        MaskAtlas.Region a = atlas.allocate(20, 20);
        MaskAtlas.Region b = atlas.allocate(20, 20);
        MaskAtlas.Region c = atlas.allocate(19, 21);
        assertEquals(0, a.y());
        assertEquals(a.y(), b.y(), "the second mask is beside the first");
        assertEquals(21, b.x(), "one texel of gutter between them");
        assertEquals(a.y(), c.y(), "a near size rounds to the same shelf");
    }

    @Test
    @DisplayName("full is an answer rather than an error, and a reset makes room again")
    void fullThenReset() {
        MaskAtlas atlas = new MaskAtlas(64);
        int placed = 0;
        while (atlas.allocate(30, 30) != null) {
            placed++;
        }
        assertEquals(2 * 2, placed, "a 64-texel atlas holds four 31-texel squares with their gutters");
        atlas.reset();
        assertEquals(new MaskAtlas.Region(0, 0, 30, 30), atlas.allocate(30, 30));
    }

    @Test
    @DisplayName("a mask taller than the tallest shelf, wider than the texture, or empty is refused")
    void outOfRangeIsRefused() {
        MaskAtlas atlas = new MaskAtlas(2048);
        assertNotNull(atlas.allocate(256, 256), "the largest mask fits");
        assertNotNull(atlas.allocate(129, 129), "and the masks past the old 128 limit fit");
        assertNotNull(atlas.allocate(200, 193));
        assertNull(atlas.allocate(10, 257), "taller than the tallest shelf");
        assertNull(new MaskAtlas(64).allocate(64, 4), "no room for the gutter");
        assertNull(atlas.allocate(0, 4));
        assertNull(atlas.allocate(4, 0));
    }

    @Test
    @DisplayName("the tallest shelf holds the biggest mask the renderer asks for, with its gutter")
    void theTallestShelfIsTheLargestMask() {
        // GpuMasks refuses a plan past MAX_SIDE and asks the atlas for anything up to it, so the two have to
        // agree: a shelf too short would refuse a mask the renderer thinks it can draw, and draw it as fills
        // on every frame for no reason anyone could see.
        int tallest = MaskAtlas.SHELF_HEIGHTS[MaskAtlas.SHELF_HEIGHTS.length - 1];
        assertEquals(GpuMasks.MAX_SIDE + 1, tallest, "the tallest shelf is the largest mask and its gutter");
        MaskAtlas atlas = new MaskAtlas(2048);
        for (int side = 1; side <= GpuMasks.MAX_SIDE; side++) {
            assertNotNull(atlas.allocate(side, side), "a " + side + "-texel mask has a shelf");
            atlas.reset();
        }
        for (int i = 1; i < MaskAtlas.SHELF_HEIGHTS.length; i++) {
            assertTrue(MaskAtlas.SHELF_HEIGHTS[i] > MaskAtlas.SHELF_HEIGHTS[i - 1], "the heights ascend");
        }
    }

    @Test
    @DisplayName("a 2048 texture still holds a screenful of the biggest masks")
    void bigMasksPack() {
        MaskAtlas atlas = new MaskAtlas(2048);
        int placed = 0;
        while (atlas.allocate(GpuMasks.MAX_SIDE, GpuMasks.MAX_SIDE) != null) {
            placed++;
        }
        // Seven 257-texel columns across and seven 257-texel shelves down.
        assertEquals(7 * 7, placed);
    }

    // ------------------------------------------------------------------
    // What goes in a mask
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every built-in shape's mask is exactly its plan's pixels, at every node size")
    void masksAreThePlansPixels() {
        List<Shape> shapes = List.of(Shapes.RECT, Shapes.CIRCLE, Shapes.HEXAGON, Shapes.TOME,
                Shapes.DIAMOND, Shapes.STAR, Shapes.OCTAGON, Shapes.PENTAGON, Shapes.GEAR, Shapes.HEART,
                Shapes.ROUNDED, Shapes.rotated(Shapes.STAR, 17));
        for (Shape shape : shapes) {
            for (int size : nodeSizes()) {
                Plans.Plan plan = Plans.of(shape, size);
                int[] bounds = MaskAtlas.bounds(plan);
                if (bounds == null) {
                    assertEquals(0, plan.count(), "only an empty plan has no box");
                    continue;
                }
                byte[] mask = MaskAtlas.coverage(plan, bounds);
                assertNotNull(mask, "a shape's own plan never overlaps itself: " + shape + " at " + size);
                assertMaskIsPlan(plan, bounds, mask);
            }
        }
    }

    @Test
    @DisplayName("a plan whose rectangles share a texel has no mask, so it is drawn as the fills it is")
    void anOverlappingPlanHasNoMask() {
        Plans.Plan overlapping = Plans.merge((row, size) -> new int[] {0, 4, 3, 6}, 4);
        int[] bounds = MaskAtlas.bounds(overlapping);
        assertArrayEquals(new int[] {0, 0, 6, 4}, bounds);
        assertNull(MaskAtlas.coverage(overlapping, bounds));

        Plans.Plan touching = Plans.merge((row, size) -> new int[] {0, 3, 3, 6}, 4);
        assertNotNull(MaskAtlas.coverage(touching, MaskAtlas.bounds(touching)),
                "half-open rectangles that meet do not share a texel");
    }

    @Test
    @DisplayName("an empty plan has no box")
    void anEmptyPlanHasNoBox() {
        assertNull(MaskAtlas.bounds(Plans.merge((row, size) -> null, 8)));
    }

    @Test
    @DisplayName("a strip inks each column's width and nothing between")
    void aStripIsItsColumns() {
        byte[] strip = MaskAtlas.strip(new int[] {10, 14, 20, 99}, 3, 2);
        assertArrayEquals(new byte[] {1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1}, strip,
                "only the first three columns, measured from the first");
        assertArrayEquals(new byte[] {1, 1, 1, 1}, MaskAtlas.strip(new int[] {5, 7}, 2, 2),
                "marks that meet leave no gap");
    }

    @Test
    @DisplayName("a strip whose marks overlap, or whose columns run backwards, is refused")
    void aBadStripIsRefused() {
        assertNull(MaskAtlas.strip(new int[] {0, 1}, 2, 2), "two marks share a texel");
        assertNull(MaskAtlas.strip(new int[] {8, 0}, 2, 1), "out of order");
        assertNull(MaskAtlas.strip(new int[] {0}, 0, 1), "no columns");
        assertNull(MaskAtlas.strip(new int[] {0}, 1, 0), "no width");
    }

    /**
     * Every size up to 128, then a stride up to the largest mask and the largest itself: quick, and still
     * reaching the sizes past the old limit that a node at the closest zoom is drawn at.
     */
    private static int[] nodeSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (int size = 2; size <= 128; size++) {
            sizes.add(size);
        }
        for (int size = 129; size < GpuMasks.MAX_SIDE; size += 7) {
            sizes.add(size);
        }
        sizes.add(GpuMasks.MAX_SIDE);
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Every texel of the box against the plan's rectangles, painted one by one: set exactly where one fills. */
    private static void assertMaskIsPlan(Plans.Plan plan, int[] bounds, byte[] mask) {
        int width = bounds[2] - bounds[0];
        int height = bounds[3] - bounds[1];
        assertEquals(width * height, mask.length);
        int[] painted = new int[width * height];
        for (int i = 0; i < plan.count(); i++) {
            for (int y = Math.min(plan.y1(i), plan.y2(i)); y < Math.max(plan.y1(i), plan.y2(i)); y++) {
                for (int x = Math.min(plan.x1(i), plan.x2(i)); x < Math.max(plan.x1(i), plan.x2(i)); x++) {
                    painted[(y - bounds[1]) * width + (x - bounds[0])]++;
                }
            }
        }
        for (int t = 0; t < painted.length; t++) {
            assertEquals(painted[t] > 0, mask[t] != 0,
                    "texel " + (bounds[0] + t % width) + "," + (bounds[1] + t / width));
        }
    }
}
