package dev.ellipog.armature.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.client.ui.shape.Plans;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game renderer's masks on the GPU: one texture that holds a shape's pixels or a row of a pattern's marks,
 * written once and drawn from as often as the shape is.
 *
 * <h2>What it is for</h2>
 *
 * <p>A shape drawn as fills is one quad per rectangle, rebuilt by the CPU on every frame: a circle is a
 * rectangle per distinct row, so a canvas of round nodes is tens of thousands of quads for a few hundred
 * shapes. Drawn from a mask, a shape is <b>one</b> quad, whatever its outline. {@link GuiGraphicsRenderer}
 * asks this class where a plan's mask is, and draws a textured quad over it in the same batch as its fills.
 *
 * <h2>Exactly the same pixels</h2>
 *
 * <p>The mask is made from the plan's own rectangles, one texel per pixel, opaque white where they fill and
 * clear elsewhere, and it is sampled nearest. The textured pipeline draws {@code texture * colour}, so a white
 * texel is the fill's colour exactly, and a clear one is discarded the way a transparent fill is. A fill in the
 * same batch samples the white texel at the texture's corner, which is what lets fills and masks share one
 * batch and keep their painter's order.
 *
 * <h2>When the texture is emptied, which is the one thing that has to be right</h2>
 *
 * <p>Drawing is recorded first and executed later in the frame, so a region written over while the frame is
 * still being recorded changes what an earlier shape in the same frame draws. So a full texture is never
 * emptied on the spot: the shape that did not fit draws as fills, and the texture is emptied at the
 * <b>start of the next frame</b>, when everything that read the old regions has been submitted. A frame is
 * recognised by the game's GUI state starting a new first layer, which it does exactly once per frame.
 *
 * <h2>Off, on purpose or by necessity</h2>
 *
 * <p>{@code -Darmature.gpuMasks=false} turns this off, for measuring the difference and for ruling it out.
 * Anything this class cannot do (a GUI state it cannot read, a device that refuses the texture, an upload that
 * throws) turns it off for the session with one warning, and every shape is drawn as fills, which is what was
 * drawn before it existed.
 */
final class GpuMasks {

    /** A plan with fewer rectangles than this is drawn as fills: it would cost no fewer quads as a mask. */
    static final int MIN_PLAN_RECTS = 4;

    /**
     * The widest and tallest mask, in texels. Bigger shapes are few, and drawn as fills.
     *
     * <p>256 rather than 128 because a quest node at the closest zoom is past 128 on screen, and every one of
     * its layers fell back to a fill per row on every frame. {@link MaskAtlas#SHELF_HEIGHTS} has to hold a
     * mask this tall with its gutter, and a test holds the two together.
     */
    static final int MAX_SIDE = 256;

    /** A lattice with fewer columns than this is drawn as fills: a row's quad would save nothing. */
    static final int MIN_LATTICE_COLUMNS = 4;

    /** The texture's edge, at most. Sixteen megabytes of RGBA, and room for a few thousand node layers. */
    private static final int MAX_ATLAS = 2048;

    private static final boolean REQUESTED =
            !"false".equalsIgnoreCase(System.getProperty("armature.gpuMasks"));

    /** {@code GuiRenderState.strata}: its first entry is new each frame, which is how a frame is told apart. */
    private static final MethodHandle GET_STRATA;

    static {
        MethodHandle strata = null;
        try {
            Field field = GuiRenderState.class.getDeclaredField("strata");
            field.setAccessible(true);
            strata = MethodHandles.lookup().unreflectGetter(field);
        }
        catch (ReflectiveOperationException | RuntimeException ignored) {
            // Without a way to tell frames apart the texture could not be emptied safely, so masks stay off.
        }
        GET_STRATA = strata;
    }

    private static GpuMasks current;
    private static boolean disabled;

    /** The frame serial {@link #frame} hands out, moved by each new first layer of the game's GUI state. */
    private static final GuiGraphicsRenderer.IdentityEpoch FRAMES = new GuiGraphicsRenderer.IdentityEpoch();

    /** Where a mask is in the texture and where it lands relative to the draw origin. */
    record Placed(int left, int top, int right, int bottom, float u0, float v0, float u1, float v1) {
    }

    /** One piece of a lattice's strip: where it starts along the row, how long it is, and where it lives. */
    record Segment(int offset, int length, float u0, float v0, float u1, float v1) {
    }

    /** A plan as a map key: by its rectangles, so a plan rebuilt per call still finds its mask. */
    record PlanKey(Plans.Plan plan) {
        @Override
        public boolean equals(Object other) {
            return other instanceof PlanKey key && plan.sameRects(key.plan);
        }

        @Override
        public int hashCode() {
            return plan.contentHash();
        }
    }

    /** A strip as a map key: the mark width, then every column measured from the first. */
    private record StripKey(int[] shape) {
        @Override
        public boolean equals(Object other) {
            return other instanceof StripKey key && Arrays.equals(shape, key.shape);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(shape);
        }
    }

