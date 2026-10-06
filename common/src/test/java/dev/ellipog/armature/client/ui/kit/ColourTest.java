package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour arithmetic, and the two mistakes it exists to make impossible.
 *
 * <h2>Why a test for four lines of arithmetic</h2>
 *
 * <p>Because both mistakes here produce a <i>plausible</i> colour rather than an error, and a plausible
 * colour is invisible until someone compares two screenshots. Blending the alpha when the intent was to
 * fade walks the hue; letting an overshooting curve past 1 wraps a channel through black. Neither
 * throws, neither logs, and both look like a palette decision rather than a bug.
 */
@DisplayName("Colour arithmetic")
class ColourTest {

    private static final int OPAQUE_RED = 0xFFFF0000;
    private static final int OPAQUE_BLUE = 0xFF0000FF;

    @Test
    @DisplayName("the endpoints are returned exactly, so a settled animation equals its own constant")
    void endpointsAreExact() {
        // The property the whole animation design leans on: an eased transition that has finished is
        // bit-identical to the colour it settled on. A blend that returned 0.999 of the way would
        // leave every hovered control imperceptibly the wrong colour, and nobody would ever find it.
        assertEquals(OPAQUE_RED, Colour.lerp(OPAQUE_RED, OPAQUE_BLUE, 0F));
        assertEquals(OPAQUE_BLUE, Colour.lerp(OPAQUE_RED, OPAQUE_BLUE, 1F));
    }

    @Test
    @DisplayName("a blend out of range is clamped, so an overshooting curve cannot wrap a channel")
    void outOfRangeBlendsAreClamped() {
        // Easing.BACK_OUT deliberately passes 1 in the middle. Un-clamped, a channel would exceed 255
        // and the cast to a byte would wrap — so a colour animating towards white would flash through
        // black for a frame or two, which is the least explicable artefact a UI can produce.
        assertEquals(OPAQUE_BLUE, Colour.lerp(OPAQUE_RED, OPAQUE_BLUE, 1.5F));
        assertEquals(OPAQUE_RED, Colour.lerp(OPAQUE_RED, OPAQUE_BLUE, -0.5F));

        // And with an overshooting curve actually applied, which is the case that reaches it.
        for (int step = 0; step <= 100; step++) {
            int blended = Colour.lerp(OPAQUE_RED, OPAQUE_BLUE, Easing.BACK_OUT.ease(step / 100F));
            assertEquals(0xFF, Colour.alpha(blended), "alpha left the range at step " + step);
            assertTrue((blended >> 16 & 0xFF) <= 0xFF);
        }
    }

    @Test
    @DisplayName("a blend moves every channel including alpha, which is right for replacing a colour")
    void aBlendMovesAllFourChannels() {
        // Halfway between opaque red and fully transparent blue is half-transparent purple. Asserted
        // rather than described because this is the case a caller sometimes does not want — see the
        // next test — and the two are easy to reach for interchangeably.
        int blended = Colour.lerp(0xFFFF0000, 0x000000FF, 0.5F);

        assertEquals(0x80, Colour.alpha(blended), "the alpha did not blend");
        assertEquals(0x80, (blended >> 16) & 0xFF, "the red did not blend");
        assertEquals(0x80, blended & 0xFF, "the blue did not blend");
    }

    @Test
    @DisplayName("translucent keeps the colour and moves only the alpha, which is right for fading")
    void translucentOnlyMovesAlpha() {
        // The other half of the pair, and the distinction is the point: fading a red panel with
        // `lerp` towards a transparent colour walks its RGB, so it fades through pink. Fading it with
        // `translucent` keeps it red at every step.
        int half = Colour.translucent(0xFFFF0000, 0.5F);

        assertEquals(0x80, Colour.alpha(half));
        assertEquals(0xFF0000, Colour.rgb(half), "translucent changed the colour instead of only the alpha");
    }

    @Test
    @DisplayName("alphaOf sets an absolute alpha, and clamps")
    void alphaOfSetsAnAbsoluteAlpha() {
        assertEquals(0x00FF0000, Colour.alphaOf(0xFFFF0000, 0F));
        assertEquals(0xFFFF0000, Colour.alphaOf(0xFFFF0000, 1F));
        assertEquals(0x80FF0000, Colour.alphaOf(0xFFFF0000, 0.5F));

        // Clamped, because a theme (R6) will express these as data and a mistyped 1.4 must not wrap.
        assertEquals(0xFFFF0000, Colour.alphaOf(0xFFFF0000, 1.4F));
        assertEquals(0x00FF0000, Colour.alphaOf(0xFFFF0000, -0.2F));
    }

