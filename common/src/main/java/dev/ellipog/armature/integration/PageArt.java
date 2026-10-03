package dev.ellipog.armature.integration;

import dev.ellipog.armature.client.render.GuiRenderer;

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
}
