package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Tween;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;

/**
 * A themed on/off switch.
 *
 * <h2>Why a switch exists beside a button</h2>
 *
 * <p>A switch and a button say different things. A button says "do this"; a switch says "this is
 * currently on or off", and a player reads its position before reading its label. A settings row
 * drawn as an "On"/"Off" button makes the reader find the word every time and gives no sense that
 * the state is a property of the thing next to it — which is exactly what a party's two settings
 * are. The editor's stepper had a hand-drawn switch for the same reason; this is that shape made
 * reusable and themed.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not animated beyond its hover tween, and not draggable. A drag is a nicety a control this small
 * does not need, and a press that toggles is unambiguous. The knob snaps, because a half-slid knob
 * reads as a third state.
 *
 * <h2>The colours come from the same place as a button's</h2>
 *
 * <p>The selected fill is {@link ArmatureControlStyle}'s accent, resolved through the style rather
 * than written here — so a theme that recolours every accent control recolours this too.
 */
public class ArmatureSwitch extends AbstractWidget {

    /** What a switch is wide. Narrower than a button: the state is the shape, not a word. */
    public static final int WIDTH = 22;

    /** What a switch is tall. */
    public static final int HEIGHT = 12;

    /** Runs on every press, after the state has flipped. Never null. */
    private Runnable onToggle = () -> {
    };

    private boolean selected;
    private final Tween hoverTween = Tween.settled(0F);

    public ArmatureSwitch(int x, int y) {
        this(x, y, false);
    }

    public ArmatureSwitch(int x, int y, boolean selected) {
        super(x, y, WIDTH, HEIGHT, net.minecraft.network.chat.Component.empty());
        this.selected = selected;
    }

    /** Whether the switch reads as on. */
    public boolean selected() {
        return selected;
    }

    /** Sets the state without firing. For a rebuild, where the server's answer is the state. */
    public void setSelected(boolean value) {
        this.selected = value;
    }

    public ArmatureSwitch onToggle(Runnable handler) {
        this.onToggle = handler == null ? () -> {
        } : handler;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The one forced signature, wrapped and delegated exactly as ArmatureButton does — see the
        // seam note there. Everything below is expressed in fill and shape.
        draw(new GuiGraphicsRenderer(graphics), net.minecraft.Util.getMillis());
    }

    /** Draws this switch at the current time. The modal redraw pass and a preview both use it. */
    public void draw(GuiRenderer renderer) {
        draw(renderer, net.minecraft.Util.getMillis());
    }

    /** Draws this switch, taking the time so a test can drive the hover without sleeping. */
    public void draw(GuiRenderer renderer, long nowMillis) {
        if (!visible) {
            return;
        }
        hoverTween.retarget(isHoveredOrFocused() && active ? 1F : 0F, nowMillis);
        float hover = hoverTween.value(nowMillis);

        // The track is the recessed well; the knob is the accent when on, and faint when off — so the
        // two states differ in both position and colour, which is what makes the state readable at a
        // glance rather than only on close inspection.
        int trackFill = active ? ArmatureTheme.recessed() : ArmatureTheme.canvas();
        int trackEdge = ArmatureTheme.panelEdge();
        ArmatureTheme.panel(renderer, getX(), getY(), width, height, trackFill, trackEdge);

        int knob = height - 4;
        int knobX = selected ? getX() + width - knob - 2 : getX() + 2;
        int knobFill = !active
                ? ArmatureTheme.blocked()
                : selected
                        ? ArmatureControlStyle.fillAt(ArmatureControlStyle.Variant.ACCENT, true, false, hover)
                        : ArmatureTheme.raised();
        renderer.fill(knobX, getY() + 2, knobX + knob, getY() + 2 + knob, knobFill);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // The state flips here and the handler is told after, so a handler that reads `selected()`
        // reads the new answer — the order every caller expects and the one that is wrong half the
        // time when it is the other way round.
        selected = !selected;
        onToggle.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // Vanilla's own on/off words, and that is deliberate for a library: Armature ships no language
        // file of its own -- its other strings are the data files' -- and two keys of its own would be
        // the only untranslated strings in a mod's whole UI. A game that has localized vanilla already
        // has these.
        output.add(NarratedElementType.TITLE, net.minecraft.network.chat.Component.translatable(
                selected ? "options.on" : "options.off"));
    }
}