    /**
     * What this texture has answered for each plan: where its mask is, or that it is drawn as fills for good.
     *
     * <h2>Why a refusal is remembered</h2>
     *
     * <p>Because it was not, and a refused plan paid for the question on every frame: a key, a missed lookup and
     * the plan's bounding box worked out again, before the caller drew it as a fill per row anyway. At the
     * closest zoom that was every layer of every big node. A plan with nothing in it, one wider or taller than
     * {@link #MAX_SIDE} and one whose rectangles overlap are refused for what its rectangles are, and the key is
     * those rectangles, so the answer can never change while the entry is kept. One lookup answers it now.
     *
     * <p>A plan that did not fit because the texture was full is not one of those. It is asked again after the
     * texture is emptied, which is how it always worked.
     *
     * <h2>The refusals are bounded on their own</h2>
     *
     * <p>A mask takes room in the texture, so the masks are bounded by the texture and emptied with it. A
     * refusal takes none, and a plan rebuilt per call (a panel that grows with its window) is a new key at
     * every size it passes through, so the refusals are counted and swept out when there are
     * {@link #MAX_REFUSALS} of them. The masks are left where they are.
     *
     * <p>Game-free, so a test can hold the sweep without a device.
     */
    static final class PlanTable {

        /** How many refusals are kept before they are swept out together. */
        static final int MAX_REFUSALS = 1024;

        /** The entry that says "drawn as fills". Compared by identity: no mask has an empty box. */
        static final Placed REFUSED = new Placed(0, 0, 0, 0, 0F, 0F, 0F, 0F);

        private final Map<PlanKey, Placed> entries = new HashMap<>();
        private int refusals;

        /** The plan's mask, {@link #REFUSED} when it is drawn as fills, or null when it has not been asked. */
        Placed find(PlanKey key) {
            return entries.get(key);
        }

        void place(PlanKey key, Placed placed) {
            entries.put(key, placed);
        }

        void refuse(PlanKey key) {
            if (refusals >= MAX_REFUSALS) {
                entries.values().removeIf(placed -> placed == REFUSED);
                refusals = 0;
            }
            entries.put(key, REFUSED);
            refusals++;
        }

        void clear() {
            entries.clear();
            refusals = 0;
        }

        int size() {
            return entries.size();
        }
    }

    private final GpuDevice device;
    private final GpuTexture texture;
    private final TextureSetup setup;
    private final MaskAtlas atlas;
    private final PlanTable plans = new PlanTable();
    private final Map<StripKey, Segment[]> strips = new HashMap<>();
    private ByteBuffer scratch = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
    private WeakReference<Object> frame = new WeakReference<>(null);
    private boolean emptyNextFrame;

    private GpuMasks(GpuDevice device) {
        this.device = device;
        int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
        int size = Math.min(MAX_ATLAS, limit);
        this.texture = device.createTexture("Armature shape masks",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM,
                size, size, 1, 1);
        GpuTextureView view = device.createTextureView(texture);
        this.setup = TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        this.atlas = new MaskAtlas(size);
        reserveWhite();
    }

