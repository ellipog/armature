package dev.ellipog.armature.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

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
 * <h2>Accent and flat</h2>
 *
 * <p>{@link #accent(boolean)} marks the one control a screen is really about — a Done, a Confirm.
 * Used more than once per screen it stops meaning anything, which is why it is a flag rather than a
 * colour: the theme decides what "accent" looks like, and there is one place to change it.
 * {@link #flat(boolean)} drops the fill for a control that should read as a label — a section header,
 * a tab.
 */
public class ArmatureButton extends AbstractWidget {

    /** Tooltip lines, or null. Drawn by the screen, since a widget cannot draw outside itself. */
    private List<FormattedCharSequence> tooltip;

    /** What to do when pressed. Never null — an inert button is {@code active = false}. */
    private final Consumer<ArmatureButton> onPress;

    /** A 16×16 item drawn before the label. The size is the control's, not this. */
    private net.minecraft.world.item.ItemStack icon;

    private boolean accent;
    private boolean flat;
    private boolean borderless;
    private int textColour = ArmatureTheme.TITLE;

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
     */
    public ArmatureButton tooltip(List<Component> lines) {
        this.tooltip = lines.isEmpty() ? null : lines.stream()
                .map(Component::getVisualOrderText)
                .toList();
        return this;
    }

    /** The tooltip lines, or null. For the owning screen to draw. */
    public List<FormattedCharSequence> tooltip() {
        return tooltip;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }

        int fill;
        int border;
        if (!active) {
            fill = ArmatureTheme.CONTROL_DISABLED;
            border = ArmatureTheme.PANEL_EDGE;
        }
        else if (held) {
            fill = ArmatureTheme.CONTROL_HELD;
            border = accent ? ArmatureTheme.CONTROL_EDGE_ACCENT : ArmatureTheme.CONTROL_EDGE;
        }
        else if (isHoveredOrFocused()) {
            fill = accent ? ArmatureTheme.CONTROL_ACCENT : ArmatureTheme.CONTROL_HOVER;
            border = accent ? ArmatureTheme.CONTROL_EDGE_ACCENT : ArmatureTheme.CONTROL_EDGE;
        }
        else {
            fill = accent ? ArmatureTheme.CONTROL_ACCENT : ArmatureTheme.CONTROL;
            border = accent ? ArmatureTheme.CONTROL_EDGE_ACCENT : ArmatureTheme.CONTROL_EDGE;
        }

        if (!flat) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, fill);
            if (!borderless) {
                ArmatureTheme.outline(graphics, getX(), getY(), width, height, border);
            }
        }

        Font font = Minecraft.getInstance().font;
        int colour = active ? textColour : ArmatureTheme.BLOCKED;
        Component label = getMessage();

        // The icon is the inner height of the control, so it is the size of the space it is in rather
        // than a fixed 16px in a 20px button with 4px of padding somewhere. The slot the text starts
        // after is derived from that same number, so a row of controls with and without icons still
        // lines its labels up.
        int iconBox = Math.max(8, height - 6);
        int iconSlot = icon != null ? iconBox + 4 : 0;
        if (icon != null) {
            ArmatureTheme.drawIcon(graphics, icon, getX() + 3, getY() + (height - iconBox) / 2, iconBox);
        }

        int textLeft = getX() + iconSlot;
        int textWidth = width - iconSlot;

        // Truncated to the space there actually is, by pixel width rather than by character count. A
        // control cannot know how long its label will be -- a chapter title comes from the quest file
        // -- so without this a long label is drawn straight through the control's edge and reads as a
        // rendering fault rather than as a name that is simply too long.
        String shown = font.plainSubstrByWidth(label.getString(), Math.max(0, textWidth - 4));
        int textX = textLeft + (textWidth - font.width(shown)) / 2;
        graphics.drawString(font, shown, textX, getY() + (height - 8) / 2, colour, false);
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
