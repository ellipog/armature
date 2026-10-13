package dev.ellipog.armature.client;

import dev.ellipog.armature.client.render.RecordingRenderer;
import dev.ellipog.armature.client.ui.Themes;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla chrome: the bevel panel, the button sprite for each state, and when any of it applies.
 *
 * <h2>Why pixels rather than pictures</h2>
 *
 * <p>A bevel is five fills in a fixed arrangement, and the arrangement is the whole feature: white
 * top and left, dark bottom and right, black outside, the caller's fill inside. A recorder sees
 * which colour covers which point, which is exactly the question a screenshot answers and the
 * one a flat panel answers differently.
 */
@DisplayName("VanillaChrome")
class VanillaChromeTest {

    @BeforeEach
    void resetTheme() {
        ArmatureTheme.resetCurrent();
    }

    @Test
    @DisplayName("a vanilla panel is a bevel: black outside, lit top and left, dark bottom and right")
    void thePanelIsABevel() {
        ArmatureTheme.setCurrent(Themes.VANILLA);
        RecordingRenderer r = RecordingRenderer.create();

        ArmatureTheme.panel(r, 10, 10, 60, 20, 0xFF123456, 0xFF00FF00);

        // The border argument is set aside with the radius: a bevel's edges are highlight and
        // shadow, not one ring, so no fill anywhere may carry the border colour.
        assertFalse(r.fills().stream().anyMatch(call -> call.argb() == 0xFF00FF00));
        assertTrue(r.covered(10, 10), "the footprint starts at the panel's own corner");
        assertEquals(0xFF000000, colourAt(r, 10, 10), "whose outer pixel is black");
        assertEquals(0xFFFFFFFF, colourAt(r, 30, 11), "the top inside it is lit");
        assertEquals(0xFFFFFFFF, colourAt(r, 11, 15), "and the left inside it");
        assertEquals(0xFF373737, colourAt(r, 30, 28), "the bottom inside is shadowed");
        assertEquals(0xFF373737, colourAt(r, 68, 15), "and the right inside it");
        assertEquals(0xFF123456, colourAt(r, 30, 15), "while the middle keeps the caller's fill");
    }

    @Test
    @DisplayName("a box too small for a bevel degrades to a fill rather than drawing nothing")
    void aTinyPanelIsAFlatFill() {
        ArmatureTheme.setCurrent(Themes.VANILLA);
        RecordingRenderer r = RecordingRenderer.create();

        ArmatureTheme.panel(r, 4, 4, 2, 2, 0xFF123456, 0xFF000000);

        assertEquals(1, r.fills().size());
        assertEquals(0xFF123456, r.fills().get(0).argb());
    }

    @Test
    @DisplayName("flat themes still draw the bordered panel they always drew")
    void flatThemesAreUntouched() {
        // The default is square, so the footprint assertion needs no allowance for cut corners.
        ArmatureTheme.setCurrent(Themes.DEFAULT);
        RecordingRenderer r = RecordingRenderer.create();

        ArmatureTheme.panel(r, 10, 10, 60, 20, 0xFF123456, 0xFF000000);

        assertEquals(0xFF000000, colourAt(r, 10, 10), "the footprint is the border colour");
        assertEquals(0xFF123456, colourAt(r, 30, 15), "inset by one pixel of it");
        assertTrue(r.guiSprites().isEmpty(), "and no sprite anywhere");
    }

    @Test
    @DisplayName("the button sprite follows active, hovered and held")
    void theButtonSpriteFollowsState() {
        assertEquals(VanillaChrome.BUTTON, VanillaChrome.buttonSprite(true, false));
        assertEquals(VanillaChrome.BUTTON_HIGHLIGHTED, VanillaChrome.buttonSprite(true, true),
                "under the pointer the button wears its white border, and held is a hover that"
                        + " has not let go yet — vanilla has no pressed sprite of its own");
        assertEquals(VanillaChrome.BUTTON_DISABLED, VanillaChrome.buttonSprite(false, false));
        assertEquals(VanillaChrome.BUTTON_DISABLED, VanillaChrome.buttonSprite(false, true),
                "a button that cannot be used never highlights, whatever the pointer does");
    }

    @Test
    @DisplayName("chrome follows the scope, so a vanilla chapter keeps its bevels in a flat book")
    void chromeFollowsTheScope() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        assertFalse(VanillaChrome.inForce());

        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(Themes.VANILLA)) {
            assertTrue(VanillaChrome.inForce(), "inside the scope the chapter's chrome rules");
        }
        assertFalse(VanillaChrome.inForce(), "and outside it the book's does");
    }

    @Test
    @DisplayName("the tooltip box is procedural fill and two-tone frame, never an atlas lookup")
    void theTooltipBoxIsProcedural() {
        // 1.21.1 has no tooltip/* sprites — its client jar ships widget/* only — so a sprite id
        // for one is the missing-texture checkerboard. The box is the fill and frame colours
        // TooltipRenderUtil paints, drawn as fills.
        RecordingRenderer r = RecordingRenderer.create();

        VanillaChrome.tooltipBox(r, 10, 10, 40, 20);

        assertTrue(r.guiSprites().isEmpty(), "no atlas lookup in a tooltip box");
        assertEquals(0xF0100010, colourAt(r, 30, 20), "near-black middle");
        assertEquals(0x505000FF, colourAt(r, 30, 10), "purple crown");
        assertEquals(0x505000FF, colourAt(r, 10, 15), "down the upper sides");
        assertEquals(0x5028007F, colourAt(r, 30, 29), "dark violet foot");
        assertEquals(0x5028007F, colourAt(r, 10, 25), "up the lower sides");
    }

    @Test
    @DisplayName("the sprite ids name 1.21.1's GUI atlas")
    void theSpriteIdsAreVanillaGuiSprites() {
        // Pinned against the 1.21.1 client jar, which ships exactly these widget sprites. An id
        // added from a newer version's assets is a missing-texture box in this one — the tooltip
        // pair is the case that proved it, so no tooltip id may ever live here.
        assertEquals(Identifier.tryParse("minecraft:widget/button"), VanillaChrome.BUTTON);
        assertEquals(Identifier.tryParse("minecraft:widget/button_disabled"),
                VanillaChrome.BUTTON_DISABLED);
        assertEquals(Identifier.tryParse("minecraft:widget/button_highlighted"),
                VanillaChrome.BUTTON_HIGHLIGHTED);
    }

    /** The topmost fill covering a point: later draws win, which is how panels layer. */
    private static int colourAt(RecordingRenderer r, int x, int y) {
        int colour = 0;
        for (RecordingRenderer.Call fill : r.fills()) {
            if (fill.covers(x, y)) {
                colour = fill.argb();
            }
        }
        return colour;
    }
}