    /**
     * The masks for the frame being drawn, or null when shapes should be drawn as fills.
     *
     * <p>Asked once per shape. On the first ask of a new frame it empties the texture if the last frame filled
     * it, which is the only moment that is safe; see the class note.
     */
    static GpuMasks forFrame() {
        if (!REQUESTED || disabled || GET_STRATA == null || !RenderSystem.isOnRenderThread()) {
            return null;
        }
        try {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device == null) {
                return null;
            }
            GpuMasks masks = current;
            if (masks == null || masks.device != device) {
                masks = new GpuMasks(device);
                current = masks;
                Constants.LOG.info("Drawing shapes from a {}x{} GPU mask atlas", masks.atlas.size(),
                        masks.atlas.size());
            }
            masks.beginFrame();
            return masks;
        }
        catch (Throwable failure) {
            disable(failure);
            return null;
        }
    }

    /** Turns masks off for the session, saying why once. */
    static void disable(Throwable failure) {
        if (!disabled) {
            disabled = true;
            Constants.LOG.warn("GPU shape masks are off for this session; shapes draw as fills", failure);
        }
    }

    /** The texture binding a textured batch draws with. */
    TextureSetup textureSetup() {
        return setup;
    }

    /**
     * Where a plan's mask is, writing it first if it is not there yet, or null when this plan is drawn as fills:
     * too few rectangles, too big, overlapping itself, or no room left this frame. A plan refused for what its
     * rectangles are (empty, too big, overlapping) is remembered, so it costs one lookup after; see
     * {@link PlanTable}.
     */
    Placed plan(Plans.Plan plan) {
        if (plan.count() < MIN_PLAN_RECTS) {
            return null;
        }
        PlanKey key = new PlanKey(plan);
        Placed hit = plans.find(key);
        if (hit != null) {
            return hit == PlanTable.REFUSED ? null : hit;
        }
        int[] bounds = MaskAtlas.bounds(plan);
        if (bounds == null) {
            plans.refuse(key);
            return null;
        }
        int width = bounds[2] - bounds[0];
        int height = bounds[3] - bounds[1];
        if (width > MAX_SIDE || height > MAX_SIDE) {
            plans.refuse(key);
            return null;
        }
        byte[] coverage = MaskAtlas.coverage(plan, bounds);
        if (coverage == null) {
            plans.refuse(key);
            return null;
        }
        MaskAtlas.Region region = atlas.allocate(width, height);
        if (region == null) {
            emptyNextFrame = true;
            return null;
        }
        upload(region, coverage, 0, width);
        Placed placed = new Placed(bounds[0], bounds[1], bounds[2], bounds[3],
                u(region.x()), v(region.y()), u(region.x() + width), v(region.y() + height));
        plans.place(key, placed);
        return placed;
    }

    /**
     * Where a lattice's strip is, in as many pieces as the texture's width needs, or null when it is drawn as
     * fills: out of order or overlapping marks, or no room left this frame.
     */
    Segment[] strip(int[] xs, int count, int width) {
        int origin = xs[0];
        int[] shape = new int[count + 1];
        shape[0] = width;
        for (int i = 0; i < count; i++) {
            shape[i + 1] = xs[i] - origin;
        }
        StripKey key = new StripKey(shape);
        Segment[] hit = strips.get(key);
        if (hit != null) {
            return hit;
        }
        byte[] coverage = MaskAtlas.strip(xs, count, width);
        if (coverage == null) {
            return null;
        }
        int piece = atlas.size() - 1;
        int pieces = (coverage.length + piece - 1) / piece;
        Segment[] out = new Segment[pieces];
        for (int s = 0; s < pieces; s++) {
            int offset = s * piece;
            int length = Math.min(piece, coverage.length - offset);
            MaskAtlas.Region region = atlas.allocate(length, 1);
            if (region == null) {
                emptyNextFrame = true;
                return null;
            }
            upload(region, coverage, offset, coverage.length);
            out[s] = new Segment(offset, length, u(region.x()), v(region.y()), u(region.x() + length),
                    v(region.y() + 1));
        }
        strips.put(key, out);
        return out;
    }

    // ------------------------------------------------------------------
    // The texture
    // ------------------------------------------------------------------

    /** Empties the texture if the last frame asked for it and this is a new frame. See the class note. */
    private void beginFrame() throws Throwable {
        Object stamp = firstStratum(Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState);
        if (stamp == null || stamp == frame.get()) {
            return;
        }
        frame = new WeakReference<>(stamp);
        if (emptyNextFrame) {
            emptyNextFrame = false;
            atlas.reset();
            plans.clear();
            strips.clear();
            reserveWhite();
        }
    }

    /**
     * The first layer of a GUI state, which is how a frame is told apart: the game makes a new one when it
     * resets the state at the end of drawing a frame, and at no other time. Null for a state with no layers.
     */
    private static Object firstStratum(GuiRenderState state) throws Throwable {
        List<?> strata = (List<?>) GET_STRATA.invoke(state);
        return strata.isEmpty() ? null : strata.get(0);
    }

    /**
     * Which frame the game's GUI state is recording, as a number that moves exactly when a new one starts, or -1
     * when that cannot be told: {@code state} is not the game's own, or its layers cannot be read.
     *
     * <p>The same sign the texture is emptied by (see the class note), for a caller that needs to know when
     * what it handed the last frame has been drawn. The game draws a frame's GUI state and then resets it, so
     * everything filed under one number has been read by the time the number moves. Only the game's own state
     * is answered for, because that is the one whose reset is known to come after its drawing.
     */
    static long frame(GuiRenderState state) {
        if (GET_STRATA == null || state == null) {
            return -1L;
        }
        try {
            if (state != Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState) {
                return -1L;
            }
            Object stamp = firstStratum(state);
            return stamp == null ? -1L : FRAMES.of(stamp);
        }
        catch (Throwable unreadable) {
            return -1L;
        }
    }

    /**
     * The opaque texel every fill in a textured batch samples, at the texture's corner, so a fill's texture
     * coordinate is always zero and needs no texture to work out.
     */
    private void reserveWhite() {
        MaskAtlas.Region white = atlas.allocate(1, 1);
        if (white == null || white.x() != 0 || white.y() != 0) {
            throw new IllegalStateException("the white texel must be the atlas's first region: " + white);
        }
        upload(white, new byte[] {1}, 0, 1);
    }

    /**
     * Writes a region and its gutter: {@code region.width()} texels of each mask row from {@code fromX}, rows
     * {@code stride} apart, opaque white where the mask is set, and a clear column and row after it.
     */
    private void upload(MaskAtlas.Region region, byte[] coverage, int fromX, int stride) {
        int width = region.width() + 1;
        int height = region.height() + 1;
        int bytes = width * height * 4;
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        ByteBuffer buffer = scratch;
        buffer.clear();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean set = x < region.width() && y < region.height()
                        && coverage[y * stride + fromX + x] != 0;
                int texel = set ? 0xFFFFFFFF : 0;
                buffer.putInt(texel);
            }
        }
        buffer.flip();
        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(texture, buffer, 0, 0, region.x(), region.y(), width, height);
    }

    private float u(int texel) {
        return texel / (float) atlas.size();
    }

    private float v(int texel) {
        return texel / (float) atlas.size();
    }
}
