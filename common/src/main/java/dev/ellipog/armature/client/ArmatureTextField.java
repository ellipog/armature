package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.TextField;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.util.function.Consumer;

/**
 * A one-line text box: the kit's {@link TextField} with a widget around it.
 *
 * <h2>What is the model's and what is this</h2>
 *
 * <p>Everything worth being wrong about — where the caret is, what a backspace deletes at position three,
 * what a click at x pixels means — is {@code kit.TextField}'s, and it is asserted without a client. This
 * is the half that cannot be: keystrokes, focus, the clock the caret blinks on, and the drawing. It is thin
 * on purpose, because the alternative is the class of mistake this codebase has paid for twice — a second
 * implementation of {@code AbstractWidget}'s own logic, which a screen can only exercise by running.
 *
 * <h2>What it deliberately does not have</h2>
 *
 * <p><b>Selection.</b> The model has no anchor, so there is no drag to select, no shift-arrow, and Ctrl+A
 * is not offered rather than offered and broken. Ctrl+C and Ctrl+V copy and paste the whole value, which is
 * what a hex field wants and what a selection would be for — a caller wanting to edit part of a string in
 * place wants a different class, not a flag on this one.
 *
 * <h2>The one forced signature</h2>
 *
 * <p>{@code renderWidget} takes a {@code GuiGraphics} and is abstract; the override wraps it in one line
 * and hands it to {@link GuiRenderer}, exactly as {@code ArmatureButton} does. {@code check_seam.py} names
 * this file for that reason and no other.
 */
public final class ArmatureTextField extends AbstractWidget {

    /** What the field holds and where the caret is. */
    private final TextField model;

    /** Called with the whole value when the player presses Enter or the field loses focus. */
    private Consumer<String> onSubmit = value -> {
    };

    /** The colour of the box, and of its border. */
    private int textColour = 0xFFFFFFFF;
    private int fillColour = 0xFF101014;
    private int edgeColour = 0xFF3A3A46;

    public ArmatureTextField(int x, int y, int width, int height, String initial) {
        super(x, y, width, height, Component.empty());
        this.model = TextField.of(64).setValue(initial == null ? "" : initial);
        this.active = true;
    }

    /** The value, as it stands. */
    public String value() {
        return model.value();
    }

    public ArmatureTextField setValue(String value) {
        model.setValue(value);
        return this;
    }

    /** What to do with the value when it is finished with. See {@link #submit()}. */
    public ArmatureTextField onSubmit(Consumer<String> handler) {
        this.onSubmit = handler == null ? value -> {
        } : handler;
        return this;
    }

    public ArmatureTextField colours(int text, int fill, int edge) {
        this.textColour = text;
        this.fillColour = fill;
        this.edgeColour = edge;
        return this;
    }

    /**
     * Hands the value to the handler, once.
     *
     * <p>Called by Enter and by losing focus, and deliberately not on every keystroke: a caller that writes
     * a file or sets a colour per character is a caller writing twenty times for one edit, which is the
     * mistake the editor's own design notes name.
     */
    public void submit() {
        onSubmit.accept(model.value());
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean charTyped(char typed, int modifiers) {
        if (!isFocused() || !isActive()) {
            return false;
        }
        // Control characters are the keyboard's, not the field's: a Ctrl+C arrives here as 3 on some
        // platforms, and a field that inserted it would paste twice.
        if (typed < ' ' || typed == 127) {
            return false;
        }
        model.insert(typed);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused() || !isActive()) {
            return false;
        }
        boolean control = net.minecraft.client.gui.screens.Screen.hasControlDown();

        switch (keyCode) {
            case 257, 335 -> {          // Enter, and the keypad's
                submit();
                setFocused(false);
                return true;
            }
            case 259 -> {               // Backspace
                model.backspace();
                return true;
            }
            case 261 -> {               // Delete
                model.deleteForward();
                return true;
            }
            case 263 -> {               // Left
                model.left();
                return true;
            }
            case 262 -> {               // Right
                model.right();
                return true;
            }
            case 268 -> {               // Home
                model.home();
                return true;
            }
            case 269 -> {               // End
                model.end();
                return true;
            }
            case 67 -> {                // C
                if (control) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(model.value());
                    return true;
                }
            }
            case 86 -> {                // V
                if (control) {
                    // Replaces the whole value rather than inserting at the caret: there is no selection,
                    // and pasting into the middle of a hex code is not a thing anybody means to do.
                    model.setValue(Minecraft.getInstance().keyboardHandler.getClipboard());
                    return true;
                }
            }
            case 65 -> {                // A
                if (control) {
                    // No selection to make, so "select all" means "start again" -- which is what a hex
                    // field's Ctrl+A is reached for.
                    model.clear();
                    return true;
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        // Losing focus is when a caller's work gets done: a field the player tabs away from has been
        // finished with, and only keeping the value in the box would be keeping it to itself.
        if (isFocused() && !focused) {
            submit();
        }
        super.setFocused(focused);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isActive() || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        setFocused(true);
        // The caret goes where the click landed: the model owns the arithmetic, and this hands it an index.
        model.caretTo(caretFor(mouseX));
        return true;
    }

    /** Which character a click at {@code mouseX} is nearest, by measuring the text before it. */
    private int caretFor(double mouseX) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null) {
            return model.length();
        }
        double local = mouseX - (getX() + PAD) + textOffset();
        return model.caretForWidth(local, minecraft.font::width);
    }

    /** How far the text is scrolled left, so the caret stays inside the box. */
    private int textOffset() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null) {
            return 0;
        }
        int caret = minecraft.font.width(model.value().substring(0, Math.min(model.caret(), model.length())));
        int room = getWidth() - PAD * 2;
        return Math.max(0, caret - room);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        render(new GuiGraphicsRenderer(graphics));
    }

    /** The same, through the seam, so a caller with a renderer can draw it. */
    public void render(GuiRenderer r) {
        int edge = isFocused() ? ArmatureTheme.title() : edgeColour;
        r.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), edge);
        r.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fillColour);

        int line = getY() + (getHeight() - 8) / 2;
        try (GuiRenderer.Scoped clip = r.clip(getX() + 1, getY() + 1, getX() + getWidth() - 1,
                getY() + getHeight() - 1)) {
            r.text(model.value(), getX() + PAD - textOffset(), line, textColour);
            if (isFocused() && model.caretVisible(Util.getMillis())) {
                int caret = getX() + PAD - textOffset()
                        + r.textWidth(model.value().substring(0, Math.min(model.caret(), model.length())));
                r.fill(caret, line - 1, caret + 1, line + 9, textColour);
            }
        }
    }

    /** The gap between the box's border and its text. */
    private static final int PAD = 4;

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
