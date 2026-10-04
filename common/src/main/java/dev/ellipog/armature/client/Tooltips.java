package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiRenderer;

import java.util.List;

/**
 * A tooltip box: the themed panel, and the lines in it.
 *
 * <h2>Why the box lives in the toolkit and not in a screen</h2>
 *
 * <p>Because every tooltip in a modded screen is the same five decisions — how wide, how tall, which
 * way to flip near an edge, what to fill it with, what ink to write in — and a screen that answered
 * them itself is a screen whose tooltips drift from every other screen's the first time one of the
 * answers changes. It happened once already: the tooltip box was drawn from {@code panel()} and
 * {@code controlEdgeBright()} while the theme carried {@code tooltipFill}/{@code tooltipEdge} tokens
 * that nothing in the game used, so a theme could set its tooltip colours and see no change.
 *
 * <h2>The theme is read, not captured</h2>
 *
 * <p>Fill, border, text and radius all come from {@link ArmatureTheme#current()}, so a tooltip drawn
 * inside a chapter's scope wears that chapter's palette — which is the whole point of a scope.
 */
public final class Tooltips {

    /** The inset between the box's edge and its text, and the gap to the pointer. */
    public static final int PAD = 4;

    /** How far the box sits from the pointer, on the side it opens towards. */
    public static final int OFFSET = 10;

    /** The smallest y a box may be drawn at, so a flipped box never leaves the screen. */
    public static final int TOP_MARGIN = 2;

    private Tooltips() {
    }

    /** The box's width for these lines: the widest line plus the padding. */
    public static int width(GuiRenderer r, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, r.textWidth(line));
        }
        return widest + PAD * 2;
    }

    /** The box's height for this many lines: one text height each, plus the padding. */
    public static int height(GuiRenderer r, int lineCount) {
        return lineCount * r.lineHeight() + PAD + 2;
    }

    /**
     * Draws the box near the pointer, flipped and clamped to stay on screen.
     *
     * <p>Down-right of the pointer by default, flipped left when the right edge would be crossed and up
     * when the bottom would be — the same three moves at every scale, so a tooltip at the bottom of a
     * window reads as the same box as one at the top.
     */
    public static void draw(GuiRenderer r, List<String> lines, int mouseX, int mouseY,
                            int screenWidth, int screenHeight) {
        if (lines.isEmpty()) {
            return;
        }
        int boxWidth = width(r, lines);
        int boxHeight = height(r, lines.size());
        int x = mouseX + OFFSET;
        if (x + boxWidth > screenWidth) {
            x = mouseX - boxWidth - PAD;
        }
        int y = mouseY - 11;
        if (y + boxHeight > screenHeight) {
            y = screenHeight - boxHeight - 2;
        }
        y = Math.max(TOP_MARGIN, y);

        ArmatureTheme.panel(r, x, y, boxWidth, boxHeight, ArmatureTheme.tooltipFill(),
                ArmatureTheme.tooltipEdge());
        int lineY = y + PAD;
        for (String line : lines) {
            r.text(line, x + PAD, lineY, ArmatureTheme.tooltipText());
            lineY += r.lineHeight();
        }
    }
}
