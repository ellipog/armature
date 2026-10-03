package dev.ellipog.armature.integration;

import dev.ellipog.armature.client.render.RecordingRenderer;

import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
