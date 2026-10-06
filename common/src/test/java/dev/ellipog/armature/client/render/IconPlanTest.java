package dev.ellipog.armature.client.render;

import dev.ellipog.armature.client.render.IconPlan.Facts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which item icons take the flat path, and which go through the pipeline.
 *
 * <h2>Why the gates are asserted one at a time</h2>
 *
 * <p>Because each of them is a specific item that would go wrong in a specific way, and a rule tested
 * only with "a plain item is flat" passes while every one of them is broken. A tinted model drawn flat is
 * a <b>grey potion</b>: the texture is greyscale and the colour comes from {@code ItemColors}, so the gate
 * that misses it looks like a palette fault rather than a missing overlay. A 3-D model drawn flat is a
 * block shown as one of its six faces. A custom-rendered one is a chest with no chest in it.
 *
 * <p>The facts are booleans rather than a model, which is what makes this possible at all: the
 * version-specific accessors stay in {@code GuiGraphicsRenderer}, and the rule can be swept here with no
 * client.
 *
 * <p><b>No test can see the picture.</b> The preview harness draws no item icons -- it has no item
 * registry, so a node's icon is the state-coloured stand-in -- which means this rule is verified and its
 * <i>appearance</i> is not. That is a hand test, and it is written down as one.
 */
@DisplayName("the icon plan")
class IconPlanTest {

    /** A plain item: an item model, one texture, no tint, no world light. */
    private static final Facts PLAIN = new Facts(false, false, false, false, false, true, true);

    private static IconPlan of(boolean customRenderer, boolean gui3d, boolean usesBlockLight,
                               boolean tinted, boolean missingModel, boolean hasParticleIcon,
                               boolean oneSprite) {
        return IconPlan.of(new Facts(customRenderer, gui3d, usesBlockLight, tinted, missingModel,
                hasParticleIcon, oneSprite));
    }

    @Test
    @DisplayName("an item whose appearance is one textured quad is drawn flat")
    void aPlainItemIsFlat() {
        assertEquals(IconPlan.FLAT, IconPlan.of(PLAIN),
                "a stick, a diamond, a piece of paper: the pipeline would be a great deal of work to draw "
                        + "one quad");
    }

    @Test
    @DisplayName("a tinted model goes through the pipeline, or a potion is drawn grey")
    void aTintedModelIsLive() {
        // The gate that matters most. Leather armour, potions, tipped arrows, spawn eggs and modded
        // tiered tools are all one greyscale texture plus a colour: drawn flat they lose the colour, and
        // the fault reads as a broken palette rather than as a missing overlay.
        //
        // And the fact is a *colour* lookup rather than `isTinted`, which is the distinction that matters:
        // every `item/generated` quad carries tint index 0, because `ItemModelGenerator` passes the layer
        // number as the tint index — so a test on the index alone refuses every item in the game, which is
        // exactly what was measured before this was fixed (`flats 0` with nineteen thousand live
        // decisions a second).
        assertEquals(IconPlan.LIVE, of(false, false, false, true, false, true, true));
    }

    @Test
    @DisplayName("a model whose quads are not all one sprite goes through the pipeline")
    void aSecondSpriteIsLive() {
        // A blit draws the particle icon and nothing else, so a two-layer item — a base plus an overlay —
        // would be drawn as its base with the overlay missing. That is a missing layer rather than a tint,
        // which is why it is its own gate.
        assertEquals(IconPlan.LIVE, of(false, false, false, false, false, true, false));
    }

    @Test
    @DisplayName("a 3-D model goes through the pipeline, or a block is drawn as one face")
    void aThreeDimensionalModelIsLive() {
        assertEquals(IconPlan.LIVE, of(false, true, false, false, false, true, true));
        assertEquals(IconPlan.LIVE, of(false, false, true, false, false, true, true),
                "and a model that reads block light wants a world a GUI blit cannot supply");
    }

    @Test
    @DisplayName("a model with its own renderer goes through the pipeline")
    void aCustomRendererIsLive() {
        // A chest, a shulker box, a banner: the model is a placeholder and the picture comes from a block
        // entity renderer, which a sprite blit never calls.
        assertEquals(IconPlan.LIVE, of(true, false, false, false, false, true, true));
    }

    @Test
    @DisplayName("a missing model is not blitted as a confident purple square")
    void aMissingModelIsLive() {
        assertEquals(IconPlan.LIVE, of(false, false, false, false, true, true, true));
        assertEquals(IconPlan.LIVE, of(false, false, false, false, false, false, true),
                "and a model with no particle sprite at all has nothing to blit");
    }

    @Test
    @DisplayName("every gate is on its own: clearing any one of them is enough to go live")
    void everyGateIsIndependent() {
        // The sweep that stops a future gate being added and forgotten: each fact, alone, changes the
        // answer. A gate that did not would be a gate that is not read.
        for (int bit = 0; bit < 7; bit++) {
            assertEquals(IconPlan.LIVE, IconPlan.of(factsWithOnly(bit)),
                    "the fact at bit " + bit + " should be enough on its own");
        }
    }

    /** The plain item with one fact flipped to its live-making value. */
    private static Facts factsWithOnly(int bit) {
        return new Facts(bit == 0, bit == 1, bit == 2, bit == 3, bit == 4, bit != 5, bit != 6);
    }

    @Test
    @DisplayName("the counts are per decision, and a drain is what resets them")
    void theCountsAreDrainedRatherThanRead() {
        IconPlan.drain();

        IconPlan.counted(IconPlan.FLAT);
        IconPlan.counted(IconPlan.FLAT);
        IconPlan.counted(IconPlan.LIVE);

        assertEquals(new IconPlan.Counts(2, 1), IconPlan.drain(), "two flat, one live");
        assertEquals(new IconPlan.Counts(0, 0), IconPlan.drain(),
                "and the totals are from the last drain, so a reader once a second reads a rate");
        assertTrue(IconPlan.drain().flat() == 0);
    }
}
