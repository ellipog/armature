package dev.ellipog.armature.client.ui;

import dev.ellipog.armature.client.ui.kit.Easing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped themes: fifteen of them, in four families, and the look the project already had.
 *
 * <h2>Why the count and the distinctness are asserted rather than assumed</h2>
 *
 * <p>Because the names are the keys. They are what a setting stores, what a chapter names, and what the
 * picker's cycle walks — so two themes sharing one name makes one of them unreachable, and the lookup
 * silently returns whichever was declared first. "Fifteen" is asserted for a narrower reason: the four
 * families were a deliberate choice by the person this is for, and a family quietly losing a member is
 * the kind of thing that is noticed a year later.
 *
 * <h2>What {@link #everyBuiltInIsExpressedAsAPatch} is actually testing</h2>
 *
 * <p>Every theme here is a {@link ThemePatch} over {@code modern} rather than a hand-written full
 * palette, and that is the point rather than a convenience. It means the shipped set is itself proof
 * that the override mechanism is expressive enough for whatever a pack author will want — if fifteen
 * themes of four genuinely different characters can all be written as patches, then a quest file given
 * the same mechanism is not being offered a lesser version of it. Had the catalogue been full palettes
 * while patches were the "custom" path, the two would have diverged, and the divergence would only ever
 * show up as a file that could not do something a built-in could.
 */
@DisplayName("The shipped themes")
class ThemesTest {

    @BeforeEach
    @AfterEach
    void resetTheFiles() {
        // `Themes.byName` consults the built-ins only, but `Themes.any`, `everything` and `derivedName`
        // consult `ThemeFiles` -- which is static, so a theme another test wrote to a temporary
        // directory would still be resolvable here. A test that passes because of another test's state
        // is worse than one that fails, and unique-name assertions are exactly where it would hide.
        ThemeFiles.reset();
    }

    @Test
    @DisplayName("there are fifteen, with distinct names and distinct labels")
    void thereAreFifteenDistinctThemes() {
        assertEquals(15, Themes.ALL.size());

        Set<String> names = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (Theme theme : Themes.ALL) {
            assertTrue(names.add(theme.name()),
                    "two themes share the name '" + theme.name() + "', so one is unreachable");
            assertFalse(theme.name().isBlank());
            assertFalse(theme.name().contains(" "),
                    "a theme name is a JSON key and a filename: " + theme.name());

            // Labels matter separately from names, because the label is what the control shows. Two
            // themes whose labels read the same means clicking the control changes nothing a player can
            // see, which reads as a broken button rather than as a collision.
            assertTrue(labels.add(theme.displayName()),
                    "two themes both read as '" + theme.displayName() + "' in the picker");
        }
    }

    @Test
    @DisplayName("the four families are all present")
    void allFourFamiliesArePresent() {
        // The families are: the three originals, so nobody's muscle memory breaks; three chosen to
        // solve a problem rather than to look like somewhere; five drawn from the game's own materials
        // and dimensions; and two that are applications rather than places. Asserted by name because
        // the *order* is a design statement too -- a list of fifteen in random order is a list nobody
        // reads past the third entry.
        List<String> expected = List.of(
                "modern", "tome", "vanilla_plus",
                "high_contrast", "monochrome", "paper",
                "obsidian", "amethyst", "copper", "redstone",
                "nether", "end", "deep_dark",
                "terminal", "neon");
        assertEquals(expected, Themes.ALL.stream().map(Theme::name).toList());

        // And the originals keep their positions, so the toolbar's cycle does not reorder itself.
        assertEquals("modern", Themes.ALL.get(0).name());
        assertEquals("vanilla_plus", Themes.ALL.get(2).name());
    }

    @Test
    @DisplayName("modern states every colour, so nothing inherits a zero")
    void modernIsComplete() {
        // The reference theme is the one theme that states all forty-one values, and the reason is that
        // it is the theme every screenshot in the repository was taken with. A theme system that
        // quietly moved one pixel of the palette would make a rendering regression indistinguishable
        // from a colour decision.
        //
        // It is also what makes the seed safe. Themes are built from an all-zero array, which draws
        // nothing at all -- the most confusing possible failure, and one that would look like a
        // renderer bug rather than a missing colour. So this test is the thing standing between that
        // seed and a blank screen.
        assertEquals(ThemeToken.ALL.size(), Themes.MODERN_COLOURS.size(),
                "modern is the reference theme and every colour is stated in it");

        for (ThemeToken token : ThemeToken.ALL) {
            assertNotNull(Themes.MODERN_COLOURS.get(token.id()),
                    "modern does not state '" + token.id() + "', so it inherits the all-zero seed");
        }
    }

    @Test
    @DisplayName("a shipped theme states only what makes it itself, and inherits the rest")
    void shippedThemesArePatches() {
        // The claim these fifteen are built on, asserted on the theme where it is easiest to see:
        // `monochrome` is a palette of greys, and it says nothing about the locked-node wash or the row
        // hover, so both come from the theme underneath it.
        //
        // Worth testing on the shipped set rather than only on a synthetic patch, because the failure
        // this guards is the catalogue drifting away from the mechanism: had these been written as full
        // palettes while patches were the "custom" path, the two would have diverged, and the divergence
        // would only ever have shown up as a file that could not do something a built-in could.
        assertEquals(Themes.MODERN.nodeDim(), Themes.MONOCHROME.nodeDim(),
                "monochrome states nothing about the locked-node wash, so it should inherit modern's");
        assertEquals(Themes.MODERN.rowHover(), Themes.MONOCHROME.rowHover(),
                "and the row hover wash, for the same reason");

        // While genuinely moving the colours that make it what it is, so the assertions above are not
        // passing because nothing was patched at all.
        assertTrue(Themes.MODERN.panel() != Themes.MONOCHROME.panel(),
                "monochrome should have moved the panel colour, or it is not a patch over modern");
    }

    @Test
    @DisplayName("every theme resolves to a full palette, whatever it states")
    void everyThemeResolvesFully() {
        // A patch over a base must produce a theme indistinguishable in shape from the base -- same
        // count of colours, every one opaque or deliberately translucent, and the three non-colour
        // values present. This is what `Theme.from` guarantees, asserted across the whole catalogue.
        for (Theme theme : Themes.ALL) {
            int[] colours = theme.allColours();
            assertEquals(ThemeToken.ALL.size(), colours.length, theme.name());
            assertNotNull(theme.easing(), theme.name() + " has no easing curve");
            assertTrue(theme.cornerRadius() >= 0, theme.name());
            assertTrue(theme.motion() >= 0L, theme.name());
            for (int argb : colours) {
                assertTrue((argb >>> 24) != 0,
                        theme.name() + " resolved a transparent colour: " + String.format("#%08X", argb));
            }
        }
    }

    @Test
    @DisplayName("the utility themes carry the zeroes that prove the parameters work")
    void theUtilityThemesCarryZeroes() {
        // Two themes exist partly as tests of the code rather than of the palette, and each zero is the
        // case an implementation is most likely to get wrong:
        //
        //  * `vanilla_plus` has a radius of 0 and a motion of 0. A radius of zero is "square", and an
        //    implementation that clamps a radius to a minimum of one cannot express it. A motion of zero
        //    is "instant", not "unset" -- an earlier round read it as unset and used the built-in
        //    duration, so this theme silently animated like `modern`.
        //  * `high_contrast` also has no motion. Bright, instant, unmissable.
        assertEquals(0, Themes.VANILLA_PLUS.cornerRadius());
        assertEquals(0L, Themes.VANILLA_PLUS.motion());
        assertEquals(Easing.LINEAR, Themes.VANILLA_PLUS.easing());

        assertEquals(0L, Themes.HIGH_CONTRAST.motion());
        assertEquals(0, Themes.HIGH_CONTRAST.cornerRadius());

        // And a theme with motion, so "zero means instant" is not the only case exercised.
        assertTrue(Themes.MODERN.motion() > 0L);
        assertTrue(Themes.TOME.motion() > 0L);
        assertTrue(Themes.AMETHYST.cornerRadius() > Themes.MODERN.cornerRadius(),
                "amethyst is the roundest theme, which makes the radius visible at a glance");
    }

    @Test
    @DisplayName("paper is a light theme, which is what makes it worth shipping")
    void paperInvertsTheRelationship() {
        // The only theme where text is darker than the surface behind it. That is worth shipping even
        // if nobody plays on it, because anywhere the toolkit assumed "brighter means more important",
        // or drew a dark shadow, or used white for a highlight, this theme makes it visible -- and a
        // palette of dark themes cannot find those, because in all of them the assumption is true.
        assertTrue(luminance(Themes.PAPER.panel()) > luminance(Themes.PAPER.body()),
                "paper's panel should be lighter than its body text, or it is not a light theme");
        assertTrue(luminance(Themes.MODERN.panel()) < luminance(Themes.MODERN.body()),
                "modern's panel should be darker than its body text, so the two themes are opposites");

        // The dim is a pale haze rather than a dark one, which is the one place a light theme has to
        // make a real decision: dimming to black under a pale panel reads as a bug.
        assertTrue(luminance(Themes.PAPER.dim()) > 0x180,
                "paper's dim should be a bright haze, not a dark one");
    }

    @Test
    @DisplayName("monochrome has no hue anywhere, so a screen that relies on one is visibly broken")
    void monochromeIsGrey() {
        // Included as a test as much as a theme. Every state is a distinct brightness and nothing else,
        // so any screen that leans on hue to tell available from complete is unreadable in it -- which
        // is worth being able to check in one click rather than by imagining it.
        for (ThemeToken token : ThemeToken.ALL) {
            int argb = Themes.MONOCHROME.colour(token.id());
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            assertTrue(r == g && g == b,
                    "monochrome's '" + token.id() + "' is " + String.format("#%08X", argb)
                            + ", which has a hue in it");
        }

        // And the four states are told apart by brightness, far enough apart to matter.
        int available = luminance(Themes.MONOCHROME.available());
        int inProgress = luminance(Themes.MONOCHROME.inProgress());
        int complete = luminance(Themes.MONOCHROME.complete());
        assertTrue(complete > available && available > inProgress,
                "the three readable states should differ in brightness: complete " + complete
                        + ", available " + available + ", in progress " + inProgress);
    }

    @Test
    @DisplayName("every built-in is expressed as a patch, by construction")
    void everyBuiltInIsExpressedAsAPatch() {
        // Not directly observable from a finished Theme, so this asserts the property that *is*: each
        // one resolves through `ThemePatch.applyTo` to a theme whose non-colour values are either the
        // base's or its own, and never a leftover. `tome` is the concrete case -- it changes the radius
        // and nothing else non-colour, so a patch that dropped the base's radius would show up here.
        assertEquals(Easing.QUAD_OUT, Themes.TOME.easing(),
                "tome should inherit modern's curve, since it does not set one");
        assertEquals(Themes.MODERN.motion(), Themes.TOME.motion(),
                "tome should inherit modern's duration, since it does not set one");
        assertEquals(8, Themes.TOME.cornerRadius(), "and override the radius, which it does set");

        // A patch that does set them, for the other half.
        assertEquals(Easing.LINEAR, Themes.TERMINAL.easing());
        assertEquals(60L, Themes.TERMINAL.motion());
    }

    @Test
    @DisplayName("names() lists them all, so a warning can tell an author what exists")
    void namesAreListed() {
        String listed = Themes.names();
        for (Theme theme : Themes.ALL) {
            assertTrue(listed.contains(theme.name()),
                    "names() omits " + theme.name() + ", so a player cannot discover it: " + listed);
        }
    }

    @Test
    @DisplayName("everything() is the built-ins, plus whatever a file added")
    void everythingIncludesFiles() {
        // What a picker shows and what the cycle walks. It has to include file-loaded themes or a theme
        // saved in game would be selectable only by editing a file -- reachable, but not clickable.
        assertEquals(Themes.ALL.size(), Themes.everything().size());
        for (Theme theme : Themes.ALL) {
            assertTrue(Themes.everything().contains(theme));
        }
    }

    @Test
    @DisplayName("a derived name never collides with one that already exists")
    void derivedNamesDoNotCollide() {
        // Used when the editor saves a theme without being given a name. A derived name matching an
        // existing theme would shadow it, and a picker listing two entries with one name is a control
        // that appears not to work.
        assertEquals("modern_edited", Themes.derivedName("modern", "edited"),
                "with nothing in the way, the obvious name is used");

        // `modern` already exists, so a derivation from it must sidestep. This asserts the *shape* of
        // the fallback rather than a specific suffix, since the number appended is an implementation
        // detail -- what matters is that the answer is not already taken.
        String derived = Themes.derivedName("tome", "edited");
        assertNull(Themes.any(derived), "a derived name resolved to a theme that already existed");
        assertFalse(derived.isBlank());
        assertTrue(derived.matches("[a-z0-9_\\-]+"),
                "a derived name becomes a filename and a JSON key: " + derived);
    }

    @Test
    @DisplayName("an id the registry does not have is reported, not applied")
    void unknownTokensAreReported() {
        // The failure this exists to prevent: a misspelled key that silently never applies, leaving a
        // theme that is mostly right and looks like a decision. `ThemePatch` collects these so a caller
        // can name them.
        ThemePatch patch = ThemePatch.colours(Map.of("panel", 0xFF112233, "panell", 0xFF445566));
        assertEquals(Set.of("panell"), patch.unknownTokens());

        // And it still applies the rest, which is the deliberate tolerance: dropping the whole patch
        // because one of forty keys is wrong would leave a chapter that looks like nothing happened.
        Theme tinted = patch.tint(Themes.MODERN);
        assertEquals(0xFF112233, tinted.panel());
    }

    @Test
    @DisplayName("tokenCatalogue lists every token, for a file format or an editor to publish")
    void theCatalogueIsComplete() {
        var catalogue = ThemePatch.tokenCatalogue();
        assertEquals(ThemeToken.ALL.size(), catalogue.size());

        Set<String> ids = new LinkedHashSet<>();
        for (var element : catalogue) {
            JsonObject entry = element.getAsJsonObject();
            assertTrue(entry.has("id") && entry.has("label") && entry.has("group"));
            ids.add(entry.get("id").getAsString());
        }
        for (ThemeToken token : ThemeToken.ALL) {
            assertTrue(ids.contains(token.id()), "the catalogue omits " + token.id());
        }
    }

    private static int luminance(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }
}
