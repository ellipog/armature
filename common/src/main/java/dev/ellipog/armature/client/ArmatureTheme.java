package dev.ellipog.armature.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Armature's palette and its drawing helpers, in one place.
 *
 * <h2>Every colour is eight digits</h2>
 *
 * <p>{@code 0xAARRGGBB}, alpha included, always. On 1.21.1 a colour written without alpha happens to
 * come out opaque anyway; from 1.21.6 it does not. So writing the alpha is not tidiness — it is the
 * difference between this UI porting to the next version and every colour in it needing a rewrite.
 *
 * <h2>Separation, not subtlety</h2>
 *
 * <p>This is the mistake worth reading about, because it cost a screenshot. The first version of this
 * palette spread its four surfaces over <b>ten units out of 255</b>:
 *
 * <pre>
 * PANEL    0x16161C    22, 22, 28
 * RECESSED 0x101015    16, 16, 21
 * CANVAS   0x0C0C11    12, 12, 17
 * </pre>
 *
 * <p>On paper those are four shades. On a real monitor, through a real gamma curve, they are one
 * colour — and the screenshot was a black rectangle with some squares on it. Which then reads as
 * "the panel is not being drawn", so you go looking for a layout bug that does not exist.
 *
 * <p>The steps below are 10 to 16 units at the dark end, where the eye is least sensitive, and wider
 * further up. They are deliberately larger than they look right on paper, because they have to
 * survive being displayed.
 *
 * <p>The order is load-bearing and is worth stating plainly: {@link #CANVAS} is the deepest,
 * {@link #RECESSED} sits on that, {@link #PANEL} frames both, {@link #RAISED} is a strip or a header
 * above the panel, and {@link #CONTROL} is above all of them. Anything that draws a surface out of
 * that order will look like a hole.
 *
 * <h2>Why named constants rather than a theme object</h2>
 *
 * <p>A theme is the right shape for something a player or a pack can switch. Nothing can yet, and a
 * theme object with one instance is a layer of indirection around thirty numbers. When there are two
 * themes there will be a theme interface, and these constants become its first implementation — which
 * is a small change made at the moment there is a reason for it.
 *
 * <h2>Why the widget and the screen share it</h2>
 *
 * <p>Because they have to agree. A button drawn from its own private palette drifts away from the panel
 * behind it the first time either is adjusted, and the result looks like a mistake rather than a
 * decision. One palette means one place to change.
 */
public final class ArmatureTheme {

    private ArmatureTheme() {
    }

    // --- surfaces, deepest first --------------------------------------------

    /** The dim over the world, behind a screen's panel. Deliberately not fully opaque. */
    public static final int DIM = 0xB80A0A0D;

    /** The canvas a graph or a map sits on — the deepest surface there is. */
    public static final int CANVAS = 0xFF0A0A0E;

    /** A recessed area inside a panel — a sidebar, a list. */
    public static final int RECESSED = 0xFF191920;

    /** A screen's main panel — the frame everything else sits inside. */
    public static final int PANEL = 0xFF24242E;

    /**
     * A raised area inside a panel — a header, a strip.
     *
     * <p>48,48,60. Deliberately well below {@link #CONTROL}, because a control sits <b>on</b> a raised
     * surface and the two must not be the same value.
     */
    public static final int RAISED = 0xFF30303C;

    /** A panel's border, and the divider between two surfaces. Bright enough to read as a line. */
    public static final int PANEL_EDGE = 0xFF46465A;

    // --- controls ------------------------------------------------------------

    /**
     * A control at rest: 62,62,76.
     *
     * <h2>The trap here, which cost a reading of the file to notice</h2>
     *
     * <p>The first version of this palette had {@code CONTROL 0x34343F} and {@code RAISED 0x31313D} —
     * <b>three units apart</b>. The strip is a RAISED surface and the buttons on it are CONTROLS, so
     * an unaccented button on the strip would have been drawn in very nearly its own background. The
     * accent border would have saved the one accent button; every plain one would have looked like the
     * strip's own text.
     *
     * <p>It is the same mistake as the two colliding buttons and the icon that did not scale with its
     * box, from a third direction: <b>two values that have to differ, chosen independently.</b> Hence
     * the arithmetic in each doc comment — the numbers are here so the next person can check the gaps
     * without opening an image editor.
     */
    public static final int CONTROL = 0xFF3E3E4C;

    /** A control with the pointer over it. */
    public static final int CONTROL_HOVER = 0xFF4A4A5A;

    /** A control being held down. */
    public static final int CONTROL_HELD = 0xFF565668;

    /**
     * A control that cannot be used: 42,42,52.
     *
     * <p>Recedes rather than turning red — nothing has gone wrong, it just cannot be used yet. Above
     * {@link #PANEL} so it still reads as a control that is present, which is what "not yet" means;
     * a disabled button that vanishes reads as a missing feature.
     */
    public static final int CONTROL_DISABLED = 0xFF2A2A34;

    /** A control's border. */
    public static final int CONTROL_EDGE = 0xFF4C4C62;

    /** A control's border when it is the one that matters. */
    public static final int CONTROL_EDGE_ACCENT = 0xFF6A9AC8;

    /** A control's border at its brightest, for a tooltip frame or a highlight. */
    public static final int CONTROL_EDGE_BRIGHT = 0xFF5C5C74;

    /** The fill for a control the action is about — used sparingly, one per screen. */
    public static final int CONTROL_ACCENT = 0xFF33597F;

    // --- text ----------------------------------------------------------------

    public static final int TITLE = 0xFFFFFFFF;
    public static final int BODY = 0xFFC6C6D4;
    public static final int FAINT = 0xFF80808F;
    public static final int HEADING = 0xFF9E9EB0;

    /** Behind a label drawn over something else, so text never sits directly on a line or an icon. */
    public static final int LABEL_BACKDROP = 0xF00A0A0E;

    // --- state ---------------------------------------------------------------

    /** Available, not started. */
    public static final int AVAILABLE = 0xFF7FB4E8;

    /** Started, not finished. */
    public static final int IN_PROGRESS = 0xFFE8C868;

    /** Done. */
    public static final int COMPLETE = 0xFF86CE8A;

    /** Cannot be done yet. Legible as a border, not as a fog. */
    public static final int BLOCKED = 0xFF66666F;

    // --- graph ---------------------------------------------------------------

    /** A node's fill, so an item with transparent corners still sits on something. */
    public static final int NODE_FILL = 0xFF2E2E3A;

    /** A node's border when the quest cannot be started. */
    public static final int NODE_EDGE_BLOCKED = 0xFF43434F;

    /**
     * A wash over a locked node's icon.
     *
     * <p>This replaced a chip with a ✖ drawn in the node's bottom-right corner. At node scale that chip
     * was a black square pasted over the artwork — the worst thing in the screenshot, and it hid the
     * one thing the icon is on the canvas to show. Dimming what is already there says "not yet" without
     * destroying it.
     */
    public static final int NODE_DIM = 0x9C000000;

    /** A wash over a completed node's icon, so "done" reads at a glance and still shows the item. */
    public static final int NODE_DONE_WASH = 0x3086CE8A;

    public static final int LINE = 0xFF505064;
    public static final int LINE_DONE = 0xFF5F8A62;
    public static final int SELECTED_RING = 0xFFFFFFFF;
    public static final int HOVER_RING = 0x80FFFFFF;

    // -------------------------------------------------------------------------
    // Drawing helpers
    // -------------------------------------------------------------------------

    /**
     * A one-pixel rectangle outline, as four fills.
     *
     * <p>Provided because {@code GuiGraphics} has no stroke, so every outline in this UI is four
     * {@code fill} calls, and writing that out at each site is four chances to get one edge wrong.
     * The width is not a parameter: nothing in this UI wants a 2px border, and a width argument
     * invites one.
     */
    public static void outline(GuiGraphics graphics, int left, int top, int width, int height, int colour) {
        graphics.fill(left, top, left + width, top + 1, colour);
        graphics.fill(left, top + height - 1, left + width, top + height, colour);
        graphics.fill(left, top, left + 1, top + height, colour);
        graphics.fill(left + width - 1, top, left + width, top + height, colour);
    }

    /**
     * A filled rectangle with a one-pixel border, as five fills.
     *
     * <p>The commonest thing this UI draws, and the reason it is here rather than in each caller: the
     * fill has to come first and the border four times over, and getting that order wrong produces a
     * border that the fill then covers.
     */
    public static void panel(GuiGraphics graphics, int left, int top, int width, int height,
                             int fill, int border) {
        graphics.fill(left, top, left + width, top + height, fill);
        outline(graphics, left, top, width, height, border);
    }

    /**
     * A non-rectangular shape, described as the horizontal extent of each row.
     *
     * <p>Deliberately one method and no more: a shape is a lookup from a row to a span, and everything
     * else — filling it, stroking it, hit-testing it — is derived from that one answer. {@code tasked}
     * implements this with {@code QuestShape}, which is where the actual geometry lives; Armature
     * cannot know about it, and should not.
     */
    @FunctionalInterface
    public interface RowSpans {

        /**
         * The extent of the shape on {@code row} of a {@code size}-pixel square.
         *
         * @return {@code {from, to}}, {@code from} inclusive and {@code to} exclusive, or {@code null}
         *     for a row outside the shape
         */
        int[] span(int row, int size);
    }

    /**
     * Fills a shape that is not a rectangle, one horizontal band at a time.
     *
     * <h2>Why bands rather than rows</h2>
     *
     * <p>Because the number of {@code fill} calls is the cost. Each one appends four vertices and may
     * flush the batch, so a circle drawn a row at a time is 48 primitives for one node and the same
     * again for its border — and a chapter can show thirty nodes. Adjacent rows almost always have an
     * identical span, so they are merged: a rounded rectangle becomes three fills instead of 48, and a
     * circle about twenty-four instead of 96. The result is pixel-identical; only the call count
     * changes.
     *
     * <p>{@code null} from the lookup skips the row rather than stopping — a shape with a genuine hole
     * in it is not something any of these are, but a lookup that ran off the end should not truncate
     * the bottom half of a node.
     */
    public static void fillShape(GuiGraphics graphics, int x, int y, int size, int colour,
                                 RowSpans spans) {
        if (size <= 0) {
            return;
        }
        int row = 0;
        while (row < size) {
            int[] span = spans.span(row, size);
            if (span == null) {
                row++;
                continue;
            }

            int end = row + 1;
            while (end < size) {
                int[] next = spans.span(end, size);
                if (next == null || next[0] != span[0] || next[1] != span[1]) {
                    break;
                }
                end++;
            }

            graphics.fill(x + span[0], y + row, x + span[1], y + end, colour);
            row = end;
        }
    }

    /**
     * A shape's outline: the shape in {@code border}, with a one-pixel-smaller copy of itself in
     * {@code fill} inset by one.
     *
     * <p>Compositing rather than stroking. Stroking a per-row span means two more fills per band and
     * needs the top and bottom caps special-cased, where drawing the shape twice is one call each and
     * is obviously the same shape — which is the property that matters, since a border that does not
     * follow the fill is exactly the class of bug this whole file is about.
     */
    public static void shapePanel(GuiGraphics graphics, int x, int y, int size, int fill, int border,
                                  RowSpans spans) {
        fillShape(graphics, x, y, size, border, spans);
        if (size > 2) {
            fillShape(graphics, x + 1, y + 1, size - 2, fill, spans);
        }
    }

    /**
     * Draws an item so that it exactly fills a box of {@code box} pixels.
     *
     * <h2>How, and why it is not obvious</h2>
     *
     * <p>{@code GuiGraphics.renderItem(stack, x, y)} draws at a fixed size — 16 screen pixels — because
     * it translates to {@code (x + 8, y + 8, 150)} and then scales by {@code (16, -16, 16)}. There is no
     * size parameter, so the only way to draw an item bigger or smaller is to change the transform it
     * inherits.
     *
     * <p>And it <b>does</b> inherit one: it pushes onto the same {@code PoseStack} that
     * {@link GuiGraphics#pose()} hands out. So translating to the box's corner and scaling by
     * {@code box / 16} makes the item's own internal 16-unit scale land exactly on {@code box}.
     * Verified by reading {@code GuiGraphics.renderItem} in 1.21.1 rather than assumed, because "does
     * the parent transform apply" is precisely the sort of thing that is wrong half the time.
     *
     * <h2>Why it lives here, and not in each screen</h2>
     *
     * <p>Because a box and the item inside it must be <b>one</b> number, and this is the only function
     * that knows how to make that true. The quest book had two live bugs from getting it wrong in two
     * places: a node whose box scaled with the zoom while its item stayed at 16px, and a 13px row pitch
     * around a 16px icon so consecutive rows overlapped. Both are the same mistake — sizing a box and
     * its contents independently — and both are impossible when there is one function and everything
     * uses it. {@link ArmatureButton} and the quest book both do.
     *
     * @return whether anything was drawn, so a caller can fall back to a plain block when the client
     *     cannot resolve the item — an empty box in a row of icons reads as a bug, not as a fallback.
     */
    public static boolean drawIcon(GuiGraphics graphics, ItemStack stack, int boxX, int boxY, int box) {
        if (stack == null || stack.isEmpty() || box <= 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            // renderItem reaches through minecraft.player and minecraft.level for the model, so a null
            // level is a crash rather than a blank icon. One guard here, rather than at every call site.
            return false;
        }

        float scale = box / 16.0F;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(boxX, boxY, 0);
        // z stays 1: renderItem sets z to 150 + ... inside itself, so scaling z is how an item ends up
        // drawn behind the panel it is supposed to be on.
        pose.scale(scale, scale, 1F);
        graphics.renderItem(stack, 0, 0);
        pose.popPose();
        return true;
    }
}