    @Test
    @DisplayName("withAlpha clamps rather than wrapping into a different colour")
    void withAlphaClamps() {
        // 256 through the shift would set bit 8 and produce a colour with the red channel at zero —
        // so a panel meant to become opaque becomes black instead, which is a whole-panel artefact
        // from a one-off mistake.
        assertEquals(0xFFFF0000, Colour.withAlpha(0xFFFF0000, 300));
        assertEquals(0x00FF0000, Colour.withAlpha(0xFFFF0000, -5));
        assertEquals(0x80FF0000, Colour.withAlpha(0xFFFF0000, 128));
    }

    @Test
    @DisplayName("shading moves towards black or white and leaves the alpha alone")
    void shadingPreservesAlpha() {
        // Alpha is preserved deliberately: "darker" is a statement about the colour, not about whether
        // the thing is there. Shading the alpha too would make a hover fade a panel towards invisible.
        int halfTransparent = 0x80FF0000;

        int darker = Colour.shade(halfTransparent, -1F);
        assertEquals(0x80000000, darker, "shading to black did not produce black at the same alpha");

        int lighter = Colour.shade(halfTransparent, 1F);
        assertEquals(0x80FFFFFF, lighter, "shading to white did not produce white at the same alpha");

        // Halfway towards black is a darker red, not a different hue.
        int halfway = Colour.shade(0xFFFF0000, -0.5F);
        assertEquals(0x80, (halfway >> 16) & 0xFF);
        assertEquals(0xFF, Colour.alpha(halfway), "shading moved the alpha");
    }

    @Test
    @DisplayName("shade clamps past its range rather than inverting")
    void shadeClamps() {
        // -2 would otherwise walk past black and *towards white again*, so a colour darkened twice
        // would come out brighter than one darkened once.
        assertEquals(0xFF000000, Colour.shade(0xFFFFFFFF, -2F));
        assertEquals(0xFFFFFFFF, Colour.shade(0xFF000000, 2F));
    }

    @Test
    @DisplayName("luminance orders two colours by brightness and ignores the alpha")
    void luminanceComparesBrightness() {
        // The one question this answers is "which of these two reads as the lighter one?", and it is asked
        // by exactly one caller: the scrollbar's hover and held shades go *away from the track*, so a dark
        // theme brightens its grip and a light theme darkens it. A rule like that is either true of every
        // theme or wrong in half of them, which is why it is derived from the colours rather than chosen.
        assertEquals(0, Colour.luminance(0xFF000000));
        assertEquals(765, Colour.luminance(0xFFFFFFFF));
        assertEquals(255, Colour.luminance(0xFFFF0000), "one channel at full is a third of white");
        assertTrue(Colour.luminance(0xFF808080) > Colour.luminance(0xFF404040),
                "a grey is brighter than a darker grey");
        assertTrue(Colour.luminance(0xFF9A8E76) < Colour.luminance(0xFFE2DACA),
                "a pale theme's grip reads darker than the groove it runs in -- which is the case the "
                        + "sign is taken from");

        // Alpha excluded, for the same reason `shade` preserves it: "lighter" is a statement about a
        // colour, not about whether the thing is there, and several themes' tracks are washes.
        assertEquals(Colour.luminance(0xFFFF0000), Colour.luminance(0x40FF0000));
    }

    @Test
    @DisplayName("an invisible colour is identifiable, so a caller can skip the call")
    void invisibleIsDetectable() {
        // The six-digit form is the mistake this catches: 0xRRGGBB has an alpha of zero, so a colour
        // written without its alpha channel is fully transparent and draws nothing at all.
        assertTrue(Colour.invisible(0x00FF0000));
        assertTrue(Colour.invisible(0));
        assertFalse(Colour.invisible(0xFF000000));

        // And a barely-visible colour is still visible: the distinction is exactly zero, not "nearly".
        // A threshold here would be a guess about perception, and a caller that wants to skip an
        // invisible fill wants to skip one that truly draws nothing.
        assertFalse(Colour.invisible(Colour.alphaOf(0xFF000000, 1 / 255F)));
    }
}
