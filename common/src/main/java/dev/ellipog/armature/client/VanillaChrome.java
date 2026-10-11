package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Vanilla Minecraft's own chrome: the sprites and bevels a {@code VANILLA}-chromed theme draws with.
 *
 * <h2>Why this is a class of constants and paint routines, not a theme</h2>
 *
 * <p>A theme is data a file can name — forty-three colours, a radius, a motion, a background,
 * a chrome — and none of that can say "draw {@code widget/button}". The sprite ids live here instead, beside the
 * three paint routines that are the only things allowed to use them: panels, buttons and tooltips.
 * Everything else in a vanilla look — the greys, the dark inks, the dirt canvas — already travels
 * as theme data and needs no code at all.
 *
 * <h2>Sampled, not recalled</h2>
 *
 * <p>Every id below names a file under {@code assets/minecraft/textures/gui} in 1.21.1, verified
 * against its client jar rather than a newer version's assets: the panel bevel is
 * {@code container/inventory.png} (fill {@code #C6C6C6}, white top/left, {@code #373737} and
 * black bottom/right) and the buttons are {@code sprites/widget/button[_highlighted|_disabled]}
 * (200×20, nine-sliced with a 3-pixel border). If vanilla moves one of these, the sprite lookup
 * is what fails — loudly, on screen — rather than a hex going quietly stale.
 *
 * <h2>No tooltip sprites: 1.21.1 has none</h2>
 *
 * <p>Its client jar ships {@code widget/*} only; {@code tooltip/background} and
 * {@code tooltip/frame} arrived in a later version, so an id for one is the missing-texture
 * checkerboard here. The vanilla box is instead drawn the way 1.21.1 draws it — see
 * {@link #tooltipBox} — from the colours its {@code TooltipRenderUtil} paints, read from its
 * bytecode rather than recalled.
 */
public final class VanillaChrome {

    private VanillaChrome() {
    }

    /** The id prefix every GUI sprite shares. */
    private static final String NAMESPACE = "minecraft:";

    private static ResourceLocation sprite(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse(NAMESPACE + path), path);
    }

    /** A stone button at rest. Nine-sliced with a 3-pixel border. */
    public static final ResourceLocation BUTTON = sprite("widget/button");

    /** A stone button that cannot be used. */
    public static final ResourceLocation BUTTON_DISABLED = sprite("widget/button_disabled");

    /** A stone button under the pointer, wearing its white border. */
    public static final ResourceLocation BUTTON_HIGHLIGHTED = sprite("widget/button_highlighted");

    /** Inventory fill, from {@code container/inventory.png}. */
    public static final int PANEL = 0xFFC6C6C6;

    /** The bevel's lit edges: top and left, one pixel. */
    public static final int BEVEL_LIGHT = 0xFFFFFFFF;

    /** The bevel's shadowed edges: bottom and right, one pixel inside the black. */
    public static final int BEVEL_DARK = 0xFF373737;

    /** The outermost pixel of a container border. */
    public static final int BEVEL_OUTER = 0xFF000000;

    /** Button and tooltip ink. Vanilla draws both with its shadow. */
    public static final int FLOATING_TEXT = 0xFFFFFFFF;

    /** Button ink when the control cannot be used. */
    public static final int DISABLED_TEXT = 0xFFA0A0A0;

    /**
     * A selected control's ink: vanilla's formatting yellow.
     *
     * <p>Vanilla has no selected button sprite, so the sprite stays hover-driven while the label
     * carries the state instead — plain idle is stone and white, plain hovered adds the white
     * border, selected turns the ink yellow. Every combination still reads.
     */
    public static final int SELECTED_TEXT = 0xFFFFFF55;

    /**
     * A vanilla tooltip's fill, from {@code TooltipRenderUtil}'s bytecode rather than recalled.
     */
    public static final int TOOLTIP_FILL = 0xF0100010;

    /**
     * The frame's crown: the gradient's top, opaque as a one-pixel band.
     *
     * <p>Vanilla draws the frame as a top-to-bottom gradient ({@code 0x505000FF} to
     * {@code 0x5028007F}); with no gradient on the seam the two ends become two bands, split at
     * the middle. The alpha is vanilla's own, so the box reads the same over dirt and over black.
     */
    public static final int TOOLTIP_FRAME_TOP = 0x505000FF;

    /** The frame's foot: the gradient's bottom end. */
    public static final int TOOLTIP_FRAME_BOTTOM = 0x5028007F;

    /**
     * A vanilla tooltip's box: near-black fill, two-tone purple frame, always square.
     *
     * <p>The frame is vanilla's vertical gradient drawn as two bands — crown over the top half,
     * dark foot under the bottom — because fills cannot graduate. A box too small for a frame
     * degrades to its fill rather than drawing inverted coordinates: a one-line tooltip clipped
     * by a screen edge is a state, not an error.
     */
    public static void tooltipBox(GuiRenderer renderer, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int right = left + width;
        int bottom = top + height;
        renderer.fill(left, top, right, bottom, TOOLTIP_FILL);
        if (width < 2 || height < 2) {
            return;
        }
        int mid = top + height / 2;
        renderer.fill(left, top, right, top + 1, TOOLTIP_FRAME_TOP);
        renderer.fill(left, bottom - 1, right, bottom, TOOLTIP_FRAME_BOTTOM);
        renderer.fill(left, top + 1, left + 1, mid, TOOLTIP_FRAME_TOP);
        renderer.fill(right - 1, top + 1, right, mid, TOOLTIP_FRAME_TOP);
        renderer.fill(left, mid, left + 1, bottom - 1, TOOLTIP_FRAME_BOTTOM);
        renderer.fill(right - 1, mid, right, bottom - 1, TOOLTIP_FRAME_BOTTOM);
    }

    /**
     * Whether vanilla chrome is in force: the palette on top of the scope stack asks for sprites.
     *
     * <p>Reads {@code current()} rather than {@code chrome()} so a chapter scoped to vanilla draws
     * vanilla panels while the book around it stays flat, and the other way round.
     */
    public static boolean inForce() {
        return ArmatureTheme.current().chrome() == dev.ellipog.armature.client.ui.Theme.Chrome.VANILLA;
    }

    /**
     * The button sprite for a state: disabled when the control cannot be used, highlighted
     * under the pointer or while held, plain otherwise.
     *
     * <p>Held shares highlighted because vanilla has no pressed sprite of its own — a held
     * vanilla button is a hovered one that has not let go yet.
     */
    public static ResourceLocation buttonSprite(boolean active, boolean hoveredOrHeld) {
        if (!active) {
            return BUTTON_DISABLED;
        }
        return hoveredOrHeld ? BUTTON_HIGHLIGHTED : BUTTON;
    }

    /**
     * An inventory-style bevel panel: a black outer pixel, white top and left inside it, dark
     * bottom and right, and the caller's fill in the middle. Always square — vanilla has no
     * rounded corners, so the theme's radius never reaches here.
     *
     * <p>The fill is the caller's rather than {@link #PANEL} so a tinted panel — a selection
     * chip, a deliberately foreign border — keeps the colour it asked for while still reading
     * as a container. A box too small for a bevel degrades to flat fills rather than drawing
     * nothing: a collapsed panel is a state, not an error.
     */
    public static void panel(GuiRenderer renderer, int left, int top, int width, int height, int fill) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width < 3 || height < 3) {
            renderer.fill(left, top, left + width, top + height, fill);
            return;
        }
        renderer.fill(left, top, left + width, top + height, BEVEL_OUTER);
        renderer.fill(left + 1, top + 1, left + width - 1, top + height - 1, fill);
        renderer.fill(left + 1, top + 1, left + width - 1, top + 2, BEVEL_LIGHT);
        renderer.fill(left + 1, top + 1, left + 2, top + height - 1, BEVEL_LIGHT);
        renderer.fill(left + 1, top + height - 2, left + width - 1, top + height - 1, BEVEL_DARK);
        renderer.fill(left + width - 2, top + 1, left + width - 1, top + height - 1, BEVEL_DARK);
    }
}
