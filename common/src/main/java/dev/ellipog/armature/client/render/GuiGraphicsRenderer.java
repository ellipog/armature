package dev.ellipog.armature.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.ellipog.armature.client.TextScale;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.shape.Plans;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 26.3's {@link GuiRenderer}: the one file in either mod that names {@code GuiGraphicsExtractor}, and the
 * one file that has to change when the version does.
 *
 * <h2>What the port cost, now that it is knowable</h2>
 *
 * <p>26.1 renamed the context to {@code GuiGraphicsExtractor} and moved drawing to an extraction
 * phase, where {@code Screen#render} became {@code Screen#extractRenderState}. As predicted, that
 * landed on this file: every method below is a direct one-to-one translation of the 1.21.1 body
 * onto the extractor's calls. There is no invented API to re-derive, and no state this class
 * keeps. The methods are short for that reason, and it is the property the seam is worth
 * having for.
 *
 * <p>Three things did not translate one-to-one, and each says so where it lives: fill batching
 * collapses consecutive rectangles inside {@link #batched} into one {@link GuiElementRenderState}
 * submission (see {@link #batched} for why per-fill extraction is quadratic in {@code GuiRenderState}),
 * the tint travels as a colour argument rather than pipeline state, and the pose is a 2D matrix
 * stack rather than a {@code PoseStack}.
 *
 * <p>A fourth thing has no 1.21.1 counterpart at all: {@link #fillPlan} and {@link #fillLattice} draw a
 * many-rectangle shape as one quad over a mask kept on the GPU ({@link GpuMasks}), in the same batch as
 * the fills around it. See {@link BatchedTexturedRenderState} for why that batch draws the same pixels. The
 * same element carries the tiles of {@link #scaledLattice}, under the image's own texture, so a wallpaper is
 * one element rather than a blit per tile.
 *
 * <h2>Why a record</h2>
 *
 * <p>It holds exactly one thing and has no identity. A record says both, and the constructor being
 * public is what lets a screen's forced override wrap and delegate in one line:
 * {@code new GuiGraphicsRenderer(graphics)}.
 *
 * <h2>The font is resolved per call, and that is correct</h2>
 *
 * <p>{@code Minecraft.getInstance().font} — a static lookup, so a field would be a cached copy of
 * something that can be replaced when a resource pack reloads. It is non-null whenever there is a
 * {@code GuiGraphics} to wrap, because the context only exists while a screen is drawing.
 *
 * <h2>Two things about {@link #icon} that are not obvious</h2>
 *
 * <p>{@code item} draws at a <b>fixed</b> 16 screen pixels, and there is no size parameter. It does,
 * however, inherit the transform: translating to the box's corner and scaling by {@code box / 16}
 * makes the item's own internal scale land exactly on {@code box}. The pose is 2D now, so there is
 * no z to hold at 1 the way the old {@code PoseStack} version had to.
 *
 * <p>And the item draw reads the level for the model, so a null level is a crash rather than a blank
 * icon. One guard here covers every caller.
 */
public record GuiGraphicsRenderer(GuiGraphicsExtractor graphics) implements GuiRenderer {

    private static final MethodHandle GET_RENDER_STATE;
    private static final MethodHandle PEEK_SCISSOR;

    static {
        MethodHandle getRenderState = null;
        MethodHandle peekScissor = null;
        try {
            Field stateField = GuiGraphicsExtractor.class.getDeclaredField("guiRenderState");
            stateField.setAccessible(true);
            getRenderState = MethodHandles.lookup().unreflectGetter(stateField);

            Field scissorField = GuiGraphicsExtractor.class.getDeclaredField("scissorStack");
            scissorField.setAccessible(true);
            Method peekMethod = scissorField.getType().getDeclaredMethod("peek");
            peekMethod.setAccessible(true);
            peekScissor = MethodHandles.filterReturnValue(
                    MethodHandles.lookup().unreflectGetter(scissorField),
                    MethodHandles.lookup().unreflect(peekMethod));
        }
        catch (ReflectiveOperationException ignored) {
            // Falls back to per-fill submission if the extractor's internal layout changes.
        }
        GET_RENDER_STATE = getRenderState;
        PEEK_SCISSOR = peekScissor;
    }

    /**
     * The active batch's extractor and nesting depth on the client thread.
     *
     * <p>Static for the same reason 1.21.1's {@code BATCH_DEPTH} was: a screen holds both a plain
     * {@code GuiGraphicsRenderer} and a {@code CountingRenderer} over the same extractor, and widgets
     * drawn inside a screen's outer {@link #batched} pass construct their own
     * {@code new GuiGraphicsRenderer(graphics)} wrappers. Keying the batch to the extractor on the
     * client thread keeps nested regions and across-widget fills coherent without leaking state
     * across frames.
     */
    private static final BatchNesting<GuiGraphicsExtractor> BATCH = new BatchNesting<>();
    private static float[] batchVertices = new float[1024];
    /** Four texture coordinates per quad, {@code u0, v0, u1, v1}; zero for a fill. Read only when textured. */
    private static float[] batchUvs = new float[512];
    private static int[] batchColours = new int[128];
    private static int batchQuads;
    /** The masks a quad in this run is drawn from, or null while the run is fills only. */
    private static TextureSetup batchMasks;
    /**
     * A segment quad's corners from the segment's start, {@code x, y} for each of four; unset for a fill. Read
     * only when the run has lines.
     */
    private static float[] batchLocal = new float[1024];
    /** A quad's segment run, {@code dx} low and {@code dy} high, as two shorts; zero for a fill. */
    private static int[] batchRuns = new int[128];
    /** Whether the open run holds a line, so it flushes through {@link GpuLines#PIPELINE}. */
    private static boolean batchLines;
    private static float batchMinX;
    private static float batchMinY;
    private static float batchMaxX;
    private static float batchMaxY;

    public GuiGraphicsRenderer {
        Objects.requireNonNull(graphics, "graphics");
    }

    /** The font this client is drawing with. Non-null whenever a graphics context exists. */
    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static GuiRenderState renderStateOf(GuiGraphicsExtractor extractor) {
        if (GET_RENDER_STATE == null) {
            return null;
        }
        try {
            return (GuiRenderState) GET_RENDER_STATE.invokeExact(extractor);
        }
        catch (Throwable unexpected) {
            return null;
        }
    }

    private static ScreenRectangle currentScissor(GuiGraphicsExtractor extractor) {
        if (PEEK_SCISSOR == null) {
            return null;
        }
        try {
            return (ScreenRectangle) PEEK_SCISSOR.invokeExact(extractor);
        }
        catch (Throwable unexpected) {
            return null;
        }
    }

    /** The arrays a flushed run is copied into, lent to the frame that draws it. See {@link BatchArrays}. */
    private static final BatchArrays LENT = new BatchArrays();

    private static void flushBatch(GuiGraphicsExtractor extractor) {
        if (batchQuads == 0 || BATCH.owner() != extractor) {
            return;
        }
        int count = batchQuads;
        TextureSetup masks = batchMasks;
        boolean lines = batchLines;
        int minX = Mth.floor(batchMinX);
        int minY = Mth.floor(batchMinY);
        int maxX = Mth.ceil(batchMaxX);
        int maxY = Mth.ceil(batchMaxY);
        batchQuads = 0;
        batchMasks = null;
        batchLines = false;

        GuiRenderState renderState = renderStateOf(extractor);
        if (renderState == null) {
            return;
        }
        ScreenRectangle scissor = currentScissor(extractor);
        ScreenRectangle rawBounds = new ScreenRectangle(minX, minY, maxX - minX, maxY - minY);
        ScreenRectangle bounds = scissor != null ? scissor.intersection(rawBounds) : rawBounds;
        if (bounds == null) {
            return;
        }
        // Copied once the run is known to be filed: into arrays lent until its frame has been drawn when the
        // frame can be told apart, and into fresh ones, as it always was, when it cannot. There is a copy at
        // all because the next run refills the live batch arrays long before this one is read.
        long frame = GpuMasks.frame(renderState);
        BatchArrays lender = frame >= 0 ? LENT.forFrame(frame) : null;
        float[] vertices = BatchArrays.copy(lender, batchVertices, count * 8);
        int[] colours = BatchArrays.copy(lender, batchColours, count);
        float[] uvs = masks != null ? BatchArrays.copy(lender, batchUvs, count * 4) : null;
        float[] local = lines ? BatchArrays.copy(lender, batchLocal, count * 8) : null;
        int[] runs = lines ? BatchArrays.copy(lender, batchRuns, count) : null;
        if (lines) {
            renderState.addGuiElement(
                    new BatchedLinesRenderState(vertices, local, runs, colours, count, scissor, bounds));
        }
        else if (masks != null) {
            renderState.addGuiElement(
                    new BatchedTexturedRenderState(vertices, uvs, colours, count, masks, scissor, bounds));
        }
        else {
            renderState.addGuiElement(new BatchedFillsRenderState(vertices, colours, count, scissor, bounds));
        }
    }

    /**
     * One {@link GuiElementRenderState} for a run of consecutive {@link #fill} calls inside
     * {@link #batched}.
     *
     * <p>Every call to {@code GuiGraphicsExtractor#fill} otherwise allocates a pose copy, two
     * {@code ScreenRectangle}s, four {@code Vector2f}s for bounds, and a
     * {@code ColoredRectangleRenderState}, then walks {@code GuiRenderState}'s stratum node chain
     * checking {@code ScreenRectangle#intersects} against every previously submitted element --
     * quadratic on a canvas with ten thousand curve and conduit fills. Pre-transforming the four
     * corners by the 2D pose when each rectangle is queued and emitting them in call order inside
     * one element keeps the exact painter's order, respects {@link #turned}, and pays one node
     * placement for the whole run.
     */
    private record BatchedFillsRenderState(
            float[] vertices,
            int[] colours,
            int count,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds) implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer consumer) {
            float[] verts = this.vertices;
            int[] cols = this.colours;
            int n = this.count;
            for (int i = 0, v = 0; i < n; i++, v += 8) {
                int argb = cols[i];
                consumer.addVertex(verts[v], verts[v + 1], 0.0F).setColor(argb);
                consumer.addVertex(verts[v + 2], verts[v + 3], 0.0F).setColor(argb);
                consumer.addVertex(verts[v + 4], verts[v + 5], 0.0F).setColor(argb);
                consumer.addVertex(verts[v + 6], verts[v + 7], 0.0F).setColor(argb);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }

    /**
     * A run of fills and masked shapes, drawn as one textured element.
     *
     * <h2>Why a fill can sit in this batch and draw what it always drew</h2>
     *
     * <p>Read from the two shaders rather than assumed: the fill pipeline draws {@code vertexColor} and
     * discards it at zero alpha, and the textured one draws {@code texture * vertexColor} and discards the
     * product at zero alpha, both blended the same way. A fill here samples the opaque white texel the masks
     * keep at their corner, and one times a colour is that colour, so the fill's pixels do not change. A
     * masked shape samples its own mask, white where the plan fills and clear where it does not, which is the
     * plan's pixels in the plan's colour. One element, in call order, is what keeps the painter's order
     * between the two exactly as it was when everything was fills.
     *
     * <h2>The tiles of an image, which are a run of their own</h2>
     *
     * <p>{@link GuiGraphicsRenderer#scaledLattice} files its tiles as one of these under the image's own texture
     * rather than the masks, and nothing else joins that run: a fill needs the white texel, which an image does
     * not have. Each tile is the quad vanilla's {@code blit} would have filed for it: the same corners through
     * the same pose (the pose arithmetic in {@code push} is JOML's {@code transformPosition}, term for term),
     * the coordinates {@link #blitUvs} works out the way the blit does, and the tint as the colour, through the
     * same pipeline and the texture's own view and sampler. So the tiles draw the pixels they drew as blits.
     *
     * <p>Package-private, with {@link #blitUvs}, so a test can hold a tile against vanilla's own render state.
     */
    record BatchedTexturedRenderState(
            float[] vertices,
            float[] uvs,
            int[] colours,
            int count,
            TextureSetup textureSetup,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds) implements GuiElementRenderState {

        /**
         * The texture coordinates vanilla's {@code blit} gives a source region, in the order a quad here carries
         * them: {@code u0, v0, u1, v1}. Copied from {@code GuiGraphicsExtractor.blit}, operation for operation,
         * because a float divided in another order can land on another texel.
         */
        static float[] blitUvs(float u, float v, int sourceWidth, int sourceHeight,
                               int textureWidth, int textureHeight) {
            return new float[] {
                    (u + 0.0F) / textureWidth,
                    (v + 0.0F) / textureHeight,
                    (u + sourceWidth) / textureWidth,
                    (v + sourceHeight) / textureHeight};
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            float[] verts = this.vertices;
            float[] tex = this.uvs;
            int[] cols = this.colours;
            int n = this.count;
            for (int i = 0, v = 0, t = 0; i < n; i++, v += 8, t += 4) {
                int argb = cols[i];
                float u0 = tex[t];
                float v0 = tex[t + 1];
                float u1 = tex[t + 2];
                float v1 = tex[t + 3];
                consumer.addVertex(verts[v], verts[v + 1], 0.0F).setUv(u0, v0).setColor(argb);
                consumer.addVertex(verts[v + 2], verts[v + 3], 0.0F).setUv(u0, v1).setColor(argb);
                consumer.addVertex(verts[v + 4], verts[v + 5], 0.0F).setUv(u1, v1).setColor(argb);
                consumer.addVertex(verts[v + 6], verts[v + 7], 0.0F).setUv(u1, v0).setColor(argb);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }
    }

    /**
     * A run of fills and one-pixel line segments, drawn as one element through {@link GpuLines#PIPELINE}.
     *
     * <p>A fill carries a zero run and draws its colour, as it would through the game's GUI pipeline. A segment
     * is a band around its pixels with each corner's distance from the segment's start, and the shader keeps
     * the pixels {@link Hairlines} names and discards the rest. One element in call order keeps the painter's
     * order between the two.
     */
    private record BatchedLinesRenderState(
            float[] vertices,
            float[] local,
            int[] runs,
            int[] colours,
            int count,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds) implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer consumer) {
            float[] verts = this.vertices;
            float[] at = this.local;
            int[] run = this.runs;
            int[] cols = this.colours;
            int n = this.count;
            for (int i = 0, v = 0; i < n; i++, v += 8) {
                int argb = cols[i];
                int packed = run[i];
                int dx = (short) packed;
                int dy = packed >> 16;
                for (int corner = 0; corner < 8; corner += 2) {
                    consumer.addVertex(verts[v + corner], verts[v + corner + 1], 0.0F).setColor(argb)
                            .setUv(packed == 0 ? 0F : at[v + corner], packed == 0 ? 0F : at[v + corner + 1])
                            .setUv2(dx, dy);
                }
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return GpuLines.PIPELINE;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }

    /**
     * The arrays flushed runs are copied into, lent to the frame that draws them and taken back when the next
     * frame starts.
     *
     * <h2>Why</h2>
     *
     * <p>A run is read after extraction, when the game builds the frame's vertices, so a flush has to copy it out
     * of the live batch arrays, which the next run starts refilling straight away. Copied into fresh arrays of
     * the exact size, that was 36 to 72 bytes of garbage a quad, and a big chapter flushes tens of thousands of
     * quads a frame: a megabyte or so, every frame, for the collector to find.
     *
     * <h2>When an array may be lent again, which is the one thing that has to be right</h2>
     *
     * <p>A render state reads its arrays until the game has drawn it, so an array can only be lent again once
     * the frame it went to is over. Lent sooner, an element filed earlier draws another run's geometry, and
     * nothing says so. {@link GpuMasks#frame} is the sign: it moves when the game's GUI state has a new first
     * layer, which happens when the game resets the state at the end of {@code GuiRenderer.render}, after every
     * element's vertices have been built. So everything lent under one frame number is free once the number
     * moves, and nothing is lent at all when the frame cannot be told or the state is not the game's own.
     *
     * <h2>Size classes, and what is kept</h2>
     *
     * <p>An array's length is a power of two, at least {@code 1 << MIN_CLASS}, and a run takes an array of
     * its own size class and never a bigger one: a frame of many small runs cannot pin big arrays, and a big
     * run is not served from small ones. Within a class the arrays are lent in turn, and at the start of a
     * frame each class keeps at most {@link #retained} of them, fewer the bigger they are, so one busy frame is
     * not held for the session. A run too long for the biggest class is copied as it always was.
     *
     * <p>Nothing reads past {@code count} quads (each render state loops to it), so the tail of a lent array,
     * whatever an earlier run left there, is never drawn. Game-free, so a test can hold the lending.
     */
    static final class BatchArrays {

        /** The smallest class: sixteen elements, two quads' corners. */
        static final int MIN_CLASS = 4;

        /** The biggest class lent: about a million elements, four megabytes of floats. */
        static final int MAX_CLASS = 20;

        /** About how many elements one class keeps from one frame to the next, a megabyte of floats. */
        private static final int RETAINED_ELEMENTS = 1 << 18;

        private final List<List<float[]>> floats = new ArrayList<>();
        private final List<List<int[]>> ints = new ArrayList<>();
        private final int[] floatsLent = new int[MAX_CLASS + 1];
        private final int[] intsLent = new int[MAX_CLASS + 1];
        private long frame = Long.MIN_VALUE;

        BatchArrays() {
            for (int c = 0; c <= MAX_CLASS; c++) {
                floats.add(new ArrayList<>());
                ints.add(new ArrayList<>());
            }
        }

        /**
         * This, lending for {@code frame}. On a new frame everything lent before comes back, and each class is
         * trimmed to what it keeps.
         */
        BatchArrays forFrame(long frame) {
            if (frame != this.frame) {
                this.frame = frame;
                for (int c = MIN_CLASS; c <= MAX_CLASS; c++) {
                    trim(floats.get(c), retained(c));
                    trim(ints.get(c), retained(c));
                    floatsLent[c] = 0;
                    intsLent[c] = 0;
                }
            }
            return this;
        }

        /** The first {@code length} elements of {@code source}, in an array {@code lender} lends, or a fresh one. */
        static float[] copy(BatchArrays lender, float[] source, int length) {
            return lender != null ? lender.lend(source, length) : Arrays.copyOf(source, length);
        }

        /** The same, for whole numbers. */
        static int[] copy(BatchArrays lender, int[] source, int length) {
            return lender != null ? lender.lend(source, length) : Arrays.copyOf(source, length);
        }

        float[] lend(float[] source, int length) {
            int c = sizeClass(length);
            if (c > MAX_CLASS) {
                return Arrays.copyOf(source, length);
            }
            List<float[]> shelf = floats.get(c);
            int lent = floatsLent[c];
            float[] out;
            if (lent < shelf.size()) {
                out = shelf.get(lent);
            }
            else {
                out = new float[1 << c];
                shelf.add(out);
            }
            floatsLent[c] = lent + 1;
            System.arraycopy(source, 0, out, 0, length);
            return out;
        }

        int[] lend(int[] source, int length) {
            int c = sizeClass(length);
            if (c > MAX_CLASS) {
                return Arrays.copyOf(source, length);
            }
            List<int[]> shelf = ints.get(c);
            int lent = intsLent[c];
            int[] out;
            if (lent < shelf.size()) {
                out = shelf.get(lent);
            }
            else {
                out = new int[1 << c];
                shelf.add(out);
            }
            intsLent[c] = lent + 1;
            System.arraycopy(source, 0, out, 0, length);
            return out;
        }

        /** The class an array of {@code length} elements comes from: the power of two at or above it. */
        static int sizeClass(int length) {
            return Math.max(MIN_CLASS, 32 - Integer.numberOfLeadingZeros(length - 1));
        }

        /** How many arrays of a class are kept from one frame to the next: a megabyte's worth, two to 256. */
        static int retained(int sizeClass) {
            return Math.max(2, Math.min(256, RETAINED_ELEMENTS >> sizeClass));
        }

        /** How many float arrays of a class are held, lent or not. For the tests. */
        int heldFloats(int sizeClass) {
            return floats.get(sizeClass).size();
        }

        private static <T> void trim(List<T> shelf, int keep) {
            if (shelf.size() > keep) {
                shelf.subList(keep, shelf.size()).clear();
            }
        }
    }

    // ------------------------------------------------------------------
    // Rectangles
    // ------------------------------------------------------------------

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        if (BATCH.batching(graphics)) {
            if (left == right || top == bottom) {
                return;
            }
            queue(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom),
                    argb, 0F, 0F, 0F, 0F);
            return;
        }
        graphics.fill(left, top, right, bottom, argb);
    }

    /**
     * {@inheritDoc}
     *
     * <p>One quad over the plan's mask when {@link GpuMasks} has one for it, and the default's fills when it
     * does not. Outside a {@link #batched} region the plan is drawn as a region of its own, so a lone shape
     * is one element rather than one per rectangle.
     */
    @Override
    public void fillPlan(Plans.Plan plan, int x, int y, int argb) {
        if (plan.count() < GpuMasks.MIN_PLAN_RECTS) {
            GuiRenderer.super.fillPlan(plan, x, y, argb);
            return;
        }
        if (!BATCH.batching(graphics)) {
            if (GET_RENDER_STATE == null || PEEK_SCISSOR == null) {
                GuiRenderer.super.fillPlan(plan, x, y, argb);
                return;
            }
            batched(() -> {
                fillPlan(plan, x, y, argb);
                return null;
            });
            return;
        }
        GpuMasks masks = GpuMasks.forFrame();
        GpuMasks.Placed placed = null;
        if (masks != null) {
            try {
                placed = masks.plan(plan);
            }
            catch (Throwable failure) {
                GpuMasks.disable(failure);
            }
        }
        if (placed == null) {
            GuiRenderer.super.fillPlan(plan, x, y, argb);
            return;
        }
        drawingFrom(masks);
        queue(x + placed.left(), y + placed.top(), x + placed.right(), y + placed.bottom(), argb,
                placed.u0(), placed.v0(), placed.u1(), placed.v1());
    }

    /**
     * {@inheritDoc}
     *
     * <p>One quad per row and strip piece over a one-row mask of the columns when {@link GpuMasks} has one,
     * and the default's fills when it does not. The rows are drawn top to bottom rather than in the default's
     * column order, which changes no pixel: marks on a row cannot overlap (the strip refuses them), and two
     * marks of one colour over the same pixel blend to the same result in either order.
     */
    @Override
    public void fillLattice(int[] xs, int columnCount, int width, int[] ys, int rowCount, int height, int argb) {
        if (columnCount < GpuMasks.MIN_LATTICE_COLUMNS || rowCount <= 0 || width <= 0 || height <= 0) {
            GuiRenderer.super.fillLattice(xs, columnCount, width, ys, rowCount, height, argb);
            return;
        }
        if (!BATCH.batching(graphics)) {
            if (GET_RENDER_STATE == null || PEEK_SCISSOR == null) {
                GuiRenderer.super.fillLattice(xs, columnCount, width, ys, rowCount, height, argb);
                return;
            }
            batched(() -> {
                fillLattice(xs, columnCount, width, ys, rowCount, height, argb);
                return null;
            });
            return;
        }
        GpuMasks masks = GpuMasks.forFrame();
        GpuMasks.Segment[] strip = null;
        if (masks != null) {
            try {
                strip = masks.strip(xs, columnCount, width);
            }
            catch (Throwable failure) {
                GpuMasks.disable(failure);
            }
        }
        if (strip == null) {
            GuiRenderer.super.fillLattice(xs, columnCount, width, ys, rowCount, height, argb);
            return;
        }
        drawingFrom(masks);
        int origin = xs[0];
        for (int j = 0; j < rowCount; j++) {
            int top = ys[j];
            for (GpuMasks.Segment piece : strip) {
                int left = origin + piece.offset();
                queue(left, top, left + piece.length(), top + height, argb,
                        piece.u0(), piece.v0(), piece.u1(), piece.v1());
            }
        }
    }

    /**
     * Marks the open run as drawing from these masks, so it flushes as a textured element. The fills already
     * queued need nothing: their texture coordinates are zero, which is the white texel.
     */
    private void drawingFrom(GpuMasks masks) {
        TextureSetup setup = masks.textureSetup();
        if (batchLines || (batchMasks != null && batchMasks != setup)) {
            flushBatch(graphics);
        }
        batchMasks = setup;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A segment along neither axis is one quad over its pixels, and {@link GpuLines}' shader keeps the
     * pixels of {@link Hairlines}' walk, so a long diagonal is one quad rather than one fill per run of pixels.
     * A segment along an axis is one fill. Without the pipeline, and for a segment too long for the shader to
     * round as the CPU does, a segment is the default's fills.
     */
    @Override
    public void hairline(int x, int y, int[] route, int argb) {
        int points = route.length / 2;
        if (points < 2) {
            return;
        }
        if (!BATCH.batching(graphics)) {
            if (GET_RENDER_STATE == null || PEEK_SCISSOR == null) {
                GuiRenderer.super.hairline(x, y, route, argb);
                return;
            }
            batched(() -> {
                hairline(x, y, route, argb);
                return null;
            });
            return;
        }
        if (!GpuLines.ready()) {
            GuiRenderer.super.hairline(x, y, route, argb);
            return;
        }
        for (int k = 0; k + 1 < points; k++) {
            int sx = x + route[2 * k];
            int sy = y + route[2 * k + 1];
            int dx = route[2 * k + 2] - route[2 * k];
            int dy = route[2 * k + 3] - route[2 * k + 1];
            int first = k == 0 ? 0 : 1;
            if (dx == 0 || dy == 0 || Hairlines.steps(dx, dy) >= Hairlines.EXACT_STEPS) {
                Hairlines.segment(this, sx, sy, dx, dy, first, argb);
            }
            else {
                queueSegment(sx, sy, dx, dy, first, argb);
            }
        }
    }

    /** Where {@link #queueSegment} works out a band's corners. Render thread only, like the batch. */
    private static final float[] BAND = new float[8];

    /**
     * Queues one segment as {@link Hairlines#band}'s quad over its pixels, for the shader to trim to them. A
     * run drawing from masks is flushed first: its texture is not this pipeline's.
     */
    private void queueSegment(int sx, int sy, int dx, int dy, int first, int argb) {
        if (batchMasks != null) {
            flushBatch(graphics);
        }
        float[] band = BAND;
        Hairlines.band(dx, dy, first, band);
        int quadIndex = push(sx + band[0], sy + band[1], sx + band[2], sy + band[3],
                sx + band[4], sy + band[5], sx + band[6], sy + band[7], argb);
        System.arraycopy(band, 0, batchLocal, quadIndex * 8, 8);
        batchRuns[quadIndex] = (dx & 0xFFFF) | (dy << 16);
        batchLines = true;
    }

    /**
     * Queues one quad of the open run: the rectangle's corners through the pose, its texture coordinates, and
     * its colour. The rectangle is already ordered, left above right and top above bottom.
     */
    private void queue(int x0, int y0, int x1, int y1, int argb, float u0, float v0, float u1, float v1) {
        int quadIndex = push(x0, y0, x0, y1, x1, y1, x1, y0, argb);
        int uv = quadIndex * 4;
        batchUvs[uv] = u0;
        batchUvs[uv + 1] = v0;
        batchUvs[uv + 2] = u1;
        batchUvs[uv + 3] = v1;
        batchRuns[quadIndex] = 0;
    }

    /**
     * Adds one quad to the open run, its four corners in GUI pixels and in a fill's order (top left, bottom
     * left, bottom right, top right), and returns its index. The corners go through the pose and widen the
     * run's bounds; what else the quad carries is the caller's to write.
     */
    private int push(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        Matrix3x2fc pose = graphics.pose();
        float m00 = pose.m00();
        float m01 = pose.m01();
        float m10 = pose.m10();
        float m11 = pose.m11();
        float m20 = pose.m20();
        float m21 = pose.m21();

        float vx0 = m00 * x0 + m10 * y0 + m20;
        float vy0 = m01 * x0 + m11 * y0 + m21;
        float vx1 = m00 * x1 + m10 * y1 + m20;
        float vy1 = m01 * x1 + m11 * y1 + m21;
        float vx2 = m00 * x2 + m10 * y2 + m20;
        float vy2 = m01 * x2 + m11 * y2 + m21;
        float vx3 = m00 * x3 + m10 * y3 + m20;
        float vy3 = m01 * x3 + m11 * y3 + m21;

        float quadMinX = Math.min(Math.min(vx0, vx1), Math.min(vx2, vx3));
        float quadMaxX = Math.max(Math.max(vx0, vx1), Math.max(vx2, vx3));
        float quadMinY = Math.min(Math.min(vy0, vy1), Math.min(vy2, vy3));
        float quadMaxY = Math.max(Math.max(vy0, vy1), Math.max(vy2, vy3));

        int quadIndex = batchQuads;
        if (quadIndex == 0) {
            batchMinX = quadMinX;
            batchMaxX = quadMaxX;
            batchMinY = quadMinY;
            batchMaxY = quadMaxY;
        }
        else {
            if (quadMinX < batchMinX) {
                batchMinX = quadMinX;
            }
            if (quadMaxX > batchMaxX) {
                batchMaxX = quadMaxX;
            }
            if (quadMinY < batchMinY) {
                batchMinY = quadMinY;
            }
            if (quadMaxY > batchMaxY) {
                batchMaxY = quadMaxY;
            }
        }

        if (quadIndex == batchColours.length) {
            batchColours = Arrays.copyOf(batchColours, batchColours.length * 2);
            batchVertices = Arrays.copyOf(batchVertices, batchVertices.length * 2);
            batchUvs = Arrays.copyOf(batchUvs, batchUvs.length * 2);
            batchLocal = Arrays.copyOf(batchLocal, batchLocal.length * 2);
            batchRuns = Arrays.copyOf(batchRuns, batchRuns.length * 2);
        }
        int base = quadIndex * 8;
        batchVertices[base] = vx0;
        batchVertices[base + 1] = vy0;
        batchVertices[base + 2] = vx1;
        batchVertices[base + 3] = vy1;
        batchVertices[base + 4] = vx2;
        batchVertices[base + 5] = vy2;
        batchVertices[base + 6] = vx3;
        batchVertices[base + 7] = vy3;
        batchColours[quadIndex] = argb;
        batchQuads = quadIndex + 1;
        return quadIndex;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Drains any fills queued inside an active {@link #batched} region so the caller's layering
     * boundary is honoured before whatever draw follows. Outside a batched region this is a no-op.
     */
    @Override
    public void flush() {
        boundary();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Accumulates consecutive {@link #fill} calls inside the supplier into a single
     * {@link BatchedFillsRenderState} per uninterrupted fill run, flushing automatically before any
     * non-fill draw ({@link #text}, {@link #icon}, {@link #texture}, {@link #sprite}, {@link #blur}),
     * any {@link #clip} push or pop, an explicit {@link #flush}, or when the outermost batched region
     * exits.
     *
     * <p>Nested {@code batched} calls deepen {@link #BATCH} so inner regions (such as a widget
     * or the canvas inside the screen's frame-wide region) join the outer batch without premature
     * submission, while non-fill boundaries still split runs in exact call order.
     */
    @Override
    public <T> T batched(java.util.function.Supplier<T> draw) {
        if (GET_RENDER_STATE == null || PEEK_SCISSOR == null) {
            return draw.get();
        }
        // A region on another extractor inside an open one is batched on its own and hands the outer
        // region back on the way out -- see BatchNesting for the bug that rule closed.
        BatchNesting.Entry<GuiGraphicsExtractor> entry =
                BATCH.enter(graphics, GuiGraphicsRenderer::flushBatch);
        if (entry.fresh()) {
            batchQuads = 0;
            batchMasks = null;
            batchLines = false;
        }
        try {
            return draw.get();
        }
        finally {
            BATCH.exit(entry, GuiGraphicsRenderer::flushBatch);
        }
    }

    // ------------------------------------------------------------------
    // Held pictures
    // ------------------------------------------------------------------

    /**
     * One picture an {@link #iconsLast} region asked for, kept until the region ends: what to draw, where,
     * and the pose it was asked under, so a picture asked for inside a {@link #turned} scope still draws
     * turned after the scope has closed.
     */
    private record Held(int kind, ItemStack stack, Identifier id, int x, int y, int width, int height,
                        int argb, Matrix3x2f pose) {
        static final int ITEM = 0;
        static final int TEXTURE = 1;
        static final int SPRITE = 2;
    }

    /** The held pictures, in call order. Static and keyed to one extractor, for the reason {@link #BATCH} is. */
    private static final List<Held> HELD = new ArrayList<>();
    private static GuiGraphicsExtractor heldOwner;
    private static int heldDepth;

    /**
     * {@inheritDoc}
     *
     * <p>Held per extractor: a region on another extractor inside an open one simply draws in call order, so
     * one region never swallows the pictures of another. A nested region on the same extractor joins the
     * outer one, and only the outermost end draws what was held.
     */
    @Override
    public void iconsLast(Runnable draw) {
        if (heldDepth > 0 && heldOwner != graphics) {
            draw.run();
            return;
        }
        heldOwner = graphics;
        heldDepth++;
        try {
            draw.run();
        }
        finally {
            if (heldDepth == 1) {
                try {
                    boundary();
                }
                finally {
                    HELD.clear();
                    heldOwner = null;
                    heldDepth = 0;
                }
            }
            else {
                heldDepth--;
            }
        }
    }

    private boolean holding() {
        return heldDepth > 0 && heldOwner == graphics;
    }

    /**
     * Submits the queued rectangles and then any held pictures, so whatever draws next lands over both. Every
     * draw that is not a rectangle or a held picture starts here, which is what keeps text, a clip or a blur
     * inside an {@link #iconsLast} region in its call order.
     */
    private void boundary() {
        flushBatch(graphics);
        if (!holding() || HELD.isEmpty()) {
            return;
        }
        for (Held held : HELD) {
            graphics.pose().pushMatrix();
            graphics.pose().set(held.pose());
            switch (held.kind()) {
                case Held.ITEM -> drawItem(held.stack(), held.x(), held.y(), held.width());
                case Held.TEXTURE -> drawTexture(held.id(), held.x(), held.y(), held.width(), held.height());
                default -> drawSprite(held.id(), held.x(), held.y(), held.width(), held.height(), held.argb());
            }
            graphics.pose().popMatrix();
        }
        HELD.clear();
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    @Override
    public void text(String text, int x, int y, int argb) {
        drawAt(text, x, y, argb, false, 1F);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The only differences from {@link #text} are the flag this class has always passed as false and the
     * size — so all three go through one private method rather than two copies of the pose arithmetic. A
     * shadow changes neither where a line is drawn nor how a scale is applied, and two bodies would be two
     * places for that to stop being true.
     */
    @Override
    public void shadowedText(String text, int x, int y, int argb, float scale) {
        drawAt(text, x, y, argb, true, scale);
    }

    /**
     * One line, at the player's text scale times the caller's, with or without the font's own shadow.
     *
     * <h2>Why the flag is false for everything but {@link #shadowedText}</h2>
     *
     * <p>A drop shadow is what makes vanilla's text legible against vanilla's background, and this toolkit
     * draws an opaque backdrop behind its labels instead. Both at once reads as a smudged label — which is why
     * the flag lives here, decided by the callers that know what kind of surface they are drawing on, rather
     * than being offered to every caller as a parameter.
     *
     * <p>The two factors multiply rather than one winning, and the order is the caller's size first: a heading
     * on a client whose player has turned text up is bigger by both, which is what each of them asked for.
     */
    private void drawAt(String text, int x, int y, int argb, boolean shadow, float scale) {
        boundary();
        double effective = scale * TextScale.get();
        if (effective == TextScale.DEFAULT) {
            graphics.text(font(), text, x, y, argb, shadow);
            return;
        }
        // Scaled about the string's own top-left, so the text grows right and down from where the
        // layout put it rather than from the panel's corner. `textWidth`/`lineHeight` report the same
        // factor, which is what keeps a layout that measured the string and the string it draws in step.
        // The pose is 2D now (a Matrix3x2fStack, no z): translate, scale, draw at the origin, pop.
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale((float) effective, (float) effective);
        graphics.text(font(), text, 0, 0, argb, shadow);
        graphics.pose().popMatrix();
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
        boundary();
        int at = x;
        float base = (float) TextScale.get();
        Iterator<StyledRun> each = runs.iterator();
        while (each.hasNext()) {
            StyledRun run = each.next();
            float effective = run.scale() * base;
            if (effective == 1F) {
                drawRun(run, at, y, argb);
            }
            else {
                // Bigger is the pose, scaled about the run's own top-left: the font has one size, and this
                // keeps the run's top on the line's top while it grows downward into the taller line the
                // caller reserved for it. The player's text scale multiplies the run's own, so a heading
                // run and a body run grow together.
                graphics.pose().pushMatrix();
                graphics.pose().translate(at, y);
                graphics.pose().scale(effective, effective);
                drawRun(run, 0, 0, argb);
                graphics.pose().popMatrix();
            }
            // `styledWidth` already carries the player's factor, so the advance and the drawn glyphs
            // cannot disagree. The last run's advance would move a cursor nothing reads, so it is not
            // measured.
            if (each.hasNext()) {
                at += styledWidth(run.text(), run.bold(), run.italic(), run.scale());
            }
        }
    }

    /** One run, with the styles the font answers to. A literal component, so no code is interpreted. */
    private void drawRun(StyledRun run, int x, int y, int argb) {
        net.minecraft.network.chat.MutableComponent text = Component.literal(run.text());
        if (run.bold() || run.italic() || run.underline()) {
            text = text.withStyle(styleOf(run));
        }
        graphics.text(font(), text, x, y, argb, false);
    }

    @Override
    public int styledWidth(String text, boolean bold, boolean italic, float scale) {
        // The font's own width is remembered and the scale applied after, exactly as it was applied to the
        // width measured fresh: the run's scale is a float a heading can set to anything, and keeping it out
        // of the key keeps one entry per string and style rather than one per size it is drawn at.
        int plain = STYLED_WIDTHS.width(text, bold, italic);
        return (int) Math.round(plain * scale * TextScale.get());
    }

    /**
     * The font's own width of a run in one of its four styles, unscaled, as {@link #styledWidth} measured it
     * before it was remembered: through the same style it will be drawn with, because the game's bold font is
     * one pixel wider per glyph, and measuring plain and drawing bold is a line that runs past its column.
     * Underline is not wider, so it does not enter the measure.
     */
    private static int fontStyledWidth(String text, boolean bold, boolean italic) {
        if (!bold && !italic) {
            return font().width(text);
        }
        return font().width(Component.literal(text).withStyle(styleOf(new StyledRun(text, bold, italic, false))));
    }

    /** {@link #fontStyledWidth}, remembered per style until the font or the text scale changes. */
    private static final StyledWidths STYLED_WIDTHS =
            new StyledWidths(GuiGraphicsRenderer::fontStyledWidth, TextEpoch::now);

    /**
     * Four width memos, one per pairing of bold and italic, over the same width function.
     *
     * <p>Four rather than one keyed by the style as well, because each is {@code Measure.cached}, the memo
     * {@code WIDTHS} already is, with its bound and its epoch: a run is a string plus two flags, and a flag
     * picks the memo. A prose card asks the same few runs every frame, and before this each of them was a walk
     * of its glyphs through the font, with a component and a style built first for the styled ones.
     *
     * <p>Game-free, with the width function and the epoch handed in, so a test can count what reaches the font.
     */
    static final class StyledWidths {

        /** The font's width of a string in a style. */
        @FunctionalInterface
        interface Width {
            int of(String text, boolean bold, boolean italic);
        }

        private final Measure[] memos = new Measure[4];

        StyledWidths(Width width, LongSupplier epoch) {
            for (int style = 0; style < memos.length; style++) {
                boolean bold = (style & 1) != 0;
                boolean italic = (style & 2) != 0;
                // The line height is never read: a width memo is only asked widths. See WIDTHS.
                memos[style] = Measure.cached(Measure.of(text -> width.of(text, bold, italic), 9), epoch);
            }
        }

        int width(String text, boolean bold, boolean italic) {
            return memos[(bold ? 1 : 0) | (italic ? 2 : 0)].width(text);
        }
    }

    /** The game's style for a run: the whole of what this seam's two flags mean. */
    private static Style styleOf(StyledRun run) {
        Style style = Style.EMPTY;
        if (run.bold()) {
            style = style.withBold(true);
        }
        if (run.italic()) {
            style = style.withItalic(true);
        }
        if (run.underline()) {
            style = style.withUnderlined(true);
        }
        return style;
    }

    @Override
    public int textWidth(String text) {
        // Memoized here rather than at the call sites, and there are a hundred and forty-five of them: the
        // screen alone asks this ~185 times a frame with an editor card open, every one a walk of the
        // string's glyphs through the font, and the same handful of strings come back every frame.
        //
        // Here rather than in a caller because *this* is where the answer can be invalidated correctly: the
        // font and the player's text scale are the only things that change a width without changing a
        // string, and TextEpoch is the one definition of when they have. See CachedMeasure for the bound.
        //
        // `styledWidth` has memos of its own beside this one rather than a share of it: bold and italic change
        // the answer, so they pick one of four memos (see StyledWidths), and the run's scale is applied after
        // the lookup, so it never enters a key.
        return WIDTHS.width(text);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Straight into the width memo's slice table rather than through the default's copy: the
     * callers are the field and the text area measuring selections and carets per frame, and the
     * slice they ask about is the same object every frame until the text is edited.
     */
    @Override
    public int textWidth(String text, int from, int to) {
        return WIDTHS.width(text, from, to);
    }

    /**
     * The widths this client's font has already answered.
     *
     * <p>Static because a record cannot hold instance state, and correct because there is one font: two
     * renderers over the same {@code GuiGraphics} are two views of one client. The line height passed in is
     * never read — a width memo is asked how wide a string is and nothing else — but the interface needs a
     * number for the question, and nine is the font's own at a scale of one.
     */
    private static final Measure WIDTHS = Measure.cached(
            Measure.of(text -> (int) Math.round(
                    Minecraft.getInstance().font.width(text) * TextScale.get()), 9),
            TextEpoch::now);

    @Override
    public int lineHeight() {
        return (int) Math.round(font().lineHeight * TextScale.get());
    }

    // ------------------------------------------------------------------
    // Icons
    // ------------------------------------------------------------------

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        if (stack == null || stack.isEmpty() || box <= 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            // The item draw reads the level for the model, so this is a crash rather than a blank
            // icon. Reported as "nothing drawn" so the caller falls back to a block.
            return false;
        }

        // Always through the item draw, never a sprite blit. On 1.21.1 a flat fast path blitted the
        // particle icon for simple items, decided by a walk of the model's quads (`IconPlan`); that
        // whole apparatus went away with the 1.21.1 model pipeline. There is no `BakedModel` to walk
        // any more and no `ItemColors` to ask, and tinting happens inside the item draw itself --
        // which is also what makes the flat path unnecessary: a tinted item drawn by this call comes
        // out tinted, so there is nothing left to decide. `IconPlan` and its test went with the
        // pipeline they measured; if icons ever show in a profile on this version, re-measure here.
        if (holding()) {
            HELD.add(new Held(Held.ITEM, stack, null, boxX, boxY, box, box, 0, new Matrix3x2f(graphics.pose())));
            return true;
        }
        flushBatch(graphics);
        drawItem(stack, boxX, boxY, box);
        return true;
    }

    private void drawItem(ItemStack stack, int boxX, int boxY, int box) {
        float scale = box / 16.0F;
        graphics.pose().pushMatrix();
        graphics.pose().translate(boxX, boxY);
        graphics.pose().scale(scale, scale);
        graphics.item(stack, 0, 0);
        graphics.pose().popMatrix();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code PlayerFaceExtractor} rather than blits written here, for the same reason its
     * predecessor was: the face and hat rectangles and the scaling between them belong to the
     * version, and hand-writing them once produced a solid orange square of stretched hat hair.
     * This is the same call the tab list draws with.
     *
     * <p>A resolvable profile rather than a looked-up skin: the async skin fetch cannot block a
     * draw, so the extractor resolves what it can and falls back to the default face for the rest.
     * A player the client has no skin for -- an offline server, an unknown UUID -- still draws a
     * face rather than a gap, which is the contract.
     *
     * <h2>The profile comes from the player entity when the client has one</h2>
     *
     * <p>And that is not a shortcut, it is the difference between the right skin and a default one. In a
     * single-player world the server is in offline mode, so the UUID a roster carries is derived from
     * the player's <i>name</i> -- while the client's own account has an id, and a skin, keyed by
     * something else. The entity the client is already drawing carries the <b>real</b> profile, so it
     * is asked first, and the id is only the fallback for somebody this client cannot see.
     */
    @Override
    public boolean face(UUID player, int boxX, int boxY, int box) {
        if (player == null || box <= 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }

        ResolvableProfile profile;
        if (minecraft.level != null
                && minecraft.level.getPlayerByUUID(player) instanceof AbstractClientPlayer entity) {
            profile = ResolvableProfile.createResolved(entity.getGameProfile());
        }
        else {
            profile = ResolvableProfile.createUnresolved(player);
        }

        boundary();
        net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(
                graphics, profile, boxX, boxY, box);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One call: {@code blurBeforeThisStratum} softens everything recorded so far in this frame's
     * extraction, and the card is drawn after it. There is deliberately no state to put back -- the
     * three-line restore the 1.21.1 version needed (rebind target, scissor, blend) belonged to the
     * immediate-mode post chain, and extraction records operations rather than executing GL state,
     * so there is nothing for the passes to leave behind. That is also why the mid-frame-vanish
     * fault cannot recur in this form: nothing is drawn through leftover state any more. Verified
     * against vanilla's own {@code extractBlurredBackground}, which is exactly this call behind the
     * same blurriness check.
     */
    @Override
    public boolean blur(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            // No world behind this screen, so there is nothing to soften and the blur would land on
            // a panorama. Reported as "nothing drawn" so a caller can draw its own scrim instead.
            return false;
        }
        if (minecraft.options.getMenuBackgroundBlurriness() <= 0) {
            return false;
        }
        boundary();
        graphics.blurBeforeThisStratum();
        return true;
    }

    // ------------------------------------------------------------------
    // Textures
    // ------------------------------------------------------------------

    /** A PNG's signature: eight bytes, then the IHDR width and height at 16 and 20. */
    private static final int PNG_HEADER_BYTES = 24;

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * {@inheritDoc}
     *
     * <h2>The header, not the image</h2>
     *
     * <p>{@code NativeImage.read} would answer the same question by decoding every pixel, which for a
     * wallpaper-sized background is megabytes of work and a texture upload to learn two numbers that
     * the first twenty-four bytes already carry. So the resource is opened, its PNG signature checked,
     * and the IHDR width and height read as big-endian ints — the layout the PNG specification fixes,
     * not a guess from a screenshot. Anything absent, short, unreadable or not a PNG is empty, so a
     * caller draws nothing rather than stretching whatever file the resource manager did find.
     *
     * <p>No cache here: this wrapper is created per frame and holds no state, and the caller that
     * asks for the same file every frame is the one that should remember the answer.
     */
    @Override
    public Optional<TextureSize> textureSize(Identifier texture) {
        if (texture == null) {
            return Optional.empty();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return Optional.empty();
        }
        Optional<Resource> resource = minecraft.getResourceManager().getResource(texture);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream stream = resource.get().open()) {
            byte[] header = stream.readNBytes(PNG_HEADER_BYTES);
            if (header.length < PNG_HEADER_BYTES || !hasPngSignature(header)) {
                return Optional.empty();
            }
            int width = readBigEndianInt(header, 16);
            int height = readBigEndianInt(header, 20);
            if (width <= 0 || height <= 0) {
                return Optional.empty();
            }
            return Optional.of(new TextureSize(width, height));
        }
        catch (IOException absentOrUnreadable) {
            return Optional.empty();
        }
    }

    /**
     * Which pack stack the game's resources are read through, as a serial: the same until a resource reload,
     * and a new one after every reload.
     *
     * <h2>Why the stack, and not the resource</h2>
     *
     * <p>This used to be the identity hash of the {@code Resource} the id resolved to, on the belief that a
     * resource stays the same object until a reload. It does not: the pack stack builds a new {@code Resource}
     * on every lookup, so the stamp moved on every frame, the caller's cache never hit, and the wallpaper's
     * header was read again every frame, behind a walk of every pack. The lookup is also exactly the cost a hit
     * has to avoid.
     *
     * <p>What a reload does replace, read from the version, is the stack itself: {@code createReload} builds a
     * new {@code MultiPackResourceManager} into {@code ReloadableResourceManager.resources} before any listener
     * runs, and nothing else assigns it. So the serial moves exactly when the files behind an id may have
     * changed, for every id at once, and a hit costs a field read and one identity comparison. It is a serial
     * from {@link IdentityEpoch} rather than an identity hash, because a hash is not unique; see
     * {@code TextEpoch}.
     *
     * <p>A texture that is absent or unreadable is not this method's question. The caller caches only sizes it
     * was given, so an absent file is asked about again, and still draws nothing.
     *
     * <p>Where the stack cannot be read, every ask is a new stamp, so the caller re-reads the header each time
     * as it did before: slow, and never stale.
     */
    @Override
    public long textureStamp(Identifier texture) {
        if (texture == null) {
            return 0L;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return 0L;
        }
        Object stack = PackStack.of(minecraft.getResourceManager());
        if (stack == null) {
            // Below zero, where the serials never go, and never the same twice.
            return --unknownStamp;
        }
        return PACK_STACKS.of(stack);
    }

    /** The serial for each pack stack the game's resource manager has read through. */
    private static final IdentityEpoch PACK_STACKS = new IdentityEpoch();

    /** The last stamp handed out while the stack could not be read. Render thread only, like the batch. */
    private static long unknownStamp;

    /**
     * {@code ReloadableResourceManager.resources}, the pack stack the game's resource manager reads through, and
     * which a reload replaces. In a class of its own so the field is looked up once and a test can read a
     * stack without a client.
     */
    static final class PackStack {

        private static final MethodHandle GET;

        static {
            MethodHandle get = null;
            try {
                Field field = ReloadableResourceManager.class.getDeclaredField("resources");
                field.setAccessible(true);
                get = MethodHandles.lookup().unreflectGetter(field)
                        .asType(MethodType.methodType(Object.class, Object.class));
            }
            catch (ReflectiveOperationException | RuntimeException ignored) {
                // Without it every stamp is new, which is slow and correct.
            }
            GET = get;
        }

        private PackStack() {
        }

        /** The stack {@code manager} reads through now, or null when it is not a manager this can read. */
        static Object of(Object manager) {
            if (GET == null || !(manager instanceof ReloadableResourceManager)) {
                return null;
            }
            try {
                return (Object) GET.invokeExact(manager);
            }
            catch (Throwable unexpected) {
                return null;
            }
        }
    }

    /**
     * A number that moves exactly when the object it is shown is a different one, for telling a replaced
     * object from the one before it.
     *
     * <p>Identity rather than {@code System.identityHashCode}, which two objects can share; and the object is
     * held weakly, so the one it was last shown is not kept alive by being remembered. A weak reference that
     * has been cleared cannot be the object in hand, so a cleared one reads as a change, which it is.
     *
     * <p>Shared with {@link GpuMasks#frame}, which tells frames apart the same way. Game-free, for the tests.
     */
    static final class IdentityEpoch {

        private WeakReference<Object> seen = new WeakReference<>(null);
        private long serial;

        /** The serial for {@code current}, which must not be null: a new one if it is not the last object shown. */
        long of(Object current) {
            if (current != seen.get()) {
                seen = new WeakReference<>(current);
                serial++;
            }
            return serial;
        }
    }

    private static boolean hasPngSignature(byte[] header) {
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (header[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readBigEndianInt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 24
                | (bytes[at + 1] & 0xFF) << 16
                | (bytes[at + 2] & 0xFF) << 8
                | (bytes[at + 3] & 0xFF);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>The tint path, and why it is an argument</h2>
     *
     * <p>On 1.21.1 the tint was pipeline state ({@code setColor} around the blit, put back in a
     * {@code finally}). The extractor takes the colour as the blit's last argument instead, so there
     * is no global state to leak into later draws: white ({@code 0xFFFFFFFF}) draws the file exactly
     * as authored, a colour shifts it, and a low alpha washes it towards whatever is behind.
     */
    @Override
    public void scaled(Identifier texture, int x, int y, int width, int height,
                       float u, float v, int sourceWidth, int sourceHeight,
                       int textureWidth, int textureHeight, int argb) {
        boundary();
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height,
                sourceWidth, sourceHeight, textureWidth, textureHeight, argb);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every tile is a quad of one {@link BatchedTexturedRenderState}, under the image's own texture, rather
     * than a {@code blit} each, so the game finds a layer for the whole lattice once instead of checking each
     * tile against every tile before it. Each quad is the one the blit would have filed, in the default's
     * order; see that record for why the pixels are the same.
     *
     * <p>Around it, it behaves as {@link #scaled} does: it starts at the {@link #boundary}, so whatever was queued
     * and any pictures an {@link #iconsLast} region is holding draw first, and it is not itself held. The run is
     * flushed as soon as it is queued, so a fill after it starts a run of its own, as a fill after a blit did,
     * and it is under the scissor that was open when it was asked for. A lattice with no tiles passes no
     * boundary, because the default calls nothing for it.
     */
    @Override
    public void scaledLattice(Identifier texture, int[] xs, int columnCount, int width,
                              int[] ys, int rowCount, int height,
                              float u, float v, int sourceWidth, int sourceHeight,
                              int textureWidth, int textureHeight, int argb) {
        if (columnCount <= 0 || rowCount <= 0) {
            return;
        }
        if (width <= 0 || height <= 0) {
            GuiRenderer.super.scaledLattice(texture, xs, columnCount, width, ys, rowCount, height, u, v,
                    sourceWidth, sourceHeight, textureWidth, textureHeight, argb);
            return;
        }
        if (!BATCH.batching(graphics)) {
            if (GET_RENDER_STATE == null || PEEK_SCISSOR == null) {
                GuiRenderer.super.scaledLattice(texture, xs, columnCount, width, ys, rowCount, height, u, v,
                        sourceWidth, sourceHeight, textureWidth, textureHeight, argb);
                return;
            }
            batched(() -> {
                scaledLattice(texture, xs, columnCount, width, ys, rowCount, height, u, v,
                        sourceWidth, sourceHeight, textureWidth, textureHeight, argb);
                return null;
            });
            return;
        }
        boundary();
        // What blit(RenderPipeline, Identifier, ...) does with the id, read from the version: the texture
        // manager's object for it (loaded on first ask, as the blit would have), its view, and its sampler.
        AbstractTexture image = Minecraft.getInstance().getTextureManager().getTexture(texture);
        TextureSetup setup = TextureSetup.singleTexture(image.getTextureView(), image.getSampler());
        float[] uv = BatchedTexturedRenderState.blitUvs(u, v, sourceWidth, sourceHeight,
                textureWidth, textureHeight);
        batchMasks = setup;
        for (int j = 0; j < rowCount; j++) {
            int y = ys[j];
            for (int i = 0; i < columnCount; i++) {
                int x = xs[i];
                queue(x, y, x + width, y + height, argb, uv[0], uv[1], uv[2], uv[3]);
            }
        }
        flushBatch(graphics);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code blit} with the image's own size named as the texture size, which is the whole of it:
     * the shorter overload assumes a 256x256 sheet and would sample the wrong region of a 12-pixel
     * file, so both sizes are written out. White needs no passing: the untinted overload draws the
     * file exactly as authored.
     */
    @Override
    public void texture(Identifier texture, int x, int y, int width, int height) {
        if (holding()) {
            HELD.add(new Held(Held.TEXTURE, null, texture, x, y, width, height, 0, new Matrix3x2f(graphics.pose())));
            return;
        }
        flushBatch(graphics);
        drawTexture(texture, x, y, width, height);
    }

    private void drawTexture(Identifier texture, int x, int y, int width, int height) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0F, 0F, width, height,
                width, height, width, height);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>The atlas, and why this class picks it rather than the caller</h2>
     *
     * <p>{@code TextureAtlas.LOCATION_BLOCKS} is the sheet a model's textures are stitched into,
     * whether the model belongs to a block or to an item — so it is the one sheet that holds every
     * sprite a picture element could name. Which sheet to ask is a fact about this version's
     * resource pipeline, which is exactly the kind of fact this file exists to own.
     *
     * <p>No null guard on the sprite, and that is read rather than assumed: the atlas answers an
     * unknown id with its missing marker rather than null, so "draw nothing" was never available
     * here without naming the marker and second-guessing it.
     */
    @Override
    public void sprite(Identifier atlasSprite, int x, int y, int width, int height, int argb) {
        if (atlasSprite == null || width <= 0 || height <= 0) {
            return;
        }
        if (holding()) {
            HELD.add(new Held(Held.SPRITE, null, atlasSprite, x, y, width, height, argb,
                    new Matrix3x2f(graphics.pose())));
            return;
        }
        flushBatch(graphics);
        drawSprite(atlasSprite, x, y, width, height, argb);
    }

    private void drawSprite(Identifier atlasSprite, int x, int y, int width, int height, int argb) {
        TextureAtlasSprite sprite = graphics.getSprite(
                new SpriteId(TextureAtlas.LOCATION_BLOCKS, atlasSprite));
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, argb);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>One call, because the nine-slicing is the sprite's own</h2>
     *
     * <p>This version's {@code GuiGraphics.blitSprite} reads the sprite's scaling metadata
     * itself — a button's 3-pixel nine-slice border, a tooltip frame's 9 — so there are no
     * insets to thread through the seam and no pieces to loop over. The guards are the same
     * shape as {@link #sprite}'s: nothing to draw, or nowhere to draw it, draws nothing.
     */
    @Override
    public void guiSprite(Identifier guiSprite, int x, int y, int width, int height) {
        if (guiSprite == null || width <= 0 || height <= 0) {
            return;
        }
        boundary();
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, guiSprite, x, y, width, height);
    }

    // ------------------------------------------------------------------
    // Turning
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <h2>The order of the three pose calls, which is the whole of it</h2>
     *
     * <p>Translate to the pivot, turn, translate back. The first translate is what makes the pivot the point
     * that stays still; the second says that what follows is measured from the pivot again rather than from
     * the corner of the screen. Swapping the last two would turn the picture about the origin of the frame,
     * which is a different picture at the same angle — the kind of mistake that looks like a coordinate bug
     * in the caller.
     *
     * <p>The pop is in a {@code finally}, and the guard against a second close is not symmetry with
     * {@link #clip}: a second pop here would remove a frame <b>the caller</b> pushed, which is a fault that
     * surfaces somewhere else entirely. Same reason, one level up.
     */
    @Override
    public Scoped turned(int pivotX, int pivotY, float degrees) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(pivotX, pivotY);
        graphics.pose().rotate((float) Math.toRadians(degrees));
        graphics.pose().translate(-pivotX, -pivotY);
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                graphics.pose().popMatrix();
            }
        };
    }

    // ------------------------------------------------------------------
    // Clipping
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>The scissor stack in 1.21.1 is genuinely a stack — {@code enableScissor} pushes a
     * {@code ScreenRectangle} and {@code disableScissor} pops one, both read from the jar rather than
     * recalled — so a nested clip narrows its parent rather than replacing it, and the pops have to
     * come off in the reverse order. That is what nesting {@code try} blocks gives for free.
     */
    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        boundary();
        graphics.enableScissor(left, top, right, bottom);
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    // A second close would pop a rectangle this scope never pushed, unbalancing the
                    // stack in the direction that is hardest to see: the frame ends one scissor too
                    // shallow, and every later draw is clipped to something nobody chose. A caller
                    // that closes explicitly and then lets try-with-resources close again is a mistake
                    // worth making harmless.
                    return;
                }
                closed = true;
                boundary();
                graphics.disableScissor();
            }
        };
    }
}
