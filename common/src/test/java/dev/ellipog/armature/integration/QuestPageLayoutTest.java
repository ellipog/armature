package dev.ellipog.armature.integration;

import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page's arithmetic: heights, boxes and hit testing, pinned so three adapters cannot disagree.
 *
 * <p>This is the only page code a game-free test can see, and that is the reason it is a class: the
 * adapters read boxes from here, so a row that overlaps its neighbour or a bar that falls outside its
 * row fails here rather than in a screenshot.
 */
class QuestPageLayoutTest {

    @BeforeAll
    static void bootstrap() {
        // The rows carry empty ItemStacks, which need the registry flag set.
        MinecraftTestBootstrap.boot();
    }

    private static QuestRef quest() {
        return new QuestRef("q", "Quest", "Chapter", ItemStack.EMPTY, "");
    }

    private static QuestRow row(String label) {
        return new QuestRow(ItemStack.EMPTY, label, 0, 3, false, false, "");
    }

    private static QuestPage page(int tasks, int rewards) {
        List<QuestRow> t = new ArrayList<>();
        List<QuestRow> r = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            t.add(row("task " + i));
        }
        for (int i = 0; i < rewards; i++) {
            r.add(row("reward " + i));
        }
        return new QuestPage(quest(), t, r);
    }

    @Test
    @DisplayName("height is the header plus the rows, with a gap only between two non-empty sections")
    void heightIsHeaderPlusRowsAndAGap() {
        QuestPageLayout layout = new QuestPageLayout(134);

        assertEquals(QuestPageLayout.HEADER_HEIGHT, layout.height(page(0, 0)));
        assertEquals(QuestPageLayout.HEADER_HEIGHT + 2 * QuestPageLayout.ROW_HEIGHT,
                layout.height(page(2, 0)), "no gap when there is nothing to separate");
        assertEquals(QuestPageLayout.HEADER_HEIGHT + 2 * QuestPageLayout.ROW_HEIGHT,
                layout.height(page(0, 2)));
        assertEquals(QuestPageLayout.HEADER_HEIGHT + 2 * QuestPageLayout.ROW_HEIGHT
                        + QuestPageLayout.SECTION_GAP + QuestPageLayout.ROW_HEIGHT,
                layout.height(page(2, 1)),
                "the gap appears only when a task section and a reward section both exist");
    }

    @Test
    @DisplayName("hit testing agrees with the boxes for every pixel of a two-section page")
    void hitTestingAgreesWithTheBoxes() {
        QuestPageLayout layout = new QuestPageLayout(134);
        QuestPage page = page(2, 2);

        for (int y = -2; y < layout.height(page) + 2; y++) {
            Optional<QuestPageLayout.Hit> hit = layout.rowAt(page, y);
            if (y < 0 || y >= layout.height(page)) {
                assertTrue(hit.isEmpty(), "outside the page at y=" + y);
                continue;
            }
            if (layout.header().contains(0, y)) {
                assertTrue(hit.isPresent() && hit.get().header(), "header at y=" + y);
                continue;
            }
            if (hit.isEmpty()) {
                continue;
            }
            QuestPageLayout.Hit found = hit.get();
            QuestPageLayout.Box box = found.task()
                    ? layout.taskRow(found.index())
                    : layout.rewardRow(page, found.index());
            assertTrue(box.contains(0, y),
                    "hit says " + (found.task() ? "task " : "reward ") + found.index()
                            + " at y=" + y + " but that row does not contain it");
        }

        assertTrue(layout.rowAt(page, layout.taskRow(1).y()).get().task(), "the second task row");
        assertTrue(layout.rowAt(page, layout.rewardRow(page, 1).y()).get().index() == 1);
        assertTrue(layout.rowAt(page, layout.header().y()).get().header());
        int gapY = QuestPageLayout.HEADER_HEIGHT + 2 * QuestPageLayout.ROW_HEIGHT;
        assertTrue(layout.rowAt(page, gapY).isEmpty(), "the section gap is not a row");
    }

    @Test
    @DisplayName("every box sits inside its row, and rows do not overlap")
    void boxesSitInsideTheirRows() {
        QuestPageLayout layout = new QuestPageLayout(134);
        QuestPage page = page(3, 2);

        List<QuestPageLayout.Box> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(layout.taskRow(i));
        }
        for (int i = 0; i < 2; i++) {
            rows.add(layout.rewardRow(page, i));
        }
        for (QuestPageLayout.Box row : rows) {
            QuestPageLayout.Box icon = layout.icon(row);
            QuestPageLayout.Box text = layout.text(row);
            QuestPageLayout.Box bar = layout.bar(row);
            assertTrue(row.contains(icon.x(), icon.y()) && row.contains(icon.right() - 1, icon.bottom() - 1),
                    "the icon inside its row: " + icon + " in " + row);
            assertTrue(row.contains(text.x(), text.y()) && row.contains(text.right() - 1, text.bottom() - 1),
                    "the text column inside its row: " + text + " in " + row);
            assertTrue(row.contains(bar.x(), bar.y()) && row.contains(bar.right() - 1, bar.bottom() - 1),
                    "the bar inside its row: " + bar + " in " + row);
            assertTrue(text.bottom() <= bar.y(),
                    "the bar sits below the label, not through it: " + text + " then " + bar);
        }
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).bottom() <= rows.get(i).y(),
                    "rows do not overlap: " + rows.get(i - 1) + " then " + rows.get(i));
        }
    }

    @Test
    @DisplayName("a page narrower than its own text column is a caller bug and is refused")
    void tooNarrowIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuestPageLayout(QuestPageLayout.TEXT_X),
                "a width that leaves no text column is not a layout");
        assertFalse(new QuestPageLayout(QuestPageLayout.TEXT_X + 2).header().width() == 0);
    }
}
