package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.TextWrap;

import java.util.ArrayList;
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
 * <h2>A long line is folded, and the folding rule is {@link TextWrap}'s</h2>
 *
 * <p>It used to be a flip and a clamp and nothing else, which is enough for a label and not enough for a
 * sentence: the box was as wide as its widest line, so one long help sentence — or a row's list of what
 * it is waiting for — ran off the left of the window, where the flip had put it, and half of it was
 * simply not on screen. Folding is the fix, and the rule for it already existed: {@link TextWrap} is what
 * a panel's rows, a card's body and a prose column wrap with, so a tooltip wraps the way the rest of the
 * toolkit's text does rather than the way this class would have invented.
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

    /** The closest the box comes to any screen edge, on every side. */
    public static final int MARGIN = 2;

    /**
     * The widest a line gets before it is folded, in pixels.
     *
     * <p>About forty characters of the default font. That is the order vanilla wraps its own tooltips
     * at, and it is chosen the same way: wide enough that a sentence keeps its shape across two lines
     * rather than five, narrow enough that the box still reads as a tip rather than as a paragraph.
     * It is an <b>upper</b> bound and not a target — a short line still gets a short box, because a
     * tooltip padded out to a fixed width would look like a panel.
     */
    public static final int MAX_LINE_WIDTH = 240;

    /**
     * The narrowest a line is folded to, for a window smaller than {@link #MAX_LINE_WIDTH}.
     *
     * <p>Only reachable on a window a few dozen pixels across, and it exists so the arithmetic below
     * has a floor: a room of zero is a wrap that cannot place a character and a box one glyph wide.
     */
    public static final int MIN_LINE_WIDTH = 48;

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
     * The lines as they will be drawn: each one folded to the room the window leaves it.
     *
     * <p>Separate from {@link #draw} because the folding is the part worth asking about — a test can
     * assert the lines without a box, and a caller that wants to know how tall a tooltip will be asks
     * this first and then {@link #height}.
     *
     * <p>An <b>empty</b> line stays one line. {@link TextWrap} answers "nothing in, nothing out" for a
     * paragraph, and for a tooltip that is wrong: a blank line here is a spacer a caller wrote on
     * purpose, and it is how a tooltip separates two facts. That one difference is the whole of this
     * method's own logic; everything else is {@code TextWrap}'s.
     */
    public static List<String> folded(GuiRenderer r, List<String> lines, int screenWidth) {
        int room = lineRoom(screenWidth);
        Measure measure = Measure.of(r::textWidth, r.lineHeight());
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                out.add("");
                continue;
            }
            out.addAll(TextWrap.wrap(line, room, measure));
        }
        return List.copyOf(out);
    }

    /**
     * How wide one line may be, given the window.
     *
     * <p>The cap, or whatever the window can actually hold with a margin either side, whichever is
     * smaller — so a narrow window folds earlier rather than drawing a box wider than itself.
     */
    private static int lineRoom(int screenWidth) {
        return Math.max(MIN_LINE_WIDTH,
                Math.min(MAX_LINE_WIDTH, screenWidth - MARGIN * 2 - PAD * 2));
    }

    /**
     * Draws the box near the pointer, folded to fit and clamped to stay on screen.
     *
     * <p>Down-right of the pointer by default, flipped left when the right edge would be crossed and up
     * when the bottom would be — the same three moves at every scale, so a tooltip at the bottom of a
     * window reads as the same box as one at the top. Then clamped, which is the backstop the flip is
     * not: a window narrower than the box's own padding can still put the flip past the left edge, and
     * folding bounds the box by design rather than by arithmetic.
     */
    public static void draw(GuiRenderer r, List<String> lines, int mouseX, int mouseY,
                            int screenWidth, int screenHeight) {
        if (lines.isEmpty()) {
            return;
        }
        List<String> folded = folded(r, lines, screenWidth);
        int boxWidth = width(r, folded);
        int boxHeight = height(r, folded.size());
        int x = mouseX + OFFSET;
        if (x + boxWidth > screenWidth - MARGIN) {
            x = mouseX - boxWidth - PAD;
        }
        x = Math.max(MARGIN, Math.min(x, screenWidth - boxWidth - MARGIN));
        int y = mouseY - 11;
        if (y + boxHeight > screenHeight - MARGIN) {
            y = screenHeight - boxHeight - MARGIN;
        }
        y = Math.max(TOP_MARGIN, y);

        ArmatureTheme.panel(r, x, y, boxWidth, boxHeight, ArmatureTheme.tooltipFill(),
                ArmatureTheme.tooltipEdge());
        int lineY = y + PAD;
        for (String line : folded) {
            r.text(line, x + PAD, lineY, ArmatureTheme.tooltipText());
            lineY += r.lineHeight();
        }
    }
}
