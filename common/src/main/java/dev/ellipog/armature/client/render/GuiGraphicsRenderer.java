package dev.ellipog.armature.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import dev.ellipog.armature.client.TextScale;
import dev.ellipog.armature.client.ui.kit.Measure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import org.joml.Matrix3x2fc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
    private static int[] batchColours = new int[128];
    private static int batchQuads;
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

    private static void flushBatch(GuiGraphicsExtractor extractor) {
        if (batchQuads == 0 || BATCH.owner() != extractor) {
            return;
        }
        int count = batchQuads;
        float[] vertices = Arrays.copyOf(batchVertices, count * 8);
        int[] colours = Arrays.copyOf(batchColours, count);
        int minX = Mth.floor(batchMinX);
        int minY = Mth.floor(batchMinY);
        int maxX = Mth.ceil(batchMaxX);
        int maxY = Mth.ceil(batchMaxY);
        batchQuads = 0;

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
        renderState.addGuiElement(new BatchedFillsRenderState(vertices, colours, count, scissor, bounds));
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

    // ------------------------------------------------------------------
    // Rectangles
    // ------------------------------------------------------------------

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        if (BATCH.batching(graphics)) {
            if (left == right || top == bottom) {
                return;
            }
            int x0 = Math.min(left, right);
            int x1 = Math.max(left, right);
            int y0 = Math.min(top, bottom);
            int y1 = Math.max(top, bottom);

            Matrix3x2fc pose = graphics.pose();
            float m00 = pose.m00();
            float m01 = pose.m01();
            float m10 = pose.m10();
            float m11 = pose.m11();
            float m20 = pose.m20();
            float m21 = pose.m21();

            float vx0 = m00 * x0 + m10 * y0 + m20;
            float vy0 = m01 * x0 + m11 * y0 + m21;
            float vx1 = m00 * x0 + m10 * y1 + m20;
            float vy1 = m01 * x0 + m11 * y1 + m21;
            float vx2 = m00 * x1 + m10 * y1 + m20;
            float vy2 = m01 * x1 + m11 * y1 + m21;
            float vx3 = m00 * x1 + m10 * y0 + m20;
            float vy3 = m01 * x1 + m11 * y0 + m21;

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
            return;
        }
        graphics.fill(left, top, right, bottom, argb);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Drains any fills queued inside an active {@link #batched} region so the caller's layering
     * boundary is honoured before whatever draw follows. Outside a batched region this is a no-op.
     */
    @Override
    public void flush() {
        flushBatch(graphics);
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
        }
        try {
            return draw.get();
        }
        finally {
            BATCH.exit(entry, GuiGraphicsRenderer::flushBatch);
        }
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
        flushBatch(graphics);
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
        flushBatch(graphics);
        int at = x;
        float base = (float) TextScale.get();
        for (StyledRun run : runs) {
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
            // cannot disagree.
            at += styledWidth(run.text(), run.bold(), run.italic(), run.scale());
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
        // Measured through the same style it will be drawn with: the game's bold font is one pixel wider
        // per glyph, so measuring plain and drawing bold is a line that runs past its column.
        int plain;
        if (!bold && !italic) {
            plain = font().width(text);         // underline is not wider, so it does not enter the measure
        }
        else {
            plain = font().width(Component.literal(text).withStyle(styleOf(new StyledRun(text, bold, italic,
                    false))));
        }
        return (int) Math.round(plain * scale * TextScale.get());
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
        // `styledWidth` is deliberately not memoized beside it: its answer depends on three more inputs
        // (bold, italic, scale), so it needs a key this memo cannot express, and it is called for the few
        // strings that carry runs rather than for every label in a panel.
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
        flushBatch(graphics);
        float scale = box / 16.0F;
        graphics.pose().pushMatrix();
        graphics.pose().translate(boxX, boxY);
        graphics.pose().scale(scale, scale);
        graphics.item(stack, 0, 0);
        graphics.pose().popMatrix();
        return true;
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

        flushBatch(graphics);
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
        flushBatch(graphics);
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
     * The resource's identity, which is what changes when a pack reload replaces the file.
     *
     * <p>A reload builds a new resource manager, so the same id resolves to a different {@code Resource}
     * object — and the object is what the stamp is taken from, rather than its contents. Reading the file
     * to compare it would cost exactly what the caller's cache exists to avoid, and a modification time is
     * not reliable enough across the file systems this runs on (the same reason {@code ParsedFiles} takes
     * an explicit invalidation from the write path).
     *
     * <p>Empty resolution is a stamp of zero rather than an error: "there is nothing here" is a stable
     * answer, and a caller that cached it will ask again the moment one arrives, because the stamp it
     * holds no longer matches.
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
        return minecraft.getResourceManager().getResource(texture)
                .map(resource -> (long) System.identityHashCode(resource))
                .orElse(0L);
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
        flushBatch(graphics);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height,
                sourceWidth, sourceHeight, textureWidth, textureHeight, argb);
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
        flushBatch(graphics);
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
        flushBatch(graphics);
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
        flushBatch(graphics);
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
        flushBatch(graphics);
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
                flushBatch(graphics);
                graphics.disableScissor();
            }
        };
    }
}
