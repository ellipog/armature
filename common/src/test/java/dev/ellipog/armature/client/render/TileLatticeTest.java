package dev.ellipog.armature.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.resources.Identifier;

import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tiled image handed over whole, and the claim the game renderer rests on: that a tile drawn as a quad of a
 * batch is the quad vanilla's {@code blit} would have filed for it.
 *
 * <h2>What is held, and against what</h2>
 *
 * <p>The default against the loop it replaced, call for call. Then the batch's quad against vanilla's own
 * {@code BlitRenderState}, vertex for vertex and bit for bit, with the texture coordinates worked out the way
 * {@code GuiGraphicsExtractor.blit} works them out, copied here as the oracle. And the one assumption under
 * both the tiles and every batched fill: that the batch's pose arithmetic is the arithmetic vanilla's vertex
 * path does, so a corner moved through a turn lands on the same float either way.
 */
@DisplayName("A tile lattice")
class TileLatticeTest {

    private static final Identifier WALLPAPER = Identifier.fromNamespaceAndPath("armature", "textures/gui/wall.png");

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

    @Test
    @DisplayName("a tile in the batch is the quad vanilla's blit files, vertex for vertex")
    void aBatchedTileIsTheBlitsQuad() {
        Random random = new Random(1392);
        for (int trial = 0; trial < 2000; trial++) {
            int x = random.nextInt(2000) - 500;
            int y = random.nextInt(2000) - 500;
            int width = 1 + random.nextInt(300);
            int height = 1 + random.nextInt(300);
            int textureWidth = 1 + random.nextInt(1024);
            int textureHeight = 1 + random.nextInt(1024);
            float u = random.nextBoolean() ? 0F : random.nextInt(textureWidth) + (random.nextBoolean() ? 0.5F : 0F);
            float v = random.nextBoolean() ? 0F : random.nextInt(textureHeight);
            int sourceWidth = 1 + random.nextInt(textureWidth);
            int sourceHeight = 1 + random.nextInt(textureHeight);
            int argb = random.nextInt();

            // The oracle: what blit(RenderPipeline, Identifier, x, y, u, v, width, height, srcWidth, srcHeight,
            // textureWidth, textureHeight, color) hands innerBlit, and the render state innerBlit files. Vanilla's
            // own record, so its buildVertices is the one the game runs. Built through the canonical constructor
            // with no bounds, because the bounds decide a layer and not a vertex.
            BlitRenderState blit = new BlitRenderState(null, null, new Matrix3x2f(),
                    x, y, x + width, y + height,
                    (u + 0.0F) / textureWidth,
                    (u + sourceWidth) / textureWidth,
                    (v + 0.0F) / textureHeight,
                    (v + sourceHeight) / textureHeight,
                    argb, null, null);
            Recorder expected = new Recorder();
            blit.buildVertices(expected);

            // The batch's quad: the corners in queue's order (top left, bottom left, bottom right, top right) at
            // an identity pose, and the coordinates from blitUvs.
            float[] uv = GuiGraphicsRenderer.BatchedTexturedRenderState.blitUvs(u, v, sourceWidth, sourceHeight,
                    textureWidth, textureHeight);
            float[] corners = {x, y, x, y + height, x + width, y + height, x + width, y};
            GuiGraphicsRenderer.BatchedTexturedRenderState batch = new GuiGraphicsRenderer.BatchedTexturedRenderState(
                    corners, uv, new int[] {argb}, 1, null, null, null);
            Recorder actual = new Recorder();
            batch.buildVertices(actual);

            assertEquals(expected.out, actual.out, "trial " + trial);
        }
    }

    @Test
    @DisplayName("the batch's pose arithmetic is the vertex path's, so a turned corner lands on the same float")
    void theBatchPoseIsTheVertexPathsPose() {
        // The assumption under every batched quad, the tiles' included: `push` moves a corner through the pose
        // as m00 * x + m10 * y + m20 (and the same for y), and vanilla's addVertexWith2DPose does it through
        // JOML's transformPosition. Floats are not associative, so the order of the terms decides the bits; this
        // pins that the two orders are the same, and would fail on a JOML that fused or reordered them.
        Random random = new Random(263);
        for (int trial = 0; trial < 5000; trial++) {
            Matrix3x2f pose = new Matrix3x2f()
                    .translate(random.nextFloat() * 400F - 200F, random.nextFloat() * 400F - 200F)
                    .rotate((float) Math.toRadians(random.nextFloat() * 360F))
                    .scale(0.25F + random.nextFloat() * 4F, 0.25F + random.nextFloat() * 4F);
            float x = random.nextInt(3000) - 1000;
            float y = random.nextInt(3000) - 1000;

            Recorder vanilla = new Recorder();
            vanilla.addVertexWith2DPose(pose, x, y);

            Recorder batch = new Recorder();
            batch.addVertex(posedX(pose, x, y), posedY(pose, x, y), 0.0F);

            assertEquals(vanilla.out, batch.out, "trial " + trial + " at " + x + "," + y + " through " + pose);
        }
    }

    /** {@code push}'s x, term for term. */
    private static float posedX(Matrix3x2fc pose, float x, float y) {
        return pose.m00() * x + pose.m10() * y + pose.m20();
    }

    /** {@code push}'s y, term for term. */
    private static float posedY(Matrix3x2fc pose, float x, float y) {
        return pose.m01() * x + pose.m11() * y + pose.m21();
    }

    /** A vertex consumer that writes down what it was given, floats as their bits so nothing is rounded away. */
    private static final class Recorder implements VertexConsumer {

        final List<String> out = new ArrayList<>();

        private static String bits(float value) {
            return value + "/" + Integer.toHexString(Float.floatToRawIntBits(value));
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            out.add("vertex " + bits(x) + " " + bits(y) + " " + bits(z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            out.add("colour " + r + "," + g + "," + b + "," + a);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            out.add("colour " + Integer.toHexString(color));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            out.add("uv " + bits(u) + " " + bits(v));
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            out.add("uv1 " + u + "," + v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            out.add("uv2 " + u + "," + v);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            out.add("uv3 " + bits(u) + " " + bits(v));
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            out.add("normal " + bits(x) + " " + bits(y) + " " + bits(z));
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            out.add("line " + bits(width));
            return this;
        }
    }
}
