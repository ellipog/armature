package dev.ellipog.armature.integration;

import java.util.Optional;

/**
 * Where everything on a quest page sits, as pure arithmetic.
 *
 * <p>Every viewer has its own drawing API and none of them should have its own layout: a row that
 * sits two pixels higher in JEI than in EMI is the kind of difference that is invisible in review
 * and obvious in a screenshot. So the geometry lives here, with no drawing types and no viewer types,
 * and each adapter asks for boxes and paints inside them. It is also the only part of the page code
 * that a game-free test can pin, which is why it exists as a class rather than as arithmetic inside
 * three adapters.
 *
 * <p>The page is a header strip, then the task rows, then a gap if there are rewards, then the reward
 * rows. The width is the adapter's: EMI's default layout wants 134, JEI and REI are told their own.
 */
public final class QuestPageLayout {

    public static final int HEADER_HEIGHT = 18;
    public static final int ROW_HEIGHT = 18;
    public static final int ICON = 16;
    public static final int ICON_X = 2;
    public static final int TEXT_X = ICON_X + ICON + 4;
    public static final int BAR_HEIGHT = 3;
    /** The bar's top edge, measured from the row's top: under the label, inside the row. */
    public static final int BAR_Y = 13;
    /** The space between the last task row and the first reward row, only when both exist. */
    public static final int SECTION_GAP = 4;
    private static final int MARGIN = 2;

    /** A rectangle. Deliberately not a toolkit type: this class must stay drawable-API-free. */
    public record Box(int x, int y, int width, int height) {

        public boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    /**
     * What is under a point: the header, or a row and which one. {@code task} tells the two row
     * spaces apart, because "row 2" means a task on one side of the gap and a reward on the other.
     */
    public record Hit(boolean header, boolean task, int index) {
    }

    private final int width;

    public QuestPageLayout(int width) {
        if (width < TEXT_X + MARGIN) {
            throw new IllegalArgumentException("a page narrower than its own text column: " + width);
        }
        this.width = width;
    }

    public int width() {
        return width;
    }

    public int height(QuestPage page) {
        return HEADER_HEIGHT
                + page.tasks().size() * ROW_HEIGHT
                + gap(page)
                + page.rewards().size() * ROW_HEIGHT;
    }

    public Box header() {
        return new Box(0, 0, width, HEADER_HEIGHT);
    }

    public Box taskRow(int index) {
        return new Box(0, HEADER_HEIGHT + index * ROW_HEIGHT, width, ROW_HEIGHT);
    }

    public Box rewardRow(QuestPage page, int index) {
        return new Box(0, HEADER_HEIGHT + page.tasks().size() * ROW_HEIGHT + gap(page) + index * ROW_HEIGHT,
                width, ROW_HEIGHT);
    }

    /** The item's 16-pixel square at the left of a row. */
    public Box icon(Box row) {
        return new Box(ICON_X, row.y() + 1, ICON, ICON);
    }

    /** The label's column: everything right of the icon, inset by the margin. */
    public Box text(Box row) {
        return new Box(TEXT_X, row.y() + 3, Math.max(1, width - TEXT_X - MARGIN), 9);
    }

    /** The progress bar, under the label. */
    public Box bar(Box row) {
        return new Box(TEXT_X, row.y() + BAR_Y, Math.max(1, width - TEXT_X - MARGIN - 2), BAR_HEIGHT);
    }

    /**
     * What is under {@code y}: the header, a task, a reward, or nothing (the section gap, or beyond
     * the page).
     */
    public Optional<Hit> rowAt(QuestPage page, int y) {
        if (y < 0 || y >= height(page)) {
            return Optional.empty();
        }
        if (y < HEADER_HEIGHT) {
            return Optional.of(new Hit(true, false, 0));
        }
        int tasks = page.tasks().size();
        if (y < HEADER_HEIGHT + tasks * ROW_HEIGHT) {
            return Optional.of(new Hit(false, true, (y - HEADER_HEIGHT) / ROW_HEIGHT));
        }
        int rewardsStart = HEADER_HEIGHT + tasks * ROW_HEIGHT + gap(page);
        if (y < rewardsStart) {
            return Optional.empty();
        }
        return Optional.of(new Hit(false, false, (y - rewardsStart) / ROW_HEIGHT));
    }

    private static int gap(QuestPage page) {
        return page.tasks().isEmpty() || page.rewards().isEmpty() ? 0 : SECTION_GAP;
    }
}
