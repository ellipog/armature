package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slider;
import dev.ellipog.armature.client.ui.kit.Tween;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * A themed slider: a message on the left, a track on the right, and a thumb you drag.
 *
 * <h2>Why a slider exists beside a stepper</h2>
 *
 * <p>A stepper is right for a value with a handful of positions you name — a radius of 0 to 12 is
 * arrows and a number. A slider is right for a value that is a <b>position on a line</b>: text size
 * from 100% to 150% is one gesture to anywhere, and the thumb's place says where you are before you
 * read the number. It is also the shape a player already knows from every settings screen.
 *
 * <h2>Where the arithmetic lives</h2>
 *
 * <p>In {@link Slider}, game-free and asserted: snapping, the fraction, both ends, a pointer past
 * either edge. This class is the rectangle, the three fills and the input — and it reads the model
 * rather than keeping a second copy of the value, so the drawn thumb and the value a press asks for
 * cannot disagree.
 *
 * <h2>The message is the caller's, and it updates</h2>
 *
 * <p>It draws {@link #getMessage()}, and a caller whose label carries the value sets it as the value
 * moves — no rebuild, because rebuilding the screen mid-drag would destroy the widget being dragged.
 */
public class ArmatureSlider extends AbstractWidget {

    /** What a slider is tall. The same as a switch: one row of a settings list. */
    public static final int HEIGHT = 12;

    /** The narrowest track. The message gets what is left, and is truncated to it. */
    public static final int MIN_TRACK = 60;

    /** Between the message and the track. */
    private static final int TRACK_GAP = 8;

    /** The track's own thickness, and the gap between it and the message's line. */
    private static final int TRACK = 4;

    /** The thumb's width, which is also what it travels inside the track. */
    private static final int THUMB = 6;

    private Slider slider;
    private Runnable onChange = () -> {
    };
    private final Tween hoverTween = Tween.settled(0F);

    public ArmatureSlider(int x, int y, int width, double min, double max, double step, double value) {
        super(x, y, width, HEIGHT, Component.empty());
        this.slider = new Slider(min, max, step, value);
    }

    /** The value in force. */
    public double value() {
        return slider.value();
    }

    /** Sets the value without firing. For a rebuild, where the stored setting is the state. */
    public void setValue(double raw) {
        slider = slider.withValue(raw);
    }

    /** Sets the message — a caller whose label carries the value updates it as the value moves. */
    public ArmatureSlider label(Component message) {
        setMessage(message == null ? Component.empty() : message);
        return this;
    }

    /** Runs on every change: a drag, a click, a key. Never null. */
    public ArmatureSlider onChange(Runnable handler) {
        this.onChange = handler == null ? () -> {
        } : handler;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The one forced signature, wrapped and delegated exactly as ArmatureSwitch does — see the
        // seam note there, including why the drawing is batched: a slider is a track, a fill and a
        // handle, each a run of rows, and unmanaged each row's fill is its own submission.
        GuiRenderer renderer = new GuiGraphicsRenderer(graphics);
        long now = net.minecraft.Util.getMillis();
        renderer.batched(() -> {
            draw(renderer, now);
            return null;
        });
    }

    /** Draws this slider at the current time. The modal redraw pass and a preview both use it. */
    public void draw(GuiRenderer renderer) {
        draw(renderer, net.minecraft.Util.getMillis());
    }

    /** Draws this slider, taking the time so a test can drive the hover without sleeping. */
    public void draw(GuiRenderer renderer, long nowMillis) {
        if (!visible) {
            return;
        }
        hoverTween.retarget(isHoveredOrFocused() && active ? 1F : 0F, nowMillis);
        float hover = hoverTween.value(nowMillis);

        int trackWidth = trackWidth();
        int trackLeft = getX() + width - trackWidth;
        int trackTop = getY() + (height - TRACK) / 2;

        // The message, truncated to the room the track left it.
        String label = getMessage().getString();
        int labelRoom = Math.max(0, trackLeft - TRACK_GAP - getX());
        if (labelRoom > 0 && !label.isEmpty()) {
            Measure measure = Measure.of(renderer::textWidth, renderer.lineHeight());
            renderer.text(Measure.truncate(label, labelRoom, measure), getX(),
                    getY() + (height - renderer.lineHeight()) / 2,
                    ArmatureControlStyle.text(ArmatureControlStyle.Variant.PLAIN, active));
        }

        // The well and its one-pixel border, the part the thumb has travelled, and the thumb itself.
        renderer.fill(trackLeft, trackTop, trackLeft + trackWidth, trackTop + TRACK,
                ArmatureTheme.recessed());
        ArmatureTheme.outline(renderer, trackLeft - 1, trackTop - 1, trackWidth + 2, TRACK + 2,
                ArmatureTheme.panelEdge());

        int thumbX = thumbLeft();
        int fill = ArmatureControlStyle.fillAt(ArmatureControlStyle.Variant.ACCENT, active, false, hover);
        if (thumbX > trackLeft) {
            renderer.fill(trackLeft, trackTop, thumbX, trackTop + TRACK, fill);
        }
        renderer.fill(thumbX, getY() + 2, thumbX + THUMB, getY() + height - 2, fill);
    }

    /** The track's width: half the control, never narrower than {@link #MIN_TRACK}. */
    private int trackWidth() {
        return Math.max(MIN_TRACK, width / 2);
    }

    /** The thumb's left edge, from the value's fraction of the distance it can travel. */
    private int thumbLeft() {
        int trackLeft = getX() + width - trackWidth();
        int travel = Math.max(0, trackWidth() - THUMB);
        return trackLeft + (int) Math.round(slider.fraction() * travel);
    }

    /**
     * The value a pointer asks for.
     *
     * <p>Read against the thumb's own travel — the track less the thumb's width — because that is the
     * span the thumb moves over: a pointer at the track's left edge means the minimum, not a value
     * half a thumb's width into the range.
     */
    private double valueAt(double mouseX) {
        int trackLeft = getX() + width - trackWidth();
        int travel = Math.max(1, trackWidth() - THUMB);
        return slider.valueAt((mouseX - (trackLeft + THUMB / 2.0D)) / travel);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        setFromPointer(mouseX);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        setFromPointer(mouseX);
    }

    private void setFromPointer(double mouseX) {
        double next = valueAt(mouseX);
        if (next != slider.value()) {
            slider = slider.withValue(next);
            onChange.run();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Arrows move by one step — the smallest move a drag can make — so the keyboard and the mouse
        // reach exactly the same values.
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_DOWN) {
            step(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP) {
            step(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void step(int direction) {
        double next = slider.snap(slider.value() + direction * slider.step());
        if (next != slider.value()) {
            slider = slider.withValue(next);
            onChange.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // The message, which a caller keeps current — the value is in it.
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
