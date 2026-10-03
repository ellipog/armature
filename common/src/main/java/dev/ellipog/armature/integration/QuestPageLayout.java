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
 * <p>The page is a header strip, then a heading and the task rows, then a gap and a heading and the
 * reward rows. The headings exist because a reward row and a task row can look identical — the same
 * item, the same count — and a player who read a reward as something to hand in would be misled by
 * the page itself.
 *
 * <p>The width is the adapter's: EMI's default layout wants 134, JEI and REI are told their own.
 */
public final class QuestPageLayout {

    public static final int HEADER_HEIGHT = 18;
    /** A section's label strip, drawn only when that section has rows. */
    public static final int HEADING_HEIGHT = 10;
    public static final int ROW_HEIGHT = 18;
    public static final int ICON = 16;
    public static final int ICON_X = 2;
    public static final int TEXT_X = ICON_X + ICON + 4;
    public static final int BAR_HEIGHT = 3;
    /** The bar's top edge, measured from the row's top: under the label, inside the row. */
    public static final int BAR_Y = 13;
    /** The space between the last task row and the reward heading, only when both exist. */
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

        /** An empty box, for a heading whose section has no rows. */
        public boolean empty() {
            return width == 0 || height == 0;
        }
    }

    /**
     * What is under a point: the header, or a row and which one. {@code task} tells the two row
     * spaces apart, because "row 2" means a task on one side of the gap and a reward on the other.
     * Headings and the gap are not rows.
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
                + section(HEADING_HEIGHT, page.tasks().size())
                + gap(page)
                + section(HEADING_HEIGHT, page.rewards().size());
    }

    public Box header() {
        return new Box(0, 0, width, HEADER_HEIGHT);
    }

    /** The "Tasks" strip, empty when the quest has no task rows. */
    public Box tasksHeading(QuestPage page) {
        return page.tasks().isEmpty() ? new Box(0, 0, 0, 0) : new Box(0, HEADER_HEIGHT, width, HEADING_HEIGHT);
    }

    /** The "Rewards" strip, empty when the quest has no reward rows. */
    public Box rewardsHeading(QuestPage page) {
        if (page.rewards().isEmpty()) {
            return new Box(0, 0, 0, 0);
        }
        int y = HEADER_HEIGHT + section(HEADING_HEIGHT, page.tasks().size()) + gap(page);
        return new Box(0, y, width, HEADING_HEIGHT);
    }

    public Box taskRow(int index) {
        return new Box(0, HEADER_HEIGHT + HEADING_HEIGHT + index * ROW_HEIGHT, width, ROW_HEIGHT);
    }

    public Box rewardRow(QuestPage page, int index) {
        QuestPageLayout.Box heading = rewardsHeading(page);
        return new Box(0, heading.y() + HEADING_HEIGHT + index * ROW_HEIGHT, width, ROW_HEIGHT);
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
     * What is under {@code y}: the header, a task, a reward, or nothing — a heading, the section gap,
     * or beyond the page.
     */
    public Optional<Hit> rowAt(QuestPage page, int y) {
        if (y < 0 || y >= height(page)) {
            return Optional.empty();
        }
        if (header().contains(0, y)) {
            return Optional.of(new Hit(true, false, 0));
        }
        int tasks = page.tasks().size();
        if (tasks > 0) {
            QuestPageLayout.Box heading = tasksHeading(page);
            if (heading.contains(0, y)) {
                return Optional.empty();
            }
            if (y < heading.bottom() + tasks * ROW_HEIGHT) {
                return Optional.of(new Hit(false, true, (y - heading.bottom()) / ROW_HEIGHT));
            }
        }
        if (page.rewards().isEmpty()) {
            return Optional.empty();
        }
        QuestPageLayout.Box heading = rewardsHeading(page);
        if (y < heading.bottom()) {
            return Optional.empty();
        }
        return Optional.of(new Hit(false, false, (y - heading.bottom()) / ROW_HEIGHT));
    }

    /** A section's contribution: its heading plus its rows, or nothing when it has no rows. */
    private static int section(int headingHeight, int rows) {
        return rows == 0 ? 0 : headingHeight + rows * ROW_HEIGHT;
    }

    private static int gap(QuestPage page) {
        return page.tasks().isEmpty() || page.rewards().isEmpty() ? 0 : SECTION_GAP;
    }
}
