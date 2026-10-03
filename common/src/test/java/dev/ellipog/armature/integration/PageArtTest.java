package dev.ellipog.armature.integration;

import dev.ellipog.armature.client.render.RecordingRenderer;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared page arithmetic: the truncation fit, the status pill, and a reward's standing.
 *
 * <p>Read through {@link RecordingRenderer}, which fixes a character at six pixels, so every number
 * here is checkable by hand. That is the point of moving these three out of the adapters: the pill a
 * player sees in EMI is the pill JEI and REI draw, and the trim that keeps a label inside its column
 * is one rule rather than three copies that drift.
 */
class PageArtTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    private static QuestRow reward(boolean done, boolean locked, boolean claimable) {
        return new QuestRow(ItemStack.EMPTY, "Diamond", 0, 0, done, locked, claimable, "", 0);
    }

    @Test
    @DisplayName("text that fits is untouched, and a column of nothing yields nothing")
    void fitLeavesShortTextAlone() {
        RecordingRenderer renderer = RecordingRenderer.create();

        assertEquals("Ready", PageArt.fit(renderer, "Ready", 60));
        assertEquals("", PageArt.fit(renderer, "Ready", 0));
    }

    @Test
    @DisplayName("text too wide is cut to the widest prefix the ellipsis still fits in")
    void fitTruncatesWithAsciiEllipsis() {
        RecordingRenderer renderer = RecordingRenderer.create();

        // Six pixels a character: "Ready" is 30, and 24 leaves room for one character and "...".
        assertEquals("R...", PageArt.fit(renderer, "Ready", 24));
        assertEquals("Ready", PageArt.fit(renderer, "Ready", 30),
                "exactly wide enough is wide enough");
    }

    @Test
    @DisplayName("a pill is its word plus padding, filled in its colour with white text on it")
    void aPillIsTheWordPlusPadding() {
        RecordingRenderer renderer = RecordingRenderer.create();

        int width = PageArt.pill(renderer, "Ready", 4, 6, PagePalette.COMPLETE);

        assertEquals(36, width, "30 for the word and 3 of padding on each side");
        List<RecordingRenderer.Call> fills = renderer.fills();
        assertEquals(1, fills.size());
        assertEquals("fill(4,6 -> 40,16, #FF2E7D32)", fills.get(0).toString());
        List<RecordingRenderer.Call> texts = renderer.texts();
        assertEquals(1, texts.size());
        assertEquals("text(\"Ready\" at 7,7, #FFFFFFFF)", texts.get(0).toString());
        assertEquals(PageArt.PILL_HEIGHT, fills.get(0).y2() - fills.get(0).y());
    }

    @Test
    @DisplayName("a reward's standing follows its flags, locked first")
    void rewardStatusFollowsTheFlags() {
        assertEquals(QuestContent.RewardStatus.READY, PageArt.rewardStatus(reward(false, false, true)));
        assertEquals(QuestContent.RewardStatus.CLAIMED, PageArt.rewardStatus(reward(true, false, false)));
        assertEquals(QuestContent.RewardStatus.LOCKED, PageArt.rewardStatus(reward(false, true, false)));
        assertEquals(QuestContent.RewardStatus.LOCKED, PageArt.rewardStatus(reward(true, true, false)),
                "a locked row is locked whatever else is true of it");
        assertNull(PageArt.rewardStatus(reward(false, false, false)),
                "an unfinished quest has nothing to say about a reward");
    }

    @Test
    @DisplayName("each status has its own colour")
    void rewardStatusColours() {
        assertEquals(PagePalette.COMPLETE, PageArt.rewardStatusColour(QuestContent.RewardStatus.READY));
        assertEquals(PagePalette.LOCKED, PageArt.rewardStatusColour(QuestContent.RewardStatus.LOCKED));
        assertEquals(PagePalette.MUTED, PageArt.rewardStatusColour(QuestContent.RewardStatus.CLAIMED));
    }

    @Test
    @DisplayName("a pinned star is solid and an idle one is the same star's outline")
    void theStarIsSolidWhenPinnedAndHollowWhenNot() {
        RecordingRenderer pinned = RecordingRenderer.create();
        PageArt.star(pinned, 100, 50, PagePalette.PIN_ACTIVE, true);
        RecordingRenderer idle = RecordingRenderer.create();
        PageArt.star(idle, 100, 50, PagePalette.PIN_IDLE, false);

        assertEquals(11, PageArt.starSize());
        assertTrue(covers(pinned, 105, 55), "the solid star covers its interior");
        assertFalse(covers(idle, 105, 55), "the hollow one does not -- that is what hollow means");
        assertTrue(covers(idle, 105, 50), "but it still draws the shape's edge at the top point");
        assertTrue(pinned.fills().stream().allMatch(call -> call.argb() == PagePalette.PIN_ACTIVE));
        assertTrue(idle.fills().stream().allMatch(call -> call.argb() == PagePalette.PIN_IDLE));
    }

    /** Whether any recorded fill covers a pixel. */
    private static boolean covers(RecordingRenderer renderer, int px, int py) {
        return renderer.fills().stream().anyMatch(call -> call.covers(px, py));
    }

    @Test
    @DisplayName("a quest's outputs are its item rewards, or its icon when it has none")
    void outputsFallBackToTheQuestIcon() {
        ItemStack icon = new ItemStack(Items.STONE);
        ItemStack reward = new ItemStack(Items.DIAMOND);
        ItemStack category = new ItemStack(Items.WRITABLE_BOOK);

        QuestRef quest = new QuestRef("q", "Title", "Chapter", icon, "");
        QuestPage withReward = new QuestPage(quest, List.of(),
                List.of(new QuestRow(reward, "Diamond", 0, 0, false, false, true, "", 0)));
        QuestPage withoutReward = new QuestPage(quest, List.of(), List.of());

        assertEquals(List.of(reward), PageArt.outputs(withReward, category),
                "item rewards come first and are the whole list when they exist");
        assertEquals(List.of(icon), PageArt.outputs(withoutReward, category),
                "an xp-only quest is still pinnable, through its own icon");

        QuestPage iconless = new QuestPage(new QuestRef("q", "Title", "Chapter", ItemStack.EMPTY, "gone:x"), List.of(), List.of());
        assertEquals(List.of(category), PageArt.outputs(iconless, category),
                "and with no icon at all the category's stands in -- never an empty stack");
        assertTrue(PageArt.outputs(iconless, ItemStack.EMPTY).isEmpty(),
                "the only empty answer is when there is nothing anywhere to draw");
    }
}
