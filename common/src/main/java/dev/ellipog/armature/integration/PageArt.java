package dev.ellipog.armature.integration;

import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The little drawing every adapter repeats: the truncation fit, and a status pill.
 *
 * <p>Three adapters draw the same row twice over — the same label, the same badge — and the pieces
 * that must not drift are the arithmetic ones: how a string is trimmed to a pixel width, and how big
 * a pill is for the word inside it. They live here, mod-free and viewer-free, so the three pages
 * cannot disagree about a pill's height or a trim's ellipsis.
 */
public final class PageArt {

    /** A pill's height: a word's line plus a pixel of padding above and below. */
    public static final int PILL_HEIGHT = 10;
    private static final int PILL_PADDING = 3;

    private PageArt() {
    }

    /**
     * Trims a string to a pixel width, for text a viewer draws without a wrap.
     *
     * <p>ASCII ellipsis deliberately: the font's coverage is measured, and a codepoint outside it is
     * a box rather than a character.
     */
    public static String fit(GuiRenderer renderer, String text, int width) {
        if (width <= 0 || renderer.textWidth(text) <= width) {
            return width <= 0 ? "" : text;
        }
        String cut = text;
        while (!cut.isEmpty() && renderer.textWidth(cut + "...") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.isEmpty() ? "" : cut + "...";
    }

    /** How wide a pill is for this word. */
    public static int pillWidth(GuiRenderer renderer, String text) {
        return renderer.textWidth(text) + 2 * PILL_PADDING;
    }

    /**
     * Draws a status pill: a filled rectangle with its word on it, white so the fill carries it.
     *
     * @return the pill's width, for a caller placing it against a right edge
     */
    public static int pill(GuiRenderer renderer, String text, int x, int y, int colour) {
        int width = pillWidth(renderer, text);
        renderer.fill(x, y, x + width, y + PILL_HEIGHT, colour);
        renderer.text(text, x + PILL_PADDING, y + 1, PagePalette.PILL_TEXT);
        return width;
    }

    /**
     * A reward row's standing, or null when the quest is not finished and there is nothing to say.
     *
     * <p>One rule for three adapters, so a page cannot call the same row Ready in one viewer and
     * Claimed in another.
     */
    public static QuestContent.RewardStatus rewardStatus(QuestRow live) {
        if (live.locked()) {
            return QuestContent.RewardStatus.LOCKED;
        }
        if (live.done()) {
            return QuestContent.RewardStatus.CLAIMED;
        }
        return live.claimable() ? QuestContent.RewardStatus.READY : null;
    }

    /** The colour a reward status is drawn in: the same three the palette uses elsewhere. */
    public static int rewardStatusColour(QuestContent.RewardStatus status) {
        return switch (status) {
            case READY -> PagePalette.COMPLETE;
            case LOCKED -> PagePalette.LOCKED;
            case CLAIMED -> PagePalette.MUTED;
        };
    }

    // ------------------------------------------------------------------
    // The star, and what a quest looks like in a viewer's sidebar
    // ------------------------------------------------------------------

    /**
     * The star both pin states draw from: a 16-pixel five-point star, one character a pixel.
     *
     * <p><b>One mask, and that is the point.</b> The hollow state is this shape's outline ring and
     * nothing else, computed from the same characters the filled state fills, so the two states
     * cannot be two slightly different stars — which is exactly what an earlier draft was, when the
     * hollow state was traced as a shape of its own. Drawn by us rather than taken from a viewer's
     * atlas: a guessed sprite coordinate would be a silent wrong picture instead of a compile error,
     * and no viewer ships a fill/outline pair to borrow anyway.
     */
    private static final String[] STAR = {
            ".......##.......",
            ".......##.......",
            "......####......",
            "......####......",
            ".....######.....",
            "..############..",
            "################",
            ".##############.",
            "..############..",
            "...##########...",
            "....########....",
            "...####..####...",
            "..####....####..",
            "..###......###..",
            ".##..........##.",
            "##............##",
    };

    /** The star's drawn size in pixels -- and so the pin button's, which the layout reserves. */
    public static int starSize() {
        return STAR.length;
    }

    /**
     * Draws the pin star: filled is a gold body inside a dark ring, hollow is that same ring in
     * grey with the middle left empty. Hover lightens whichever of the two is showing.
     */
    public static void star(GuiRenderer renderer, int x, int y, boolean pinned, boolean hovered) {
        for (int row = 0; row < STAR.length; row++) {
            String line = STAR[row];
            int col = 0;
            while (col < line.length()) {
                int colour = colourAt(row, col, pinned, hovered);
                if (colour == 0) {
                    col++;
                    continue;
                }
                int end = col + 1;
                while (end < line.length() && colourAt(row, end, pinned, hovered) == colour) {
                    end++;
                }
                renderer.fill(x + col, y + row, x + end, y + row + 1, colour);
                col = end;
            }
        }
    }

    /** One pixel's colour, or 0 for nothing at all. The ring is the same pixels in both states. */
    private static int colourAt(int row, int col, boolean pinned, boolean hovered) {
        if (STAR[row].charAt(col) != '#') {
            return 0;
        }
        if (isOutline(row, col)) {
            if (pinned) {
                return PagePalette.PIN_OUTLINE;
            }
            return hovered ? PagePalette.PIN_HOLLOW_HOVER : PagePalette.PIN_HOLLOW;
        }
        if (!pinned) {
            return 0;
        }
        return hovered ? PagePalette.PIN_BODY_HOVER : PagePalette.PIN_BODY;
    }

    /** A shape pixel is on the ring when any of its four neighbours is outside the shape. */
    private static boolean isOutline(int row, int col) {
        return !on(row - 1, col) || !on(row + 1, col) || !on(row, col - 1) || !on(row, col + 1);
    }

    private static boolean on(int row, int col) {
        return row >= 0 && row < STAR.length && col >= 0 && col < STAR[row].length()
                && STAR[row].charAt(col) == '#';
    }

    /**
     * What a viewer's recipe for this quest offers as its outputs.
     *
     * <p>The item rewards, in order; and when there are none — an xp-only, stage or command reward —
     * the quest's own icon, with the category's icon behind it as a last resort. Never empty, and
     * that is the point: a viewer favourites a recipe by its first output, draws the sidebar entry
     * from it, and refuses an empty stack, so an empty list is the difference between a quest that
     * can be pinned and one that cannot.
     */
    public static List<ItemStack> outputs(QuestPage page, ItemStack fallbackIcon) {
        List<ItemStack> out = new ArrayList<>();
        for (QuestRow row : page.rewards()) {
            if (!row.icon().isEmpty()) {
                out.add(row.icon());
            }
        }
        if (out.isEmpty() && !page.quest().icon().isEmpty()) {
            out.add(page.quest().icon());
        }
        if (out.isEmpty() && fallbackIcon != null && !fallbackIcon.isEmpty()) {
            out.add(fallbackIcon);
        }
        return List.copyOf(out);
    }
}
