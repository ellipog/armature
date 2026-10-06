package dev.ellipog.armature.client;

import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.ScrollBar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scrollbar's appearance, and the one property that makes deriving it safe.
 *
 * <h2>Why there is a test here at all, given the class has no arithmetic</h2>
 *
 * <p>Because the decision this class embodies is a claim about all sixteen themes, and the claim is
 * the only thing standing between "no new tokens" and a theme whose grip vanishes under the pointer.
 *
 * <p>The theme declares two scrollbar colours. The hover and held appearances are shades of the grip,
 * and the direction of that shade is taken from the theme's own pair — away from the groove — rather
 * than chosen per theme. That rule is either true of every built-in or it is wrong, and there is no
 * way to find out by looking at one of them: a dark theme and a light theme need opposite signs, and
 * the two extremes (a grip already white, a grip already black) cannot move at all. So the sweep is
 * the test, over {@link Themes#ALL}, and what it asserts is exactly the property that matters —
 * <b>a hover never makes the grip harder to find than it already was.</b>
 *
 * <p>The alternative design was two more tokens and thirty hand-picked values, and what this test
 * would have had to become is worth stating, because it is the argument against it: "does each theme's
 * chosen hover colour read against its chosen track" is a judgement, and the closest a test gets to it
 * is a luminance comparison like this one — which the derived colours satisfy by construction.
 */
@DisplayName("the scrollbar's appearance")
class ArmatureScrollStyleTest {

    /** The grip that cannot be moved further from its track in the direction it is going. */
    private static final int WHITE = 0xFFFFFF;
    private static final int BLACK = 0x000000;

    @BeforeEach
    @AfterEach
    void resetTheme() {
        // `ArmatureTheme.current()` is process-wide: see `ThemeTest` for why both annotations are here.
        ArmatureTheme.resetCurrent();
    }

    @Test
    @DisplayName("the skin is the theme's own pair, and two shades of its grip")
    void theSkinComesFromTheTheme() {
        ArmatureTheme.setCurrent(Themes.MODERN);
        ScrollBar.Skin skin = ArmatureScrollStyle.skin();

        assertEquals(Themes.MODERN.scrollTrack(), skin.track(), "the groove is the theme's");
        assertEquals(Themes.MODERN.scrollThumb(), skin.thumb(), "and so is the grip");
        assertTrue(skin.thumbHover() != skin.thumb(), "which a hover moves");
        assertTrue(skin.thumbActive() != skin.thumbHover(), "and a drag moves further");
    }

    @Test
    @DisplayName("a hover and a drag always move the grip away from its groove, never towards it")
    void everyThemeIsMoreVisibleWhenPointedAt() {
        int moved = 0;

        for (Theme theme : Themes.ALL) {
            ArmatureTheme.setCurrent(theme);
            ScrollBar.Skin skin = ArmatureScrollStyle.skin();

            String at = theme.name() + ": grip " + hex(skin.thumb()) + " on groove " + hex(skin.track());
            int rest = distance(skin.thumb(), skin.track());
            int hover = distance(skin.thumbHover(), skin.track());
            int active = distance(skin.thumbActive(), skin.track());

            assertTrue(hover >= rest, "a hover must not make the grip harder to find — " + at);
            assertTrue(active >= hover, "nor a drag, which has to outrank the hover — " + at);

            // A grip already at the end it is travelling towards cannot move: a white grip on a black
            // groove has nowhere brighter to go, and that is the correct outcome rather than an
            // exception to the rule. Every other theme is required to actually change, so a rule that
            // quietly did nothing everywhere would fail here rather than pass by vacuous satisfaction.
            if (!atExtreme(skin.thumb(), skin.track())) {
                assertTrue(hover > rest, "a hover has to be a visible change — " + at);
                assertTrue(active > hover, "and a drag a further one — " + at);
                moved++;
            }

            assertEquals(Colour.alpha(skin.thumb()), Colour.alpha(skin.thumbHover()),
                    "shading a colour does not shade whether it is there — " + at);
            assertEquals(255, Colour.alpha(skin.thumbActive()));
        }

        assertTrue(moved >= Themes.ALL.size() - 2,
                "only a grip already at an extreme can fail to move, and at most two of the sixteen "
                        + "reach one — a white grip on a black groove, or the reverse; " + moved
                        + " of " + Themes.ALL.size() + " did move");
    }

    /** Whether a grip is already as far from its groove as the shade it is about to receive can take it. */
    private static boolean atExtreme(int thumb, int track) {
        boolean awayFromBlack = Colour.luminance(thumb) >= Colour.luminance(track);
        return (thumb & 0xFFFFFF) == (awayFromBlack ? WHITE : BLACK);
    }

    /** How far a grip is from its groove, in the direction it is already going. */
    private static int distance(int thumb, int track) {
        return Math.abs(Colour.luminance(thumb) - Colour.luminance(track));
    }

    private static String hex(int argb) {
        return Colour.toHex(argb);
    }
}
