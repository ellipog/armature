package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.TextArea;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * A multi-line text box: the kit's {@link TextArea} with a widget around it.
 *
 * <h2>What is the model's and what is this</h2>
 *
 * <p>The same split as {@link ArmatureTextField}, one dimension up: where a newline puts the caret,
 * which character a click means once the text has wrapped, what up and down are -- the model's, and
 * asserted without a client. This is the half that cannot be: keystrokes, focus, the blink, the scroll
 * that keeps the caret in view, and the drawing. It is thin on purpose.
 *
 * <h2>Enter is a newline; Escape ends the edit</h2>
 *
 * <p>A multi-line box cannot commit on Enter -- Enter is the whole point of it. So the commit is the
 * blur, as with the field, and <b>Escape ends the edit</b> by submitting and blurring rather than
 * bubbling up: the screen's Escape closes the card, and an author pressing it while writing a
 * description means "stop writing", not "close the quest". A second Escape closes the card.
 *
 * <h2>The wrap is measured, then cached</h2>
 *
 * <p>Wrapping calls {@code textWidth} once per character, so it runs when the text or the width has
 * changed and not once per frame. The cache key is the pair, because a resize re-wraps and an edit
 * re-wraps, and neither alone does.
 *
 * <h2>The advance is the caller's, and the border does not answer to focus</h2>
 *
 * <p>{@link #advance} sets the line pitch and the paragraph gap, because a caller can be standing in for
 * text it did not draw: the quest editor's description replaces prose the overlay laid out at a pitch of
 * its own, and a line drawn at the font's height inside that block moves the moment the box is clicked.
 * {@link TextArea#lineTop} carries the arithmetic; this class only adds pixels.
 *
 * <p>And the border is the caller's colour in <b>every</b> state. It used to brighten to the theme's
 * title colour while focused -- a focus ring, and the one difference the author could see between a value
 * being edited and a value at rest. A field that is meant to look like the text it replaces cannot have
 * one: what says the field has the keyboard is the caret, which is the only thing an editor needs and a
 * reader does not have.
 */
public final class ArmatureTextArea extends AbstractWidget {

    /** The model: text, caret, selection. */
    private final TextArea model;

    /** Called with the whole value when the box is finished with -- Enter is a newline here. */
    private Consumer<String> onSubmit = value -> {
    };

    private int textColour = 0xFFFFFFFF;
    private int fillColour = 0xFF101014;
    private int edgeColour = 0xFF3A3A46;

    /** The first visual line drawn, so a long block scrolls to keep the caret in view. */
    private int firstVisibleLine;

    /** The line pitch asked for, or 0 for the font's own height. See {@link #advance}. */
    private int linePitch;

    /** Blank pixels before each paragraph after the first. See {@link #advance}. */
    private int paragraphGap;

    /** The wrap cache: the text and width it was computed for, and its spans. */
    private String wrappedValue;
    private int wrappedWidth = -1;
    private List<TextArea.Span> spans = List.of();

    /** Set while {@link #submit()} runs, for the same reason the field has one: rebuilds blur, blur submits. */
    private boolean submitting;

    public ArmatureTextArea(int x, int y, int width, int height, String initial) {
        super(x, y, width, height, Component.empty());
        this.model = TextArea.of(4096).setValue(initial == null ? "" : initial);
        // The value a block is opened with is where its history starts -- see the one-line field.
        this.model.clearHistory();
        this.active = true;
    }

    public String value() {
        return model.value();
    }

    /** Where the caret is in the text, for a caller watching whether it moved. */
    public int caret() {
        return model.caret();
    }

    public ArmatureTextArea setValue(String value) {
        model.setValue(value);
        return this;
    }

    public ArmatureTextArea onSubmit(Consumer<String> handler) {
        this.onSubmit = handler == null ? value -> {
        } : handler;
        return this;
    }

    public ArmatureTextArea colours(int text, int fill, int edge) {
        this.textColour = text;
        this.fillColour = fill;
        this.edgeColour = edge;
        return this;
    }

    /**
     * The pitch to draw the lines at, and how much blank space a paragraph starts after.
     *
     * <p>For a caller standing in for text that someone else drew: a reader that lays prose out at
     * {@code 10} a line with {@code 5} between paragraphs, and an editor that has to draw the same
     * block in the same place or the text moves when it is clicked. The default -- {@code 0, 0} -- is
     * the font's own line height and no paragraph spacing, which is what a caller with no one to match
     * wants, and what this drew before there was a caller that had to.
     *
     * @param lineHeight pixels a visual line advances by, or 0 for the font's own
     * @param paragraphGap blank pixels before each hard line after the first
     */
    public ArmatureTextArea advance(int lineHeight, int paragraphGap) {
        this.linePitch = Math.max(0, lineHeight);
        this.paragraphGap = Math.max(0, paragraphGap);
        return this;
    }

    /** Hands the value to the handler, once. See {@link ArmatureTextField#submit} for the guard's story. */
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
    // The wrap
    // ------------------------------------------------------------------

    /**
     * The visual lines, measured with <b>the font</b> -- the same measure the input handlers use.
     *
     * <p>Not the renderer's {@code textWidth}: wrapping runs in {@code keyPressed} and
     * {@code mouseClicked} too, where there is no renderer, and a second measure for those paths would
     * put the caret at a character the drawn text is not under. One function, {@link #textWidth}, is
     * the only width anything here asks for.
     */
    private List<TextArea.Span> spans() {
        int width = Math.max(1, getWidth() - PAD * 2);
        if (spans.isEmpty() || !model.value().equals(wrappedValue) || width != wrappedWidth) {
            spans = TextArea.wrap(model.value(), width, ArmatureTextArea::textWidth);
            wrappedValue = model.value();
            wrappedWidth = width;
        }
        return spans;
    }

    /** The one measure. The font, which is what the drawing draws with. */
    private static int textWidth(String text) {
        return Minecraft.getInstance().font.width(text);
    }

    /** The pitch the lines are drawn at: the caller's, or the font's own when none was asked for. */
    private int lineHeight() {
        return linePitch > 0 ? linePitch : Minecraft.getInstance().font.lineHeight;
    }

    /** The gap between the box's border and its text. Six, because four reads as none. */
    public static final int PAD = 6;

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * The one forced signature: {@code AbstractWidget.renderWidget} takes a {@code GuiGraphics} and is
     * abstract, so the override wraps it in one line and hands it to {@link GuiRenderer} -- exactly as
     * {@code ArmatureButton} and {@code ArmatureTextField} do. {@code check_seam.py} names this file
     * for that reason and no other.
     */
    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        render(new dev.ellipog.armature.client.render.GuiGraphicsRenderer(graphics));
    }

    /** The same, through the seam, so a caller with a renderer can draw it. */
    public void render(GuiRenderer r) {
        // The border is the caller's in every state, focused or not: a field must look the same while
        // it is being edited as it does the frame after, or every click moves and recolours the text
        // it surrounds. The caret is what says which field has the keyboard.
        r.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), edgeColour);
        r.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fillColour);

        List<TextArea.Span> lines = spans();
        int lineHeight = lineHeight();
        int visible = Math.max(1, TextArea.linesThatFit(lines, firstVisibleLine, getHeight() - PAD * 2,
                lineHeight, paragraphGap));
        keepCaretVisible(lines, visible);

        try (GuiRenderer.Scoped clip = r.clip(getX() + 1, getY() + 1, getX() + getWidth() - 1,
                getY() + getHeight() - 1)) {
            String whole = model.value();
            for (int i = firstVisibleLine; i < Math.min(lines.size(), firstVisibleLine + visible); i++) {
                TextArea.Span span = lines.get(i);
                // Every line's own top, from the one rule: a line drawn at the font's pitch inside a
                // block laid out at someone else's is a line that moves when the field is clicked.
                int y = getY() + PAD + TextArea.lineTop(lines, i, lineHeight, paragraphGap);
                int x = getX() + PAD;

                // The selection behind the text, the same rule the field uses: the text's own colour at
                // a third of it, so a box that knows only its text and its box can still show a mark.
                if (model.hasSelection()) {
                    int from = Math.max(span.start(), model.selectionStart());
                    int to = Math.min(span.end(), model.selectionEnd());
                    if (from < to) {
                        int markX = x + textWidth(whole.substring(span.start(), from));
                        int markTo = x + textWidth(whole.substring(span.start(), to));
                        r.fill(markX, y - 1, markTo, y + lineHeight - 1,
                                Colour.alphaOf(textColour, 0.35F));
                    }
                }

                r.text(span.text(whole), x, y, textColour);

                if (isFocused() && model.caret() >= span.start() && model.caret() <= span.end()
                        && model.caretVisible(Util.getMillis())) {
                    int caretX = x + textWidth(whole.substring(span.start(), model.caret()));
                    r.fill(caretX, y - 1, caretX + 1, y + lineHeight - 1, textColour);
                }
            }
        }
    }

    /**
     * Where the caret is, measured from the widget's own top: the y its line begins at.
     *
     * <p>For a caller that scrolls the text into view -- the quest editor's card follows the caret rather
     * than this box, because the box is as tall as the whole text and following *it* means the view never
     * settles. The value moved with the caret, and moves no other way, so a caller that scrolls only when
     * the caret has moved never fights the mouse wheel.
     */
    public int caretTop() {
        List<TextArea.Span> lines = spans();
        int height = lineHeight();
        int line = TextArea.lineOf(model.caret(), lines);
        return PAD + TextArea.lineTop(lines, line, height, paragraphGap)
                - TextArea.lineTop(lines, firstVisibleLine, height, paragraphGap);
    }

    private void keepCaretVisible(List<TextArea.Span> lines, int visible) {
        int caretLine = TextArea.lineOf(model.caret(), lines);
        if (caretLine < firstVisibleLine) {
            firstVisibleLine = caretLine;
        }
        if (caretLine >= firstVisibleLine + visible) {
            firstVisibleLine = caretLine - visible + 1;
        }
        firstVisibleLine = Math.max(0, Math.min(firstVisibleLine, Math.max(0, lines.size() - visible)));
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean charTyped(char typed, int modifiers) {
        if (!isFocused() || !isActive()) {
            return false;
        }
        if (typed == '\r') {
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
        List<TextArea.Span> lines = spans();

        switch (keyCode) {
            case 257, 335 -> {          // Enter, and the keypad's: a newline, not a commit
                model.insert('\n');
                return true;
            }
            case 256 -> {               // Escape: the edit is over, the card is not
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
            case 263 -> {
                model.left();
                return true;
            }
            case 262 -> {
                model.right();
                return true;
            }
            case 265 -> {               // Up: a visual line, not a hard one
                model.up(lines);
                return true;
            }
            case 264 -> {               // Down
                model.down(lines);
                return true;
            }
            case 268 -> {
                model.home();
                return true;
            }
            case 269 -> {
                model.end();
                return true;
            }
            case 67 -> {                // C: the mark, or the whole block
                if (control) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(
                            model.hasSelection() ? model.selectedText() : model.value());
                    return true;
                }
            }
            case 86 -> {                // V: inserted at the caret, unlike the one-line field
                if (control) {
                    String pasted = Minecraft.getInstance().keyboardHandler.getClipboard();
                    for (int i = 0; i < pasted.length(); i++) {
                        model.insert(pasted.charAt(i));
                    }
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
        // Losing focus is when the caller's work gets done; see the field's override for the story.
        if (isFocused() && !focused) {
            submit();
        }
        super.setFocused(focused);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isActive() || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        setFocused(true);
        // A click puts the caret where it landed and drops any mark -- the model's rule, from the model's
        // pure point-to-index arithmetic. See `indexAt` for why the two gestures share it.
        model.caretTo(indexAt(mouseX, mouseY));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!isFocused() || button != 0) {
            return false;
        }
        // A drag extends the mark from wherever the press put the caret, which is the one gesture a block
        // of prose is edited with. `selectTo` over the **pure** index -- *not* `selectTo(caretAt(...))`,
        // which is what this was: `caretAt` ends by dropping the anchor, so the mark it produced was
        // always empty and dragging in the description selected nothing at all.
        model.selectTo(indexAt(mouseX, mouseY));
        return true;
    }

    /**
     * Which character index a point means, line and column, from the same advance the drawing uses -- and
     * mutating nothing. The caller decides what the answer means: a click puts the caret there, a drag
     * extends the mark to there.
     */
    private int indexAt(double mouseX, double mouseY) {
        List<TextArea.Span> lines = spans();
        int line = TextArea.lineAt(lines, (int) (mouseY - getY() - PAD), lineHeight(), paragraphGap);
        return model.indexAt(line, mouseX - getX() - PAD, lines, ArmatureTextArea::textWidth);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
