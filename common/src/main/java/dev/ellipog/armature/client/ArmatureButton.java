package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Tween;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * A themed button, for a UI that is not the vanilla one.
 *
 * <h2>Why not {@code Button}</h2>
 *
 * <p>Vanilla's button draws a 200×20 nine-sliced texture with vanilla's widget sprites. In a screen
 * with its own flat palette it looks like a piece of another program pasted into this one — which is
 * exactly what it is. Restyling it means overriding the drawing anyway, and at that point the texture
 * and the sprite machinery are along for the ride.
 *
 * <p>So this extends {@link AbstractWidget}, which supplies the input handling, focus, narration and
 * positioning, and draws everything itself. That is a real saving of work: hit testing, tab order,
 * keyboard activation and the narration system are all subtle and all already correct in the base.
 *
 * <p>One thing worth knowing about the base, since it decides the shape of {@link #mouseClicked}:
 * {@code AbstractWidget.onClick} fires on mouse <b>down</b>, not on release. A button that wants to
 * look pressed while the pointer is down has to override {@code mouseClicked} instead of using
 * {@code onClick}. The method comment says so with the source quoted.
 *
 * <h2>What it can be</h2>
 *
 * <p>Text, or text with a leading item icon. An icon is what makes a chapter list or a quest action
 * readable at a glance, and there is no other way to put one in a control. The icon is drawn at a
 * fixed offset and the text is centred in what is left, so a row of buttons with and without icons
 * still lines its labels up.
 *
 * <h2>Accent, selected and flat</h2>
 *
 * <p>{@link #accent(boolean)} marks the one control a screen is really about — a Done, a Confirm.
 * Used more than once per screen it stops meaning anything, which is why it is a flag rather than a
 * colour: {@link ArmatureControlStyle} decides what "accent" looks like, and there is one place to
 * change it.
 *
 * <p>{@link #selected(boolean)} marks the current one — the chapter being shown, the tab being read.
 * It is a <b>fill</b>, not the absence of one, and that distinction cost a round: the quest book's
 * chapter list used {@link #flat(boolean)} for its selected row, so the current chapter was the only
 * one drawn with no box at all and read as the missing or disabled one. Flat means "this is a label
 * that happens to be clickable"; it does not mean "this one is on".
 *
 * <p>{@link #flat(boolean)} drops the fill for a control that should read as a label — a link, a
 * section header. Nothing uses it at the time of writing, and it is kept because it is the right shape
 * for something a quest editor will want.
 *
 * <h2>Where the colours come from</h2>
 *
 * <p>Every state is resolved by {@link ArmatureControlStyle}, not here. That is deliberate and it is
 * the fix for a real drift: the preview that draws this UI outside the game used to carry its own copy
 * of these rules and drew a selected control differently from the way this class does. One description,
 * called by the button and printed by the tool.
 */
public class ArmatureButton extends AbstractWidget {

    /** Tooltip lines, or null. Drawn by the screen, since a widget cannot draw outside itself. */
    private List<String> tooltip;

    /**
     * How hovered this control looks, from 0 to 1, eased over {@link Tween#DEFAULT_MILLIS}.
     *
     * <h2>Why a control needs state to change colour</h2>
     *
     * <p>Because the alternative is a snap, and a cursor crossing a column of chapters snaps eight
     * times in a few hundred milliseconds — which reads as flicker rather than as response. Eased, the
     * same movement reads as the interface following the pointer.
     *
     * <h2>What this costs, and why it is the right trade</h2>
     *
     * <p>A field and one {@code retarget} per frame per visible control. The retarget is a comparison
     * and an assignment when nothing has changed, which is most frames, so the common path is two
     * branches. The window is a fixed one: a control's tween has no allocation, no list, and nothing
     * that grows.
     *
     * <p>Not {@code volatile} and not synchronised: every read and write is on the client thread,
     * because that is where a widget is drawn.
     */
    private final Tween hoverTween = Tween.settled(0F);

    /** What to do when pressed. Never null — an inert button is {@code active = false}. */
    private final Consumer<ArmatureButton> onPress;

    /** A 16×16 item drawn before the label. The size is the control's, not this. */
    private net.minecraft.world.item.ItemStack icon;

    private boolean accent;
    private boolean selected;
    private boolean flat;
    private boolean borderless;
    private int textColour = ArmatureTheme.title();

    /** Whether the pointer is down on this button. Held so the pressed state can be drawn. */
    private boolean held;

    public ArmatureButton(int x, int y, int width, int height, Component label,
                          Consumer<ArmatureButton> onPress) {
        super(x, y, width, height, label);
        this.onPress = onPress;
    }

    public ArmatureButton(int x, int y, int width, int height, Component label, Runnable onPress) {
        this(x, y, width, height, label, button -> onPress.run());
    }

    // ------------------------------------------------------------------
    // Configuration. All of these return this, so a control reads as one expression.
    // ------------------------------------------------------------------

    /** Mark this as the screen's primary action. Use on one control per screen. */
    public ArmatureButton accent(boolean value) {
        this.accent = value;
        return this;
    }

    /**
     * Mark this as the current one — the chapter being shown, the tab being read.
     *
     * <p>A fill of its own, and not the absence of one. See the class comment: using {@link #flat} for
     * this made the selected chapter the only row with no box, which reads as the broken one rather
     * than the active one.
     */
    public ArmatureButton selected(boolean value) {
        this.selected = value;
        return this;
    }

    /** No fill behind the text. For a control that should read as a link or a label. */
    public ArmatureButton flat(boolean value) {
        this.flat = value;
        return this;
    }

    /** Fill but no border. For a control that sits inside another filled area. */
    public ArmatureButton borderless(boolean value) {
        this.borderless = value;
        return this;
    }

    public ArmatureButton textColour(int colour) {
        this.textColour = colour;
        return this;
    }

    /**
     * A 16×16 item drawn before the label.
     *
     * <p>16 is the item's own size, not the slot's: the icon is scaled to the control's inner height,
     * so passing an item here does not decide how big it is drawn. See {@link ArmatureTheme#drawIcon}.
     */
    public ArmatureButton icon(net.minecraft.world.item.ItemStack stack) {
        this.icon = stack == null || stack.isEmpty() ? null : stack;
        return this;
    }

    public ArmatureButton icon(net.minecraft.world.item.Item item) {
        return icon(new net.minecraft.world.item.ItemStack(item));
    }

    public ArmatureButton tooltip(Component line) {
        return tooltip(List.of(line));
    }

    /**
     * Tooltip lines.
     *
     * <p>Stored rather than passed to {@code setTooltip}, because the base class's tooltip is wired to
     * vanilla's tooltip renderer and would draw in vanilla's style. The screen asks for
     * {@link #tooltip()} when the pointer is over this widget and draws it itself.
     *
     * <h2>Strings rather than {@code FormattedCharSequence}, and nothing was lost</h2>
     *
     * <p>These used to be {@code Component#getVisualOrderText}, which is a {@code FormattedCharSequence}
     * — a sequence of {@code (codepoint, Style)} pairs, which is what the renderer wants and a shape
     * the renderer seam cannot accept without taking {@code Style} with it. So they are plain strings
     * now, and the reason that costs nothing is worth stating rather than assuming: every tooltip in
     * both mods is built from {@code Component.literal}, so none of them carries a style to lose. If
     * one ever does, it will draw unstyled — visibly, not silently, and the fix is to give the seam a
     * styled-text type rather than to put {@code Style} back into the caller's hands.
     */
    public ArmatureButton tooltip(List<Component> lines) {
        this.tooltip = lines.isEmpty() ? null : lines.stream()
                .map(Component::getString)
                .toList();
        return this;
    }

    /** Which kind of control this is, for {@link ArmatureControlStyle}. Accent wins over selected. */
    private ArmatureControlStyle.Variant variant() {
        if (accent) {
            return ArmatureControlStyle.Variant.ACCENT;
        }
        if (selected) {
            return ArmatureControlStyle.Variant.SELECTED;
        }
        return flat ? ArmatureControlStyle.Variant.FLAT : ArmatureControlStyle.Variant.PLAIN;
    }

    /** The tooltip lines, or null. For the owning screen to draw. */
    public List<String> tooltip() {
        return tooltip;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The one forced signature in this class, and it is Minecraft's rather than a choice: the base
        // class hands over a GuiGraphics and there is no overload that does not. So this wraps and
        // delegates immediately, and the body — where every actual decision lives — never sees one.
        //
        // That is the whole shape of the seam at a call site: one line here, and everything below is
        // expressed in terms of fill, text and icon. `.utils/check_seam.py` counts these files, so a
        // third one cannot appear without somebody writing down why.
        draw(new GuiGraphicsRenderer(graphics));
    }

    /**
     * Draws this control, animating any hover it is part-way through.
     *
     * <p>Takes a renderer, so it is testable without a client and shares nothing with the version being
     * ported. The clock is read here rather than inside, so the animated overload below can be driven
     * from a test with time it chose.
     */
    public void draw(GuiRenderer renderer) {
        draw(renderer, net.minecraft.Util.getMillis());
    }

    /**
     * The same, at a time the caller supplies.
     *
     * <p>This is the one a test drives. An animation asserted by sleeping is flaky on a loaded machine
     * and slow always; asserting one by passing a larger number is neither.
     */
    public void draw(GuiRenderer renderer, long nowMillis) {
        if (!visible) {
            return;
        }

        // One source for the appearance, so this button and anything that draws it agree. The
        // precedence -- disabled, then selected, then held, then hovered -- is documented there.
        ArmatureControlStyle.Variant variant = variant();
        boolean hovered = isHoveredOrFocused();

        // Told where it is heading every frame, which is how the tween catches up when the pointer
        // arrives while the last transition is still running -- see Tween.retarget. Passing the state
        // rather than calling retarget only on a change means there is no "did I already know this"
        // bookkeeping here to get out of step with the widget's own idea of hover.
        hoverTween.retarget(hovered ? 1F : 0F, nowMillis);

        // Held is checked before the blend rather than inside it: a press has to be immediate, so it
        // snaps while the hover eases. See ArmatureControlStyle.fillAt for why.
        int fill = ArmatureControlStyle.fillAt(variant, active, held, hoverTween.value(nowMillis));
        int border = ArmatureControlStyle.edge(variant, active, held, hovered);

        if (ArmatureControlStyle.drawsBox(variant)) {
            renderer.fill(getX(), getY(), getX() + width, getY() + height, fill);
            if (!borderless) {
                ArmatureTheme.outline(renderer, getX(), getY(), width, height, border);
            }
        }

        // The style decides the label for the two variants it owns -- accent and selected -- because
        // those are the two whose fill it also chose, and a label must be picked against its own
        // background. Everywhere else the caller's own textColour wins, since a screen may have a
        // reason the style cannot know.
        int colour;
        if (!active) {
            colour = ArmatureTheme.blocked();
        }
        else if (variant == ArmatureControlStyle.Variant.ACCENT
                || variant == ArmatureControlStyle.Variant.SELECTED) {
            colour = ArmatureControlStyle.text(variant, true);
        }
        else {
            colour = textColour;
        }
        String label = getMessage().getString();

        // The icon is the inner height of the control, so it is the size of the space it is in rather
        // than a fixed 16px in a 20px button with 4px of padding somewhere. The slot the text starts
        // after is derived from that same number, so a row of controls with and without icons still
        // lines its labels up.
        int iconBox = Math.max(8, height - 6);
        int iconSlot = icon != null ? iconBox + 4 : 0;
        if (icon != null) {
            renderer.icon(icon, getX() + 3, getY() + (height - iconBox) / 2, iconBox);
        }

        int textLeft = getX() + iconSlot;
        int textWidth = width - iconSlot;

        // Truncated to the space there actually is, by pixel width rather than by character count. A
        // control cannot know how long its label will be -- a chapter title comes from the quest file
        // -- so without this a long label is drawn straight through the control's edge and reads as a
        // rendering fault rather than as a name that is simply too long.
        //
        // `Measure.truncate` rather than a font call, so the truncation rule is testable and lives
        // beside the wrap rule it is a counterpart of. Both answer "what fits in this width", one by
        // breaking a paragraph and one by cutting a line.
        String shown = Measure.truncate(label, Math.max(0, textWidth - 4),
                Measure.of(renderer::textWidth, renderer.lineHeight()));
        int textX = textLeft + (textWidth - renderer.textWidth(shown)) / 2;
        renderer.text(shown, textX, getY() + (height - 8) / 2, colour);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // The label is the narration. A screen reader should say what the button does, and what it
        // does is what it says -- a separate narration field would be one more thing to keep in step.
        defaultButtonNarrationText(output);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Press and release, rather than the base class's press-only.
     *
     * <h2>What the base class actually does</h2>
     *
     * <p>Worth writing down, because I got it wrong once and the mistake was invisible. From
     * {@code AbstractWidget}, verbatim:
     *
     * <pre>
     * mouseClicked(...) {
     *     if (active &amp;&amp; visible &amp;&amp; isValidClickButton(button) &amp;&amp; clicked(mx, my)) {
     *         playDownSound(...);
     *         onClick(mx, my);       // &lt;-- fires on PRESS
     *         return true;
     *     }
     *     return false;
     * }
     * mouseReleased(...) {
     *     if (isValidClickButton(button)) { onRelease(mx, my); return true; }
     *     return false;
     * }
     * </pre>
     *
     * <p>So {@code onClick} is a <i>press</i> callback, and overriding it to report a press is what
     * vanilla does — but it means the button cannot show a held state, because the action has already
     * happened by the time the button looks pressed. For a Submit button in a quest book that is
     * wrong: it should look pressed while the pointer is down and act when it comes up, and letting go
     * somewhere else should cancel.
     *
     * <p>So {@code mouseClicked} is overridden <b>without</b> calling super, and the action is
     * reported from {@link #mouseReleased} only if the release is still inside. The click sound is
     * played here by hand, because not calling super is what skips it.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0 || !clicked(mouseX, mouseY)) {
            return false;
        }
        held = true;
        playDownSound(Minecraft.getInstance().getSoundManager());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasHeld = held;
        held = false;

        if (wasHeld && active && visible && button == 0 && clicked(mouseX, mouseY)) {
            onPress.accept(this);
            return true;
        }
        // Swallowed even when the press is cancelled: the pointer was down on this control, so the
        // release belongs to it whether or not it did anything.
        return wasHeld;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        // Dragging off the button cancels the press, which is the visual feedback that says letting go
        // now will do nothing. The base class routes drags here from mouseDragged.
        held = clicked(mouseX, mouseY);
    }

    /** Whether the pointer is down on this button. For a caller that needs to know. */
    public boolean isHeld() {
        return held;
    }

    /** Position, by setter rather than by {@code setPosition}. */
    public ArmatureButton at(int x, int y) {
        setX(x);
        setY(y);
        return this;
    }
}
