package dev.ellipog.armature.client.render;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
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

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    @Override
    public void text(String text, int x, int y, int argb) {
        // The shadow argument is always false, and that is not a simplification: a drop shadow is what
        // makes vanilla's text legible against vanilla's background, and this UI draws an opaque
        // backdrop behind every label instead. Both at once reads as a smudged label.
        graphics.drawString(font(), text, x, y, argb, false);
    }

    @Override
    public int textWidth(String text) {
        return font().width(text);
    }

    @Override
    public int lineHeight() {
        return font().lineHeight;
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
