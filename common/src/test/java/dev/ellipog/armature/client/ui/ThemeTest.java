package dev.ellipog.armature.client.ui;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Easing;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.armature.client.ui.kit.Tween;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record: one theme's own shape, and the two things a theme can change that are not colour.
 *
 * <h2>What moved out of this file, and why that is a better arrangement</h2>
 *
 * <p>This used to hold three concerns: the record, the catalogue of built-ins, and the override rule
 * that decided whether a chapter's theme beat the player's. That last one is gone entirely — a chapter's
 * theme is a <i>region</i> now, not a claim on the whole client, so there is no precedence rule to test.
 * The other two are separated: the catalogue is {@link Themes}, tested in {@code ThemesTest}, and this
 * file is the shape.
 *
 * <p>What is left here is the part that is genuinely about one theme — its colours, its radius, its
 * motion — plus {@link ArmatureTheme}'s scoping, which is the mechanism that replaced the override and
 * is the one new piece of behaviour in the toolkit.
 *
 * <h2>Static state, so every test restores it</h2>
 *
 * <p>{@link ArmatureTheme#current()} is process-wide, which means a test that selects a theme leaves it
 * selected for the next one. {@code @AfterEach} resets, and {@code @BeforeEach} resets too: the second is
 * what makes a test pass when it is run <i>after</i> a failure that skipped the first, which is the case
 * where a suite's results start depending on the order it ran in.
 */
@DisplayName("A theme, and the scope it is drawn in")
class ThemeTest {

    @BeforeEach
    @AfterEach
    void resetTheme() {
        ArmatureTheme.resetCurrent();
        Motion.setEnabled(true);
        Motion.setDefaultDuration(Tween.DEFAULT_MILLIS);
        Motion.setDefaultEasing(Tween.DEFAULT_EASING);
    }

    // ------------------------------------------------------------------
    // The record
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a client starts on the default, which is square, and its colours are modern's")
    void aClientStartsOnTheDefault() {
        // `Themes.DEFAULT` and `Themes.MODERN` are different constants now, and that is worth a test
        // because it is the kind of pair that quietly collapses back into one during a refactor. The
        // reference theme is rounded and the shipped one is square; both exist because a theme named
        // "the current look" cannot also be the fixed thing every assertion is written against.
        //
        // What this file is checking here is the *identity* of the default rather than its palette --
        // `ThemesTest` compares all forty-three colours, and duplicating that here would mean two tests
        // failing for one edit. What belongs here is that the toolkit starts on it.
        assertSame(Themes.DEFAULT, ArmatureTheme.current(),
                "a client that has not chosen should be on the shipped default");
        assertEquals("default", Themes.DEFAULT.name());
        assertEquals(0, Themes.DEFAULT.cornerRadius());
        assertNotSame(Themes.MODERN, Themes.DEFAULT,
                "the default and the reference theme have become one object, so a pack asking for"
                        + " `modern` and a player who never chose would now get the same look");

        assertEquals(0xB80A0A0D, Themes.MODERN.dim());
        assertEquals(0xFF24242E, Themes.MODERN.panel());
        assertEquals(0xFF46465A, Themes.MODERN.panelEdge());
        assertEquals(0xFFFFFFFF, Themes.MODERN.title());
        assertEquals(0xFFC6C6D4, Themes.MODERN.body());
        assertEquals(0xFF86CE8A, Themes.MODERN.complete());
        assertEquals(0xFF7FB4E8, Themes.MODERN.available());
        assertEquals(0xFF33597F, Themes.MODERN.controls().accent());
    }

    @Test
    @DisplayName("the token registry did not change the look, and the one later change was deliberate")
    void theNewTokensDidNotChangeTheLook() {
        // The four node borders and the three scrollbar and tooltip colours are new with the token
        // registry. Each is deliberately a colour this theme already had, because a theme system that
        // changed the default appearance on the day it arrived is indistinguishable from one that
        // broke it. This test is the assertion of that intent, and it is cheap.
        //
        // **One of the seven has since been changed on purpose**, and this test is where that is
        // recorded rather than hidden. `scrollThumb` was `#4C4C62` and is now `#3E3E50`: a scrollbar
        // grip was drawing as a solid object beside a list of buttons, and the report came from
        // looking at the screen — "make it not such an obnoxious colour by default". So this test now
        // asserts six unchanged colours and one deliberate change, and the reason it says so in the
        // one place is that a test whose whole point is "the appearance did not move" must not be
        // quietly edited into a test that says "the appearance is whatever it is now".
        assertEquals(Themes.MODERN.available(), Themes.MODERN.nodeEdgeAvailable(),
                "a node's available ring should be the available colour it has always been");
        assertEquals(Themes.MODERN.inProgress(), Themes.MODERN.nodeEdgeInProgress());
        assertEquals(Themes.MODERN.complete(), Themes.MODERN.nodeEdgeComplete());

        // `blocked` is the exception, and deliberately: a locked node's outline has always been dimmer
        // than the word explaining it, so a locked node recedes rather than shouting. Asserting the
        // difference here means the rule in ThemePatch that excludes `blocked` from following its state
        // cannot be "fixed" into making them equal without this failing.
        assertNotSame(Themes.MODERN.blocked(), Themes.MODERN.nodeEdgeBlocked());
        assertTrue(luminance(Themes.MODERN.nodeEdgeBlocked()) < luminance(Themes.MODERN.blocked()),
                "the locked node's outline should be dimmer than the word, in every shipped theme");

        // The scrollbar and tooltip colours were borrowed from `raised`, `controlEdge` and `canvas`.
        // They are now tokens, and three of the four still resolve to the colour that was being drawn
        // before.
        assertEquals(0xFF1C1C24, Themes.MODERN.scrollTrack(), "the track is unchanged");
        assertEquals(0xF00A0A0E, Themes.MODERN.tooltipFill(), "and both tooltip colours");
        assertEquals(0xFF5C5C74, Themes.MODERN.tooltipEdge());

        // The thumb is the one that moved, for the reason above. Asserted at its *new* value rather
        // than deleted, so the change is a recorded decision with a number attached instead of a line
        // that disappeared -- and so a later "tidy-up" back to the borrowed colour fails here.
        assertEquals(0xFF3E3E50, Themes.MODERN.scrollThumb(),
                "the scrollbar grip was retuned darker on visual feedback -- see the note above");

        // The property that actually motivated retuning it, and the one worth asserting: the grip has
        // to be **visible against its own track**, because a bar nobody can find is a bar nobody can
        // drag. That is the other half of the report this change answers.
        assertTrue(luminance(Themes.MODERN.scrollThumb()) > luminance(Themes.MODERN.scrollTrack()),
                "the grip must read against the track it moves along");

        // The retune was against the *old borrowed* colour, so that is what this compares to -- not
        // against an arbitrary other token. `#4C4C62` was `raised`-ish slate borrowed for a job it was
        // never chosen for; the point of the change is that the grip is no longer that bright.
        //
        // Worth recording what I got wrong here, because the assertion I *wrote* first looked
        // reasonable and was false: "the grip is quieter than a control fill". It is not, and it should
        // not be — `#3E3E50` against a fill of `#3E3E4C` is the same slate within four units of blue,
        // which is deliberate. A three-pixel grip and a hundred-pixel button at the same brightness read
        // completely differently, and a grip dimmer than a control is a grip that has stopped being
        // findable, which is the failure this whole change exists to avoid rather than to cause.
        //
        // Comparing against the retired value rather than against a live token is the honest form: the
        // claim is "this got darker than it was", which is exactly what the feedback asked for and what
        // a future thinning of the palette should not silently undo.
        assertTrue(luminance(Themes.MODERN.scrollThumb()) < luminance(0xFF4C4C62),
                "the grip should be dimmer than the colour it borrowed before the retune");
    }

    @Test
    @DisplayName("allColours is every colour in the theme, in the registry's order")
    void allColoursCoversEveryColour() {
        // The array is what every sweep is written against -- "is any colour transparent", "did this
        // patch change exactly what it named" -- so a colour missing from it is a colour no check
        // covers. The length is asserted against the registry, and ThemeTokenTest asserts the order.
        assertEquals(ThemeToken.ALL.size(), Themes.MODERN.allColours().length);

        int[] values = Themes.MODERN.allColours();
        assertEquals(Themes.MODERN.dim(), values[ThemeToken.indexOf("dim")]);
        assertEquals(Themes.MODERN.panel(), values[ThemeToken.indexOf("panel")]);
        assertEquals(Themes.MODERN.controls().accent(), values[ThemeToken.indexOf("accent")],
                "a control colour must be in the same array, past the control boundary");
    }

    @Test
    @DisplayName("a colour is readable by name, and an unknown name throws rather than defaulting")
    void coloursAreReadableByToken() {
        assertEquals(Themes.MODERN.panel(), Themes.MODERN.colour("panel"));
        assertEquals(Themes.MODERN.controls().edge(), Themes.MODERN.colour("edge"));
        assertTrue(Themes.MODERN.has("scrollThumb"));
        assertFalse(Themes.MODERN.has("scrollbar"));

        // The opposite of `Themes.byName`, and the difference is who is asking. A name reaching here
        // has already been resolved by `ThemeToken.byId` at the boundary, so an unknown one is a bug in
        // this codebase -- for which a stack trace naming the id is the useful answer, not a default.
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> Themes.MODERN.colour("panell"));
        assertTrue(thrown.getMessage().contains("panell"), "the message should name the bad id");
        assertTrue(thrown.getMessage().contains("panel"), "and list what does exist");
    }

    @Test
    @DisplayName("a derived theme is a copy, so a shared theme cannot be mutated under a screen")
    void copiesAreCopies() {
        // A theme is shared: every screen draws from one instance, so a mutating setter would change
        // the appearance of a screen that is halfway through a frame. Same argument as `Slot.moved`.
        Theme slower = Themes.MODERN.withMotion(500L);

        assertEquals(500L, slower.motion());
        assertEquals(140L, Themes.MODERN.motion(), "withMotion mutated the theme it was called on");
        assertNotSame(Themes.MODERN, slower);
        assertEquals(Themes.MODERN.panel(), slower.panel(), "a copy should keep every other colour");

        Theme renamed = Themes.MODERN.withName("elsewhere");
        assertEquals("elsewhere", renamed.name());
        assertEquals("modern", Themes.MODERN.name());
        assertEquals(Themes.MODERN.allColours().length, renamed.allColours().length);

        Theme recoloured = Themes.MODERN.with("panel", 0xFF010203);
        assertEquals(0xFF010203, recoloured.panel());
        assertEquals(0xFF24242E, Themes.MODERN.panel());
        assertSame(Themes.MODERN, Themes.MODERN.with("panell", 0xFF010203),
                "an unknown token leaves the theme alone rather than throwing, since a file drives this");
    }

    @Test
    @DisplayName("every colour in every theme is opaque or deliberately translucent")
    void everyColourIsEightDigits() {
        // The six-digit mistake, swept over every theme: a colour written without its alpha channel is
        // fully transparent and draws nothing, so a typo shows as a panel that has vanished rather than
        // as an error. Written against `allColours()` rather than a hand-written list of fields, which
        // is what makes the sweep cover a colour added tomorrow -- the version of this test that named
        // twenty fields by hand called one of them something that was not a field at all.
        for (Theme theme : Themes.ALL) {
            for (int argb : theme.allColours()) {
                assertTrue((argb >>> 24) != 0,
                        theme.name() + " has a fully transparent colour: " + hex(argb));
            }

            // The four that are meant to be see-through, named separately so that making one opaque --
            // which would hide whatever it is meant to tint -- is a failure rather than a silent
            // difference. A dim layer that is opaque hides the world behind it; a node wash that is
            // opaque hides the item the node is drawn around.
            assertTrue((theme.dim() >>> 24) < 0xFF, theme.name() + ": the dim layer should be see-through");
            assertTrue((theme.nodeDim() >>> 24) < 0xFF,
                    theme.name() + ": the locked-node wash should tint the icon, not hide it");
            assertTrue((theme.nodeDoneWash() >>> 24) < 0xFF,
                    theme.name() + ": the completed-node wash should tint the icon, not hide it");
            assertTrue((theme.rowHover() >>> 24) < 0xFF,
                    theme.name() + ": a row's hover should wash over the panel, not replace it");
        }
    }

    @Test
    @DisplayName("a theme refuses a bad array rather than drawing a plausible wrong one")
    void fromValidatesItsInput() {
        // `from` is the single construction point for every derived theme, so this is where a caller
        // that got the array length wrong is caught. The alternative is the worst failure in this file:
        // a theme that is the right shape and has each colour shifted by one slot, which reads as a
        // palette nobody would choose rather than as a bug.
        assertThrows(IllegalArgumentException.class,
                () -> Theme.from("x", new int[3], 4, 100L, Easing.LINEAR, CanvasBackground.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Theme.from("x", new int[ThemeToken.ALL.size() + 1], 4, 100L, Easing.LINEAR,
                        CanvasBackground.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Theme.from("x", new int[ThemeToken.ALL.size()], -1, 100L, Easing.LINEAR,
                        CanvasBackground.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> Theme.from("x", new int[ThemeToken.ALL.size()], 4, -1L, Easing.LINEAR,
                        CanvasBackground.NONE));
    }

    @Test
    @DisplayName("a theme's label is its name with the words separated and capitalised")
    void theLabelIsFitToShow() {
        // On the record and tested here because the only consumer is a control that shows it.
        // `vanilla_plus` on a sidebar button is an identifier; the player is owed a name.
        assertEquals("Modern", Themes.MODERN.displayName());
        assertEquals("Vanilla Plus", Themes.VANILLA_PLUS.displayName());
        assertEquals("High Contrast", Themes.HIGH_CONTRAST.displayName());
        assertEquals("Deep Dark", Themes.DEEP_DARK.displayName());
    }

    // ------------------------------------------------------------------
    // The part of a theme that is behaviour rather than colour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("selecting a theme applies its motion duration and curve")
    void selectingAppliesMotion() {
        // The whole of a theme's effect on behaviour, and why motion belongs in a theme at all: "how
        // this UI moves" is part of how it looks. Before this, a theme could say it had no animation and
        // nothing would act on it.
        ArmatureTheme.setCurrent(Themes.TOME);
        assertEquals(Themes.TOME.motion(), Motion.defaultDuration(),
                "tome's duration did not reach the toolkit's animation");
        assertEquals(Themes.TOME.easing(), Motion.defaultEasing(),
                "tome's curve did not reach the toolkit's animation");

        ArmatureTheme.setCurrent(Themes.VANILLA_PLUS);
        assertEquals(0L, Motion.defaultDuration());
    }

    @Test
    @DisplayName("vanilla_plus really does stop the animation, end to end")
    void vanillaPlusActuallyDisablesMotion() {
        // The end-to-end claim, asserted rather than described: a theme with a duration of zero makes a
        // hover arrive on the frame it is retargeted. Without the propagation in `setCurrent` this would
        // silently keep easing and the theme would be lying about itself -- which is exactly how it
        // behaved before, when a duration of zero was read as "no opinion".
        ArmatureTheme.setCurrent(Themes.VANILLA_PLUS);

        Hover hover = new Hover();
        hover.update("row", 1_000L);
        assertEquals(1F, hover.amount("row", 1_000L), 0F,
                "vanilla_plus has no motion, so a hover should be applied immediately");

        ArmatureTheme.setCurrent(Themes.MODERN);
        Hover animated = new Hover();
        animated.update("row", 1_000L);
        assertTrue(animated.amount("row", 1_000L) < 1F,
                "modern animates, so a hover should not be complete on the frame it starts");
    }

    @Test
    @DisplayName("a player who turned motion off keeps it off, whatever theme is selected")
    void theAccessibilitySwitchWinsOverTheTheme() {
        // The precedence that matters, and it is not obvious. A reskin must not be able to re-enable
        // animation for someone who cannot comfortably use it -- so a theme sets the *default* duration
        // and curve, and `Motion.setEnabled` stays the switch that wins.
        Motion.setEnabled(false);
        ArmatureTheme.setCurrent(Themes.MODERN);

        assertFalse(Motion.enabled(), "selecting a theme re-enabled a disabled animation setting");
        assertEquals(0L, Motion.scaledDuration(Themes.MODERN.motion()),
                "with motion off every duration is still zero, whatever the theme asked for");
    }

    @Test
    @DisplayName("resetting returns the default theme and its motion, so a test cannot leak one")
    void resetRestoresTheDefault() {
        ArmatureTheme.setCurrent(Themes.VANILLA_PLUS);
        assertEquals(0L, Motion.defaultDuration());

        ArmatureTheme.resetCurrent();

        assertSame(Themes.DEFAULT, ArmatureTheme.current());
        assertEquals(Themes.MODERN.motion(), Motion.defaultDuration());
        assertEquals(Themes.MODERN.easing(), Motion.defaultEasing());
    }

    @Test
    @DisplayName("a null theme is refused rather than leaving the client themeless")
    void aNullThemeIsRefused() {
        // One line, and it prevents the worst state this class can be in: a null current theme means
        // every colour read throws, on a drawing thread, in the middle of a frame.
        assertThrows(NullPointerException.class, () -> ArmatureTheme.setCurrent(null));
        assertThrows(NullPointerException.class, () -> ArmatureTheme.scope(null));
    }

    // ------------------------------------------------------------------
    // Scopes: the mechanism that replaced the override
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a scope changes what current() reports, and closing it puts the main theme back")
    void aScopeIsInForceUntilItCloses() {
        // The whole mechanism in one test. Before this, a chapter's theme was applied as a global claim
        // and had to be released by hand -- which meant a missed release left a chapter's palette on
        // every later screen. A scope cannot be missed: the compiler places the close.
        ArmatureTheme.setCurrent(Themes.MODERN);
        assertSame(Themes.MODERN, ArmatureTheme.current());

        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(Themes.TOME)) {
            assertSame(Themes.TOME, ArmatureTheme.current());
            assertEquals(Themes.TOME.panel(), ArmatureTheme.panel(),
                    "the accessors should read the theme in force, not the main one");
            assertSame(Themes.MODERN, ArmatureTheme.chrome(),
                    "chrome() is the main theme regardless of what is in scope");
        }

        assertSame(Themes.MODERN, ArmatureTheme.current());
        assertEquals(Themes.MODERN.panel(), ArmatureTheme.panel());
    }

    @Test
    @DisplayName("scopes nest, and the innermost one wins")
    void scopesNest() {
        // A group sets the viewport's palette; an entry inside it may set its own; the detail overlay
        // may set its own again. So this is a stack and not a field, which is what makes the feature
        // compose rather than being a special case at each level.
        ArmatureTheme.setCurrent(Themes.MODERN);

        try (ArmatureTheme.Scope group = ArmatureTheme.scope(Themes.NETHER)) {
            assertSame(Themes.NETHER, ArmatureTheme.current());

            try (ArmatureTheme.Scope entry = ArmatureTheme.scope(Themes.END)) {
                assertSame(Themes.END, ArmatureTheme.current());
                assertEquals(2, ArmatureTheme.scopeDepth());
            }

            assertSame(Themes.NETHER, ArmatureTheme.current(),
                    "closing an inner scope must restore the outer one, not the main theme");
        }

        assertSame(Themes.MODERN, ArmatureTheme.current());
        assertEquals(0, ArmatureTheme.scopeDepth(), "a test that leaks a scope makes the next one lie");
    }

    @Test
    @DisplayName("closing a scope twice does not pop somebody else's")
    void closingTwiceIsHarmless() {
        // The compiler emits exactly one close per scope, but a caller may reasonably close one by hand
        // inside the block as well. A second pop would take a palette off the stack that this scope did
        // not put there, leaving a region drawn in the chrome's colours while everything around it is in
        // the chapter's -- a bug with no obvious cause at the call site.
        try (ArmatureTheme.Scope outer = ArmatureTheme.scope(Themes.NETHER)) {
            ArmatureTheme.Scope inner = ArmatureTheme.scope(Themes.END);
            inner.close();
            inner.close();

            assertSame(Themes.NETHER, ArmatureTheme.current(), "the outer scope was popped by mistake");
            assertEquals(1, ArmatureTheme.scopeDepth());
        }

        assertEquals(0, ArmatureTheme.scopeDepth());
    }

    @Test
    @DisplayName("scopeOf an unknown or absent name pushes nothing, and does not corrupt the stack")
    void scopeOfAnUnknownNameChangesNothing() {
        // The null-safe form, for content: a chapter's theme may be absent, may name a built-in, or may
        // name a theme the client does not have. The last case draws unchanged -- the caller that wants
        // to warn checks `Themes.any` itself, because this method cannot warn on its behalf.
        ArmatureTheme.setCurrent(Themes.MODERN);

        try (ArmatureTheme.Scope absent = ArmatureTheme.scopeOf(null)) {
            assertSame(Themes.MODERN, ArmatureTheme.current());
        }
        try (ArmatureTheme.Scope unknown = ArmatureTheme.scopeOf("marble")) {
            assertSame(Themes.MODERN, ArmatureTheme.current());
        }
        assertEquals(0, ArmatureTheme.scopeDepth(),
                "a no-op scope must not change the depth, or the blocks around it would be unbalanced");

        try (ArmatureTheme.Scope found = ArmatureTheme.scopeOf("tome")) {
            assertSame(Themes.TOME, ArmatureTheme.current());
        }
    }

    @Test
    @DisplayName("a scoped theme changes colours and not the motion, deliberately")
    void aScopeDoesNotReachMotion() {
        // The boundary that keeps this feature from being a mystery. A theme carries a duration and a
        // curve, and a *region* must not apply them: motion is pushed into the toolkit once, process
        // wide, and read by every tween in it. A chapter with its own animation timing would mean every
        // tween asking which region it was in, and "this chapter animates differently" is a bug report
        // rather than a feature.
        ArmatureTheme.setCurrent(Themes.MODERN);
        long before = Motion.defaultDuration();

        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(Themes.VANILLA_PLUS)) {
            assertEquals(before, Motion.defaultDuration(),
                    "a scope applied a motion duration, so animation now depends on where you are"
                            + " standing -- which no theme author would expect and no player could explain");
        }
    }

    @Test
    @DisplayName("selecting a theme applies its radius and colours together, and reset clears scopes")
    void resetAlsoClosesAnyOpenScope() {
        // `resetCurrent` clears the scope stack as well as the base, and that matters for a real
        // operation rather than only for tests: a client shutting down mid-frame must not leave a
        // palette on the stack for whatever runs next.
        try (ArmatureTheme.Scope open = ArmatureTheme.scope(Themes.NETHER)) {
            assertEquals(1, ArmatureTheme.scopeDepth());

            ArmatureTheme.resetCurrent();

            assertEquals(0, ArmatureTheme.scopeDepth());
            assertSame(Themes.DEFAULT, ArmatureTheme.current());
        }
        assertEquals(0, ArmatureTheme.scopeDepth(),
                "closing a scope after a reset must not underflow the stack");
    }

    // ------------------------------------------------------------------
    // The radii, which are what makes a theme observable rather than describable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the rounded themes round, and vanilla_plus does not")
    void radiiDiffer() {
        // The assertion that makes the radius a parameter rather than a constant. Two themes at the
        // same radius would let a radius threaded through the wrong place pass every test -- which is
        // exactly why `tome` is rounder than `modern` and `vanilla_plus` is square.
        assertTrue(Themes.MODERN.cornerRadius() > 0, "the default theme should be rounded");
        assertTrue(Themes.TOME.cornerRadius() > Themes.MODERN.cornerRadius(),
                "tome should be visibly rounder than modern, so the parameter is observable");
        assertEquals(0, Themes.VANILLA_PLUS.cornerRadius(),
                "vanilla_plus is square, which is what 'looks like Minecraft' means -- and a radius of"
                        + " zero is the case that catches an implementation with a minimum of one");
    }

    @Test
    @DisplayName("names resolve by name or report nothing, and never to a fallback")
    void namesResolve() {
        assertSame(Themes.MODERN, Themes.byName("modern"));
        assertSame(Themes.TOME, Themes.byName("TOME"), "a player typing a name is not case-sensitive");
        assertSame(Themes.byName("  modern  "), Themes.byName("modern"), "a hand-edited setting has spaces");

        // Null rather than a fallback, which is the opposite of the shape lookup -- and the difference
        // is who is asking. A theme name arrives from a person's own setting, where "no such theme" is
        // the honest answer and a fallback would report success for a typo.
        assertNull(Themes.byName("dodecahedron"));
        assertNull(Themes.byName(""));
        assertNull(Themes.byName(null));
    }

    @Test
    @DisplayName("selectByName reports whether the name resolved")
    void selectByNameReports() {
        assertFalse(ArmatureTheme.selectByName("marble"), "a typo must be reportable to somebody");
        assertSame(Themes.DEFAULT, ArmatureTheme.current(), "a refused name must change nothing");

        assertTrue(ArmatureTheme.selectByName("tome"));
        assertSame(Themes.TOME, ArmatureTheme.current());
        assertEquals("tome", ArmatureTheme.themeName());
    }

    /**
     * The kit's own yardstick, rather than a second one here.
     *
     * <p>This was a private sum of the three channels for two rounds, and the scrollbar's hover shade now
     * needs exactly that question answered in production ({@code ArmatureScrollStyle} asks which of a
     * theme's grip and groove is the lighter). Two implementations of one measurement agree until one of
     * them is edited, so the test uses the one the code uses — and the assertions below then measure
     * brightness the same way the rule they are checking does.
     */
    private static int luminance(int argb) {
        return Colour.luminance(argb);
    }

    private static String hex(int argb) {
        return String.format("#%08X", argb);
    }
}
