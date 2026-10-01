package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
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
 * <h2>Selection, and the gesture that asked for it</h2>
 *
 * <p>The model grew an anchor when a report came back from play: *"double clicking the text in text fields
 * should mark it"*. A double click marks the word under the pointer, the mark is drawn behind the text, and
 * typing or deleting replaces it. Ctrl+C copies the mark when there is one and the whole value otherwise,
 * and Ctrl+A marks everything — which it used to mean "clear" only because there was nothing to mark.
 *
 * <p>A drag marks a run, which came from play -- *"allow selecting as well, like dragging the cursor in
 * text fields"* -- and it is the block's own gesture: the press places the caret, the drag extends the
 * mark from it ({@link #mouseDragged}). There is still no shift-arrow: nobody has reached for that, and
 * the rules for the gestures that have been are the model's, where they are asserted without a client.
 *
 * <h2>The border is the caller's in every state, and that is a fix rather than a default</h2>
 *
 * <p>It used to brighten to the theme's title colour while focused. That is a focus ring, and it is the
 * obvious thing to write -- it was also the one difference an author could see between a value they were
 * editing and the same value at rest, which made clicking a field move and recolour the text it
 * surrounds. What says the field has the keyboard is the caret. A field standing in for text it replaces
 * has its caller pass {@code 0x00000000} for both the fill and the edge, and then the click that focuses
 * it changes nothing about the picture.
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

    /**
     * Set while {@link #submit()} is running its handler.
     *
     * <p>Because a handler may rebuild the screen's widgets, and clearing them blurs this field, and a blur
     * submits -- which is a StackOverflowError, not a warning, and it is the crash this field shipped once.
     * The message is not lost by this guard: whatever the handler did is what caused the second submit, and
     * the value has not changed in between.
     */
    private boolean submitting;

    /** When and where the last click landed, so a second one in the same place means "a word". */
    private long lastClickMillis;
    private double lastClickX;

    /** What counts as a double click. Vanilla's own 250ms, so it feels the same as everything else. */
    private static final long DOUBLE_CLICK_MILLIS = 250L;

    public ArmatureTextField(int x, int y, int width, int height, String initial) {
        super(x, y, width, height, Component.empty());
        this.model = TextField.of(64).setValue(initial == null ? "" : initial);
        // The value a field is opened with is where its history starts: Ctrl+Z on a just-opened field is
        // not a way to empty it. A paste is a step; the opening value is not.
        this.model.clearHistory();
        this.active = true;
    }

    /** The value, as it stands. */
    public String value() {
        return model.value();
    }

    /** Where the caret is in the text, for a caller watching whether it moved. */
    public int caret() {
        return model.caret();
    }

    /**
     * Where the caret is, measured from the widget's own top. One line, centred in the box, which is the
     * same arithmetic the drawing uses -- see {@code ArmatureTextArea.caretTop} for why a caller wants it.
     */
    public int caretTop() {
        return Math.max(0, (getHeight() - 8) / 2);
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
     *
     * <p><b>Not re-entrant.</b> A handler that rebuilds its own screen blurs this field, and this method is
     * what a blur calls; the cycle crashed the game with a StackOverflowError five hundred frames deep
     * before the guard below existed. A nested submit is the handler's own consequence and not a second
     * edit, so it returns.
     */
    public void submit() {
        if (submitting) {
            return;
        }
        submitting = true;
        try {
            onSubmit.accept(model.value());
        }
        finally {
            submitting = false;
        }
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
            case 256 -> {               // Escape: the same -- the edit is over, the card is not
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
                    // The mark when there is one, the whole value otherwise -- what every field does, and
                    // what makes a double click worth doing at all.
                    Minecraft.getInstance().keyboardHandler.setClipboard(
                            model.hasSelection() ? model.selectedText() : model.value());
                    return true;
                }
            }
            case 86 -> {                // V
                if (control) {
                    // Replaces the whole value rather than inserting at the caret, mark or no mark: a hex
                    // code is one value, and pasting into the middle of one is not a thing anybody means
                    // to do.
                    model.setValue(Minecraft.getInstance().keyboardHandler.getClipboard());
                    return true;
                }
            }
            case 90 -> {                // Z: one step back through this field's own edits
                if (control) {
                    // The *field's* history, not the chapter's: the screen's Ctrl+Z is guarded on no
                    // widget having focus, and it is a server operation over the whole chapter. While a
                    // field has the keyboard, Z is the field's -- see the model's `undo`.
                    model.undo();
                    return true;
                }
            }
            case 89 -> {                // Y: and forward again, the pair the screen's own keys use
                if (control) {
                    model.redo();
                    return true;
                }
            }
            case 65 -> {                // A
                if (control) {
                    // Marks everything. It used to clear the field, which was only ever a substitute for
                    // this: there was no selection to make, so "select all" was spent on the nearest thing
                    // a hex field wanted -- starting again.
                    model.selectAll();
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
        long now = Util.getMillis();
        boolean twice = now - lastClickMillis <= DOUBLE_CLICK_MILLIS
                && Math.abs(mouseX - lastClickX) <= 3;
        lastClickMillis = now;
        lastClickX = mouseX;
        setFocused(true);
        // The caret goes where the click landed: the model owns the arithmetic, and this hands it an index.
        model.caretTo(caretFor(mouseX));
        if (twice) {
            // A second click in the same place marks the word under it. Which characters count as a word is
            // the model's rule, and it is asserted there without a client.
            model.selectWordAt(model.caret());
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!isFocused() || !isActive() || button != 0) {
            return false;
        }
        // A drag extends the mark from wherever the press put the caret -- the gesture every field has,
        // and the block's own rule ({@code ArmatureTextArea.mouseDragged}). The anchor is the press's,
        // because a click places the caret with {@code caretTo}, which drops the anchor where it lands.
        model.selectTo(caretFor(mouseX));
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
        // The caller's edge, focused or not -- see the class note on why this does not answer to focus.
        r.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), edgeColour);
        r.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fillColour);

        int line = getY() + (getHeight() - 8) / 2;
        try (GuiRenderer.Scoped clip = r.clip(getX() + 1, getY() + 1, getX() + getWidth() - 1,
                getY() + getHeight() - 1)) {
            int textX = getX() + PAD - textOffset();
            if (model.hasSelection()) {
                // Behind the text, in the text's own colour at a third of it: a field knows its text and its
                // box and nothing else, so the mark is made from what it has rather than from a theme.
                int from = textX + r.textWidth(model.value().substring(0, model.selectionStart()));
                int to = textX + r.textWidth(model.value().substring(0, model.selectionEnd()));
                r.fill(from, line - 1, to, line + 9, Colour.alphaOf(textColour, 0.35F));
            }
            r.text(model.value(), textX, line, textColour);
            if (isFocused() && model.caretVisible(Util.getMillis())) {
                int caret = textX + r.textWidth(model.value().substring(0, Math.min(model.caret(),
                        model.length())));
                r.fill(caret, line - 1, caret + 1, line + 9, textColour);
            }
        }
    }

    /** The gap between the box's border and its text. */
    public static final int PAD = 4;

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
