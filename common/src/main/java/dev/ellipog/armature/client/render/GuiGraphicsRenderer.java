package dev.ellipog.armature.client.render;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.ellipog.armature.client.TextScale;
import dev.ellipog.armature.client.ui.kit.Measure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 1.21.1's {@link GuiRenderer}: the one file in either mod that names {@code GuiGraphics}, and the
 * one file that has to change when the version does.
 *
 * <h2>What the port costs, stated now while it is knowable</h2>
 *
 * <p>26.1 renames the context to {@code GuiGraphicsExtractor} and moves drawing to an extraction
 * phase — {@code Screen#render} becomes {@code Screen#extractRenderState}. How much of that lands on
 * this file is not knowable until the version exists, so what is written down here is what <i>is</i>
 * known: every call in this class is a direct one-to-one translation of something either mod used to
 * call on {@code GuiGraphics} itself. There is no invented API to re-derive, no batching to unwind,
 * and no state this class keeps. The methods are short for that reason, and it is the property the
 * seam is worth having for.
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
 * <p>{@code renderItem} draws at a <b>fixed</b> 16 screen pixels: it translates to
 * {@code (x + 8, y + 8, 150)} and scales by {@code (16, -16, 16)}, and there is no size parameter. It
 * does, however, inherit the transform — it pushes onto the same {@code PoseStack} that
 * {@code pose()} hands out. So translating to the box's corner and scaling by {@code box / 16} makes
 * the item's own internal scale land exactly on {@code box}. Verified by reading
 * {@code GuiGraphics.renderItem} in the artefact this project compiles against rather than assumed,
 * because "does the parent transform apply" is wrong about half the time.
 *
 * <p>And {@code renderItem} reaches through {@code minecraft.player} and {@code minecraft.level} for
 * the model, so a null level is a crash rather than a blank icon. One guard here covers every caller.
 */
public record GuiGraphicsRenderer(GuiGraphics graphics) implements GuiRenderer {

    public GuiGraphicsRenderer {
        Objects.requireNonNull(graphics, "graphics");
    }

    /** The font this client is drawing with. Non-null whenever a graphics context exists. */
    private static Font font() {
        return Minecraft.getInstance().font;
    }

    // ------------------------------------------------------------------
    // Rectangles
    // ------------------------------------------------------------------

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        graphics.fill(left, top, right, bottom, argb);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code GuiGraphics.flush} is the batching flush, and it is the right one here rather than
     * {@code BufferSource.endBatch()}: this wrapper cannot see the buffer source, and asking the
     * context to flush is the same act expressed at the level this class is allowed to know about.
     */
    @Override
    public void flush() {
        graphics.flush();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code GuiGraphics.drawManaged} is the whole of the implementation: it flushes, marks the context
     * managed so every fill and label inside skips its own flush, runs the supplier, and flushes once at the
     * end. It is deprecated in this version, and used deliberately — it is the only switch that suppresses the
     * per-call flush, and the alternative is the behaviour the seam's {@link #batched} exists to fix.
     *
     * <p>The supplier's value is captured through an array because {@code drawManaged} takes a
     * {@code Runnable}: the context's signature cannot return what the caller drew, and this wrapper can.
     *
     * <h2>Regions nest, and they have to</h2>
     *
     * <p>{@code drawManaged} is <b>not</b> re-entrant: it sets the managed flag false on the way out
     * whatever it was on the way in, so a region opened inside another one <i>ends the outer one</i> — the
     * rest of the frame would then pay a flush per fill again, silently, and a whole-frame region would be
     * worth nothing the moment anything inside it batched. Since that is now exactly the arrangement (a
     * frame-wide region with the canvas's and the controls' regions inside it), the depth is counted here:
     * only the outermost region touches the context, and an inner one is just the supplier's call.
     *
     * <h2>Why the count is static, on a record</h2>
     *
     * <p>Because that is the only place it can live that is <i>right</i>. A record has no instance fields,
     * and an instance field would be wrong anyway: the flag this counter guards lives in the
     * {@code GuiGraphics}, and a screen holds two renderers over one context — the plain one and the
     * counting one wrapped around it — so a per-instance count would let the inner wrapper open a second
     * region inside the outer one, which is the exact fault this prevents. One context is drawn at a time
     * on the client thread, so one counter is the honest model of it.
     *
     * <p>And it is <b>not covered by a test</b>, which is worth saying rather than leaving to be assumed:
     * the guard needs a real {@code GuiGraphics}, and this project has no headless one. What is pinned is
     * the hazard — {@code GuiRendererTest.theContextsRegionIsNotReentrant} asserts that the seam's model of
     * the context is not re-entrant, so the reason for this code cannot quietly disappear — and the guard
     * itself is verified by reading the version's own {@code drawManaged}.
     */
    @Override
    public <T> T batched(java.util.function.Supplier<T> draw) {
        if (batchDepth++ > 0) {
            try {
                return draw.get();
            }
            finally {
                batchDepth--;
            }
        }
        try {
            Object[] result = new Object[1];
            graphics.drawManaged(() -> result[0] = draw.get());
            @SuppressWarnings("unchecked")
            T typed = (T) result[0];
            return typed;
        }
        finally {
            // Counted down even if the supplier threw, so one bad frame cannot leave every later frame
            // believing it is inside a region that no longer exists.
            batchDepth--;
        }
    }

    /** How many batch regions are open, so an inner one is a no-op rather than a fault. See {@link #batched}. */
    private static int batchDepth;

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
        double effective = scale * TextScale.get();
        if (effective == TextScale.DEFAULT) {
            graphics.drawString(font(), text, x, y, argb, shadow);
            return;
        }
        // Scaled about the string's own top-left, so the text grows right and down from where the
        // layout put it rather than from the panel's corner. `textWidth`/`lineHeight` report the same
        // factor, which is what keeps a layout that measured the string and the string it draws in step.
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0F);
        graphics.pose().scale((float) effective, (float) effective, 1F);
        graphics.drawString(font(), text, 0, 0, argb, shadow);
        graphics.pose().popPose();
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
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
                graphics.pose().pushPose();
                graphics.pose().translate(at, y, 0F);
                graphics.pose().scale(effective, effective, 1F);
                drawRun(run, 0, 0, argb);
                graphics.pose().popPose();
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
        graphics.drawString(font(), text, x, y, argb, false);
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
            // See the class note: renderItem reads the level for the model, so this is a crash rather
            // than a blank icon. Reported as "nothing drawn" so the caller falls back to a block.
            return false;
        }

        // Most items are one textured quad, and the pipeline is a great deal of work to draw one: see
        // IconPlan for the rule and for the accessors that decide it. The box is not part of it -- a
        // caller that wants less detail asks for it by asking for a smaller icon, which is the canvas's
        // own decision rather than this one's.
        BakedModel model = minecraft.getItemRenderer().getModel(stack, minecraft.level, minecraft.player, 0);
        IconPlan plan = IconPlan.of(factsOf(minecraft, model, stack));
        IconPlan.counted(plan);
        if (plan == IconPlan.FLAT) {
            // The flat path is **off by default**, and this is why: as written it drew nothing at all on
            // screen while returning {@code true}, so every node whose item took it rendered as an empty
            // panel — the icons simply vanished. The decision is still *counted* above, so the counters go
            // on reporting what would have been flat, and the drawing goes through the pipeline below,
            // which is the code that shipped for months.
            //
            // `IconPlan.arm()` is the experiment that identifies the mechanism, one arm per launch:
            // 1 the plain blit, 2 the blit that writes a per-vertex colour, 3 the blit with a flush either
            // side so it is submitted immediately, as `renderItem` gets for free. Whichever arm draws the
            // icons names the cause; none of them is the default, and the whole experiment goes away with
            // the reason recorded. See the notes in TESTING.md for what has already been ruled out.
            int arm = IconPlan.arm();
            if (arm == 1) {
                graphics.blit(boxX, boxY, 0, box, box, model.getParticleIcon());
                return true;
            }
            if (arm == 2) {
                graphics.blit(boxX, boxY, 0, box, box, model.getParticleIcon(), 1F, 1F, 1F, 1F);
                return true;
            }
            if (arm == 3) {
                graphics.flush();
                graphics.blit(boxX, boxY, 0, box, box, model.getParticleIcon());
                graphics.flush();
                return true;
            }
        }

        float scale = box / 16.0F;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(boxX, boxY, 0);
        // z stays 1. renderItem sets z to 150 + ... inside itself, so scaling z is how an item ends up
        // drawn behind the panel it is supposed to be on.
        pose.scale(scale, scale, 1F);
        graphics.renderItem(stack, 0, 0);
        pose.popPose();
        return true;
    }

    /**
     * What the plan decides from, read off the model — the whole of this file's knowledge about the item
     * pipeline, in one place.
     *
     * <h2>The tint question, asked the way the pipeline asks it</h2>
     *
     * <p>{@code BakedQuad.isTinted()} is <b>not</b> a question about colour, and reading it as one is what
     * kept every item on the pipeline: {@code ItemModelGenerator} passes the <i>layer number</i> as the tint
     * index, so every quad of every {@code item/generated} item carries index 0 and reads as tinted. A stick
     * is "tinted at index 0 with no colour". What decides is whether {@code ItemColors} has a colour for
     * this stack at that index — which is `ItemRenderer`'s own line,
     * `this.itemColors.getColor(itemStack, bakedquad.getTintIndex())`, and it answers −1 when there is none.
     *
     * <p>So the colour is asked for, and the two facts that need it live here rather than in the plan: the
     * plan takes booleans, which is what keeps it testable.
     *
     * <h2>And the sprite, which the first version of this missed</h2>
     *
     * <p>A blit draws the particle icon and nothing else, so a model whose quads do not all use that one
     * sprite would be drawn wrong: a two-layer item — a base plus an overlay — would lose the overlay. That
     * is not a tint, it is a missing layer, so it is its own gate.
     */
    private static IconPlan.Facts factsOf(Minecraft minecraft, BakedModel model, ItemStack stack) {
        List<BakedQuad> quads = model.getQuads(null, null, RandomSource.create(42L));
        TextureAtlasSprite icon = model.getParticleIcon();

        boolean tinted = false;
        boolean oneSprite = !quads.isEmpty();
        for (BakedQuad quad : quads) {
            // No early break: the sprite gate has to see every quad, and the list is a handful.
            if (!tinted && quad.isTinted()
                    && minecraft.itemColors.getColor(stack, quad.getTintIndex()) != -1) {
                tinted = true;
            }
            if (icon == null || quad.getSprite() != icon) {
                oneSprite = false;
            }
        }
        return new IconPlan.Facts(
                model.isCustomRenderer(),
                model.isGui3d(),
                model.usesBlockLight(),
                tinted,
                minecraft.getModelManager().getMissingModel() == model,
                icon != null,
                oneSprite);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code PlayerFaceRenderer} rather than a blit written here, and the shorter version is the
     * correct one: that class owns the face and hat rectangles and the scaling between them, and it is
     * the same code the tab list draws with. Hand-writing the two blits is how this was first done, and
     * it produced a solid orange square — the hat layer's hair, stretched, with the face nowhere. Two
     * numbers right and one overload wrong is exactly the kind of mistake a seam should not be making
     * twice.
     *
     * <p>{@code getInsecureSkin} rather than a lookup that fetches: this draws for whoever is in the
     * party, and a face that arrives a second late is a face that is not there when the row is. A player
     * the client has no skin for — an offline server, an unknown UUID — resolves to the default, which
     * is a face rather than a gap.
     *
     * <h2>The profile comes from the player entity when the client has one</h2>
     *
     * <p>And that is not a shortcut, it is the difference between the right skin and a default one. In a
     * single-player world the server is in offline mode, so the UUID a roster carries is derived from
     * the player's <i>name</i> — while the client's own account has an id, and a skin, keyed by
     * something else. Asking the skin cache about the server's UUID therefore finds nothing and answers
     * "Steve" for a player whose face is on screen two feet away. The entity the client is already
     * drawing carries the <b>real</b> profile, so it is asked first, and the id is only the fallback for
     * somebody this client cannot see.
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

        GameProfile profile = null;
        if (minecraft.level != null
                && minecraft.level.getPlayerByUUID(player) instanceof AbstractClientPlayer entity) {
            profile = entity.getGameProfile();
        }
        if (profile == null) {
            profile = new GameProfile(player, "");
        }

        PlayerSkin skin = minecraft.getSkinManager().getInsecureSkin(profile);
        PlayerFaceRenderer.draw(graphics, skin, boxX, boxY, box);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Flushed first, and deliberately: every other operation here leaves its work in a batch, so a
     * caller that has just drawn a panel has not written any pixels yet -- and the chain would soften a
     * framebuffer that does not contain it.
     *
     * <p>The three lines after the chain are the ones that matter, and each is a piece of state the
     * passes set for themselves: the target they rebound, the scissor they leave enabled, and the blend
     * they left in their own mode. Putting them back is what lets a caller blur <b>mid-frame</b> — see
     * the method's own note for the card that vanished when this did not.
     */
    @Override
    public boolean blur(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            // No world behind this screen, so there is nothing to soften and the chain would process a
            // panorama. Reported as "nothing drawn" so a caller can draw its own scrim instead.
            return false;
        }
        if (minecraft.options.getMenuBackgroundBlurriness() <= 0) {
            return false;
        }
        flush();
        minecraft.gameRenderer.processBlurEffect(partialTick);
        minecraft.getMainRenderTarget().bindWrite(false);
        RenderSystem.disableScissor();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
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
    public Optional<TextureSize> textureSize(ResourceLocation texture) {
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
    public long textureStamp(ResourceLocation texture) {
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
     * <h2>The tint path, and why it is this one</h2>
     *
     * <p>This version's {@code GuiGraphics} has {@code setColor(float, float, float, float)}, and it
     * is the colour state a blit actually reads: it writes the shader colour that the position-tex
     * shader multiplies into every vertex — checked against this project's own compiled artefact
     * rather than recalled, because a setter that only affected text or only affected fills would be
     * worse than none. So the tint is set, the blit drawn, and white put back in a {@code finally}:
     * the shader colour is global pipeline state, and leaving a tint behind would colour every later
     * draw in the frame, which fails far from its cause.
     *
     * <p>{@code setColor} flushes a managed batch when one is open, so this call belongs outside
     * {@link #batched} — the same layering rule the batch's own note states.
     */
    @Override
    public void scaled(ResourceLocation texture, int x, int y, int width, int height,
                       float u, float v, int sourceWidth, int sourceHeight,
                       int textureWidth, int textureHeight, int argb) {
        float alpha = ((argb >>> 24) & 0xFF) / 255F;
        float red = ((argb >> 16) & 0xFF) / 255F;
        float green = ((argb >> 8) & 0xFF) / 255F;
        float blue = (argb & 0xFF) / 255F;
        graphics.setColor(red, green, blue, alpha);
        try {
            graphics.blit(texture, x, y, width, height, u, v, sourceWidth, sourceHeight,
                    textureWidth, textureHeight);
        }
        finally {
            graphics.setColor(1F, 1F, 1F, 1F);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code blit} with the image's own size named as the texture size, which is the whole of it:
     * the shorter overload assumes a 256×256 sheet and would sample the wrong region of a 12-pixel
     * file, so both sizes are written out.
     */
    @Override
    public void texture(ResourceLocation texture, int x, int y, int width, int height) {
        graphics.blit(texture, x, y, width, height, 0F, 0F, width, height, width, height);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>The atlas, and why this class picks it rather than the caller</h2>
     *
     * <p>{@code InventoryMenu.BLOCK_ATLAS} is the sheet a model's textures are stitched into, whether the
     * model belongs to a block or to an item — so it is the one sheet that holds every sprite a picture
     * element could name. Which sheet to ask is a fact about this version's resource pipeline, which is
     * exactly the kind of fact this file exists to own.
     *
     * <p>No null guard on the sprite, and that is read rather than assumed: this version's
     * {@code TextureAtlas.getSprite} answers an unknown id with its own {@code missingSprite} and throws
     * only when the atlas is not initialized — which cannot be true while a screen is drawing, and which a
     * guard here could not fix anyway.
     */
    @Override
    public void sprite(ResourceLocation atlasSprite, int x, int y, int width, int height, int argb) {
        if (atlasSprite == null || width <= 0 || height <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        TextureAtlasSprite sprite = minecraft.getModelManager()
                .getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(atlasSprite);
        // The same tint path `scaled` uses, in the same order: set the shader colour, blit, put white back.
        // A sprite is drawn through the block atlas' own render type rather than through the gui one, and the
        // shader colour is the thing both read.
        float alpha = ((argb >>> 24) & 0xFF) / 255F;
        float red = ((argb >> 16) & 0xFF) / 255F;
        float green = ((argb >> 8) & 0xFF) / 255F;
        float blue = (argb & 0xFF) / 255F;
        graphics.setColor(red, green, blue, alpha);
        try {
            graphics.blit(x, y, 0, width, height, sprite);
        }
        finally {
            graphics.setColor(1F, 1F, 1F, 1F);
        }
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
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(pivotX, pivotY, 0F);
        pose.mulPose(Axis.ZP.rotationDegrees(degrees));
        pose.translate(-pivotX, -pivotY, 0F);
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                pose.popPose();
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
                graphics.disableScissor();
            }
        };
    }
}
