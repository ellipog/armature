package dev.ellipog.armature.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a control looks like in each state, for the variant that was added on feedback.
 *
 * <h2>Why this file exists now rather than earlier</h2>
 *
 * <p>{@link ArmatureControlStyle} is the one place a control's appearance is decided, and it had no
 * test of its own. The gap did not matter while every variant was one of four shapes that had been
 * looked at; it mattered the moment a fifth was added to fix a report from a screenshot, because the
 * thing being fixed — <i>"no like thing to make the categories look like buttons"</i> — is exactly a
 * claim about colour and therefore exactly what a test can hold.
 *
 * <p>Nothing here needs a client. {@code ArmatureTheme} resolves the current theme for
 * {@code fill}/{@code edge}/{@code text} without touching a Minecraft type on these paths, which is
 * what makes the whole appearance assertable — and is itself worth noting, because it was not obvious
 * before trying.
 */
@DisplayName("control style")
class ArmatureControlStyleTest {

    private static final ArmatureControlStyle.Variant SECTION = ArmatureControlStyle.Variant.SECTION;

    /**
     * Puts the current theme back to the shipped default before every test.
     *
     * <h2>Why this is not optional, and how the first version failed without it</h2>
     *
     * <p>{@link ArmatureControlStyle} resolves every colour through {@link ArmatureTheme#current()},
     * which is <b>mutable global state</b> — {@code Appearance.setTheme} writes it, and
     * {@code AppearanceTest} calls that with {@code "paper"} and {@code "tome"} among others. Tests share
     * a JVM, so without this the theme these assertions ran against depended on which class JUnit
     * happened to reach first.
     *
     * <p>It did, and the failure was instructive rather than merely annoying. Two assertions failed —
     * "a heading's rule should be brighter on hover" and "a heading must not be quieter than body
     * text" — and both are <b>true of the default theme and false of a light one</b>, because on a light
     * theme the brightest text is dark and the row's own body text is lighter than its heading. So the
     * tests were reporting a correct fact about the wrong theme, and the message named the relationship
     * rather than the reason, which is the worst kind of red herring.
     *
     * <p>{@code ThemeTest} had this reset from the start and this file did not, which is the whole
     * difference between the two. A test that reads global state has to pin it; there is no version of
     * this that works by luck.
     */
    @BeforeEach
    void resetTheme() {
        ArmatureTheme.resetCurrent();
    }

    private static int luminance(int argb) {
        return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
    }

    @Nested
    @DisplayName("a section heading")
    class Section {

        @Test
        @DisplayName("has no fill, so it is drawn as a rule rather than a box")
        void noFill() {
            // Zero rather than a colour, and that is a decision rather than a placeholder: a heading
            // with a fill would look like the rows it groups, which is the confusion the selected fill
            // exists to prevent. The rule underneath is what says "this is a control" instead.
            assertEquals(0, ArmatureControlStyle.fill(SECTION, true, false, false));
            assertFalse(ArmatureControlStyle.drawsBox(SECTION),
                    "a section draws a rule, not a box -- so the button must not route it through panel");
        }

        @Test
        @DisplayName("the fill is the same hovered as at rest, so no lerp is spent on it")
        void theFillNeverAnimates() {
            // Which is what makes `fillAt` take its early branch for a heading: the resting and hovered
            // fills are equal, so there is nothing to blend. Asserted because the branch is a real
            // saving on a list where every row is a heading or a chapter, and because a heading that
            // *did* fill on hover would be a heading that looked selected.
            assertEquals(ArmatureControlStyle.fill(SECTION, true, false, false),
                    ArmatureControlStyle.fill(SECTION, true, false, true));
            assertEquals(0, ArmatureControlStyle.fillAt(SECTION, true, false, 0.5F));
        }

        @Test
        @DisplayName("its rule brightens on hover, which is the one edge in the class that does")
        void theRuleIsTheOnlyEdgeThatMoves() {
            // Everywhere else the border says what a control *is* and hover must not touch it -- see
            // the class comment. A section is the exception and needs to be, because it has no fill:
            // with a constant rule a heading would give no feedback at all when the pointer arrived,
            // which reads as the row being dead.
            int rest = ArmatureControlStyle.edge(SECTION, true, false, false);
            int hovered = ArmatureControlStyle.edge(SECTION, true, false, true);

            assertNotEquals(rest, hovered, "a heading's rule should respond to the pointer");
            assertTrue(luminance(hovered) > luminance(rest), "and brighter rather than darker");

            // And the contrast that makes it readable against the surface it is drawn on: the resting
            // rule is `panelEdge`, which is already a visible line rather than something that has to be
            // hunted for.
            assertEquals(ArmatureTheme.panelEdge(), rest);
        }

        @Test
        @DisplayName("is labelled in the brightest text, so it outranks what it groups")
        void theLabelOutranksItsChapters() {
            // The whole job of a heading. Asserted against the chapter's own colour rather than against
            // a constant, so a theme that inverted its palette would fail here rather than shipping a
            // heading that recedes behind its contents.
            assertEquals(ArmatureTheme.title(), ArmatureControlStyle.text(SECTION, true));
            assertTrue(luminance(ArmatureControlStyle.text(SECTION, true))
                            >= luminance(ArmatureTheme.body()),
                    "a heading's label must not be quieter than ordinary body text");
        }

        @Test
        @DisplayName("a disabled heading keeps its look rather than greying out")
        void disabledHeadingsDoNotDim() {
            // Checked before the `enabled` branch, deliberately, and the reason is not that a heading
            // is never disabled -- it is that greying one out would make its chapters look like they
            // belonged to nothing.
            assertEquals(ArmatureControlStyle.fill(SECTION, false, false, false), 0,
                    "still no fill");
            assertEquals(ArmatureTheme.title(), ArmatureControlStyle.text(SECTION, false),
                    "and still the brightest label");
            assertNotEquals(ArmatureTheme.blocked(), ArmatureControlStyle.text(SECTION, false));
        }
    }

    @Nested
    @DisplayName("the four variants that were already there")
    class Unchanged {

        @Test
        @DisplayName("are unaffected by the section variant's existence")
        void theNewVariantDidNotChangeTheOldOnes() {
            // The regression this file is really for. Adding a variant means editing `fill`, `edge`,
            // `text` and `drawsBox`, and each is a chain of early returns where one misplaced branch
            // would change every ordinary control on every screen. Cheap to assert, and it fails
            // loudly rather than as a colour somebody notices in a screenshot three rounds later.
            assertTrue(ArmatureControlStyle.drawsBox(ArmatureControlStyle.Variant.PLAIN));
            assertTrue(ArmatureControlStyle.drawsBox(ArmatureControlStyle.Variant.SELECTED));
            assertTrue(ArmatureControlStyle.drawsBox(ArmatureControlStyle.Variant.ACCENT));
            assertFalse(ArmatureControlStyle.drawsBox(ArmatureControlStyle.Variant.FLAT),
                    "flat still draws nothing at all -- that is what it is for");

            // A plain control still resolves to the theme's own fill…
            assertEquals(ArmatureTheme.controls().fill(),
                    ArmatureControlStyle.fill(ArmatureControlStyle.Variant.PLAIN, true, false, false));
            // …and a flat one still draws nothing, rather than picking up the section's zero by
            // accident. They share the number and not the meaning: flat has no rule.
            assertNotEquals(ArmatureControlStyle.edge(ArmatureControlStyle.Variant.FLAT, true, false, true),
                    ArmatureControlStyle.edge(SECTION, true, false, true),
                    "flat has no rule, so its `edge` is the ordinary control edge");
        }
    }
}
