package dev.ellipog.armature.client.ui;

import dev.ellipog.armature.client.ui.kit.Easing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partial overrides: the one mechanism behind a theme file, a group, an entry and the editor.
 *
 * <h2>The merge order is the whole design, so it is asserted as order</h2>
 *
 * <p>Four separate features want to change part of an appearance, and all four are "these tokens become
 * these values". Rather than four implementations, there is one — {@link ThemePatch} — and the only
 * thing that decides the outcome when two of them apply is <b>which was merged into which</b>. So the
 * tests about {@link ThemePatch#merge} are testing the feature, not a helper: a child winning is what
 * makes an entry's colours beat its group's, which is what makes a group's beat the player's theme.
 *
 * <h2>The two-ways-to-apply boundary is a decision, and it is tested as one</h2>
 *
 * <p>{@code applyTo} takes everything; {@code tint} takes colours only. The difference is not an
 * oversight to be tidied away — a group that could change the corner radius of every panel in the
 * book, or the duration of every transition in it, would be a data file reaching into the screen's
 * construction. And the motion half of that is worse than intrusive: a scoped motion would do nothing,
 * because animation timing is pushed to {@code Motion} once per client. A value that cannot take effect
 * is worse than one that is refused, and this codebase has already shipped a field that was parsed,
 * validated, printed and drawn by nothing.
 */
@DisplayName("Theme patches")
class ThemePatchTest {

    private static final int VIOLET = 0xFFC79BF0;
    private static final int DARK_PANEL = 0xFF26212E;

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty patch changes nothing, and a patch of one colour changes one colour")
    void applyingChangesOnlyWhatItNames() {
        assertSame(Themes.MODERN, ThemePatch.NONE.applyTo(Themes.MODERN));
        assertTrue(ThemePatch.NONE.isEmpty());
        assertTrue(ThemePatch.colours(Map.of()).isEmpty());

        // The property that makes the editor work: a person changing one colour expects the other
        // forty to stay put. Asserted over the whole array rather than on the field, so a patch that
        // shifted a neighbour would fail here.
        Theme one = ThemePatch.one("panel", DARK_PANEL).tint(Themes.MODERN);
        assertEquals(DARK_PANEL, one.panel());
        assertOnlyDiffersAt(Themes.MODERN, one, "panel");

        // And the name, radius, motion and easing are untouched by a colour patch -- which is what
        // lets a saved theme say "everything from modern, except this".
        assertEquals(Themes.MODERN.name(), one.name());
        assertEquals(Themes.MODERN.cornerRadius(), one.cornerRadius());
        assertEquals(Themes.MODERN.motion(), one.motion());
        assertEquals(Themes.MODERN.easing(), one.easing());
    }

    @Test
    @DisplayName("applyTo takes name, radius, motion, curve and background; tint takes none of them")
    void applyToAndTintDifferDeliberately() {
        // The boundary. `applyTo` is how a whole theme is made -- a file, or one of the derived
        // built-ins -- so it takes the things a theme is. `tint` is how a *region* is reskinned,
        // and it must not reach the screen's construction.
        ThemePatch patch = new ThemePatch("elsewhere", Map.of("panel", DARK_PANEL), 0, 0L, Easing.LINEAR,
                new CanvasBackground(CanvasBackground.Kind.DOTS, CanvasBackground.Space.SCREEN, 32));

        Theme asTheme = patch.applyTo(Themes.MODERN);
        assertEquals("elsewhere", asTheme.name());
        assertEquals(0, asTheme.cornerRadius());
        assertEquals(0L, asTheme.motion());
        assertEquals(Easing.LINEAR, asTheme.easing());
        assertEquals(CanvasBackground.Kind.DOTS, asTheme.background().kind());
        assertEquals(DARK_PANEL, asTheme.panel());

        Theme asRegion = patch.tint(Themes.MODERN);
        assertEquals(DARK_PANEL, asRegion.panel());
        assertEquals(Themes.MODERN.cornerRadius(), asRegion.cornerRadius(),
                "a chapter must not be able to change every panel's corners");
        assertEquals(Themes.MODERN.motion(), asRegion.motion(),
                "a scoped motion would do nothing anyway, because timing is pushed to Motion once");
        assertEquals(Themes.MODERN.easing(), asRegion.easing());
        assertEquals(Themes.MODERN.background(), asRegion.background(),
                "a chapter paints on the canvas; it does not resurface it");
        assertEquals(Themes.MODERN.name(), asRegion.name(),
                "a tint does not rename the theme it was derived from");
    }

    @Test
    @DisplayName("a null field means no opinion; a zero is a value")
    void nullAndZeroAreDifferent() {
        // The distinction that a theme's own docs make a fuss about, asserted here because this is
        // where it can be got wrong. `new ThemePatch(name, colours, null, null, null, null)` keeps the
        // base's radius, duration and surface; a patch with `0` and `0L` sets them.
        ThemePatch keep = new ThemePatch(null, Map.of("panel", DARK_PANEL), null, null, null, null);
        Theme kept = keep.applyTo(Themes.MODERN);
        assertEquals(Themes.MODERN.cornerRadius(), kept.cornerRadius());
        assertEquals(Themes.MODERN.motion(), kept.motion());

        ThemePatch zero = new ThemePatch(null, Map.of(), 0, 0L, null, null);
        Theme squared = zero.applyTo(Themes.MODERN);
        assertEquals(0, squared.cornerRadius(),
                "a radius of zero means square, and an implementation clamping to a minimum of one"
                        + " cannot express it");
        assertEquals(0L, squared.motion(),
                "a duration of zero means instant. Reading it as 'unset' is what made vanilla_plus"
                        + " silently animate like modern");
    }

    // ------------------------------------------------------------------
    // Merging: the order is the feature
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a child wins, and it wins only on what it names")
    void theChildWins() {
        // The rule that makes three levels compose: main, then group, then entry. Each level knows
        // nothing about the others; the order is the whole of the relationship.
        ThemePatch base = ThemePatch.colours(Map.of("panel", 0xFF111111, "canvas", 0xFF222222));
        ThemePatch child = ThemePatch.colours(Map.of("panel", 0xFF333333));

        ThemePatch merged = base.merge(child);
        assertEquals(0xFF333333, merged.colours().get("panel"), "the child's value should win");
        assertEquals(0xFF222222, merged.colours().get("canvas"),
                "the parent's value should survive where the child had no opinion");
    }

    @Test
    @DisplayName("merging an empty patch changes nothing, in either direction")
    void mergingWithNothingIsSafe() {
        // Reachable from a group with no overrides and from an entry with none, which is most of them.
        ThemePatch base = ThemePatch.colours(Map.of("panel", DARK_PANEL));

        assertSame(base, base.merge(ThemePatch.NONE));
        assertSame(base, base.merge(null));
        assertSame(base, ThemePatch.NONE.merge(base));
        assertTrue(ThemePatch.NONE.merge(ThemePatch.NONE).isEmpty());
    }

    @Test
    @DisplayName("merging keeps every non-colour value the child set, and the parent's otherwise")
    void mergeCarriesTheOtherFour() {
        CanvasBackground covered = new CanvasBackground(CanvasBackground.Kind.DOTS,
                CanvasBackground.Space.GRAPH, 24);
        CanvasBackground childCover = new CanvasBackground(CanvasBackground.Kind.HATCH,
                CanvasBackground.Space.SCREEN, 40);
        ThemePatch base = new ThemePatch("base", Map.of("panel", DARK_PANEL), 4, 140L, Easing.QUAD_OUT,
                covered);
        ThemePatch child = new ThemePatch(null, Map.of("canvas", 0xFF222222), null, 0L, null, childCover);

        ThemePatch merged = base.merge(child);
        assertEquals("base", merged.name(), "a child with no name does not erase the parent's");
        assertEquals(4, merged.cornerRadius());
        assertEquals(0L, merged.motion(), "the child set a duration of zero, which is a value");
        assertEquals(Easing.QUAD_OUT, merged.easing());
        assertEquals(childCover, merged.background(), "and the child's surface wins where it has one");

        ThemePatch named = base.merge(new ThemePatch("child", Map.of(), null, null, Easing.LINEAR, null));
        assertEquals("child", named.name());
        assertEquals(Easing.LINEAR, named.easing());
        assertEquals(covered, named.background(), "while a child with no opinion keeps the parent's");
    }

    @Test
    @DisplayName("the three levels compose in one direction, and the last one applied wins")
    void threeLevelsCompose() {
        // The shape the plan calls main -> group -> entry, walked as the screen walks it. Six colours
        // across three levels, and the expected result is that each name ends up with the value from
        // the *last* level that mentioned it -- which is what makes an entry able to override its
        // group without restating it.
        ThemePatch group = ThemePatch.colours(Map.of("panel", 0xFF111111, "canvas", 0xFF111111));
        ThemePatch entry = ThemePatch.colours(Map.of("canvas", 0xFF222222, "available", VIOLET));

        ThemePatch combined = group.merge(entry);
        Theme applied = combined.tint(Themes.MODERN);

        assertEquals(0xFF111111, applied.panel(), "only the group named the panel");
        assertEquals(0xFF222222, applied.canvas(), "both named the canvas, so the entry wins");
        assertEquals(VIOLET, applied.available(), "only the entry named the state colour");
        assertEquals(Themes.MODERN.title(), applied.title(), "nobody named the title");
    }

    // ------------------------------------------------------------------
    // The border-follows-state rule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a state colour carries its node border along, unless the patch says otherwise")
    void aStateColourCarriesItsBorder() {
        // The trap this exists to close: an author who writes only `"available": "#C79BF0"` would
        // otherwise get a **violet word beside a blue ring**, because the base theme's ring is its own
        // token. That looks like the theme half-applied, and documenting "set both" would be a rule
        // nobody reads -- the theme loads, the picker lists it, and one ring is simply wrong.
        Theme only2State = ThemePatch.colours(Map.of("available", VIOLET)).tint(Themes.MODERN);
        assertEquals(VIOLET, only2State.available());
        assertEquals(VIOLET, only2State.nodeEdgeAvailable(),
                "the available ring should have followed the available colour");

        Theme alsoComplete = ThemePatch.colours(Map.of("complete", 0xFF112233)).tint(Themes.MODERN);
        assertEquals(0xFF112233, alsoComplete.nodeEdgeComplete());

        Theme alsoProgress = ThemePatch.colours(Map.of("inProgress", 0xFF445566)).tint(Themes.MODERN);
        assertEquals(0xFF445566, alsoProgress.nodeEdgeInProgress());
    }

    @Test
    @DisplayName("an explicit border wins over the one that follows")
    void anExplicitBorderIsNotOverwritten() {
        // The rule only ever fills in an omission. A theme that wants a ring that differs from its
        // state colour -- a desaturated ring under a bright label, or a border that reads against a
        // recoloured canvas -- is exactly why these are separate tokens, so it must not be overruled.
        Theme differing = ThemePatch.colours(Map.of(
                        "available", VIOLET,
                        "nodeEdgeAvailable", 0xFF404040))
                .tint(Themes.MODERN);

        assertEquals(VIOLET, differing.available());
        assertEquals(0xFF404040, differing.nodeEdgeAvailable(),
                "an author who set the ring explicitly had it replaced by the state colour");
    }

    @Test
    @DisplayName("blocked does not carry its border, because a locked node should recede")
    void blockedDoesNotFollow() {
        // The fourth state is excluded, and the reason is a design decision rather than an oversight.
        // `blocked` is the colour of the word; `nodeEdgeBlocked` is the outline of a node you cannot
        // click. In every shipped theme the outline is considerably dimmer, because a locked node should
        // fade back and the word explaining why should stay legible. Making them follow would undo that
        // in every theme, which is the opposite of what a default should do.
        Theme onlyBlocked = ThemePatch.colours(Map.of("blocked", 0xFF999999)).tint(Themes.MODERN);

        assertEquals(0xFF999999, onlyBlocked.blocked());
        assertEquals(Themes.MODERN.nodeEdgeBlocked(), onlyBlocked.nodeEdgeBlocked(),
                "the blocked outline followed its state, which would make a locked node as loud as its"
                        + " own explanation");
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a patch survives a round trip through its own format")
    void theFormatRoundTrips() {
        ThemePatch written = new ThemePatch("mine", Map.of("panel", DARK_PANEL, "available", VIOLET),
                6, 120L, Easing.QUAD_OUT,
                new CanvasBackground(CanvasBackground.Kind.SPECKLE, CanvasBackground.Space.GRAPH, 18));
        List<String> problems = new ArrayList<>();

        ThemePatch read = ThemePatch.fromJson(written.toJson(), problems);
        assertTrue(problems.isEmpty(), "a patch written by this code should read back clean: " + problems);

        assertEquals(written.name(), read.name());
        assertEquals(written.colours(), read.colours());
        assertEquals(written.cornerRadius(), read.cornerRadius());
        assertEquals(written.motion(), read.motion());
        assertEquals(written.easing(), read.easing());
        assertEquals(written.background(), read.background(),
                "the canvas background is part of the file, not a field Save forgets");
    }

    @Test
    @DisplayName("six hex digits mean opaque, because that is what a person types")
    void sixDigitsMeanOpaque() {
        // The one asymmetry with the rest of the codebase, and it is deliberate. In source a colour
        // without alpha is the mistake the Colour class warns about, because the value is computed
        // rather than read -- 0x00RRGGBB is transparent black and draws nothing. A hex string in a file
        // is read by a human and checked by the reader, so there is no silent failure to prevent, and
        // requiring eight digits would mean every theme repeating `ff` forty times.
        JsonObject json = JsonParser.parseString(
                "{\"colours\": {\"panel\": \"#26212E\", \"canvas\": \"#80112233\"}}").getAsJsonObject();
        List<String> problems = new ArrayList<>();

        ThemePatch read = ThemePatch.fromJson(json, problems);
        assertTrue(problems.isEmpty(), "problems: " + problems);
        assertEquals(0xFF26212E, read.colours().get("panel"), "six digits should be read as opaque");
        assertEquals(0x80112233, read.colours().get("canvas"), "eight digits carry their own alpha");
    }

    @Test
    @DisplayName("the hash is optional, and a bare number is read as ARGB")
    void theHashIsOptional() {
        // Both for the same reason: this is a number a person typed into a file, and refusing
        // `1e1e2e` for want of a hash would be pedantry with a support cost. A bare number is what this
        // codebase writes in source, so a value copied across should not have to be reformatted.
        List<String> problems = new ArrayList<>();
        ThemePatch read = ThemePatch.fromJson(JsonParser.parseString(
                "{\"colours\": {\"panel\": \"1e1e2e\", \"canvas\": 4280562734}}").getAsJsonObject(),
                problems);

        assertTrue(problems.isEmpty(), "problems: " + problems);
        assertEquals(0xFF1E1E2E, read.colours().get("panel"));
        assertEquals(4280562734L, Integer.toUnsignedLong(read.colours().get("canvas")));
    }

    @Test
    @DisplayName("an unreadable colour is reported and skipped, and the rest still applies")
    void badValuesAreReportedNotFatal() {
        // The tolerance, and it is chosen rather than convenient: a misspelled colour *value* should
        // not cost the author the other thirty-nine, but it must not be dropped silently either --
        // because the failure mode of a partially-read theme is a palette that is mostly right, which
        // looks like a decision rather than a mistake.
        List<String> problems = new ArrayList<>();
        ThemePatch read = ThemePatch.fromJson(JsonParser.parseString("{\"colours\": {"
                + "\"panel\": \"#26212E\","
                + "\"canvas\": \"#GGGGGG\","
                + "\"nonExistentToken\": \"#112233\","
                + "\"recessed\": \"#12345\"}}").getAsJsonObject(), problems);

        assertEquals(DARK_PANEL, read.colours().get("panel"), "the good value should still apply");
        assertEquals(1, read.colours().size(),
                "only 'panel' names a token with an alpha channel and a readable value, so only it"
                        + " should have been kept: " + read.colours());
        assertEquals(3, problems.size(), "each bad entry should be reported: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("canvas")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("nonExistentToken")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("recessed")), problems.toString());
    }

    @Test
    @DisplayName("a negative radius or duration is refused with a message, not applied")
    void negativesAreRefused() {
        // Both are impossible rather than merely unusual, and both would be silent: a negative radius
        // would reach the rounded-rectangle maths, and a negative duration would reach a tween.
        List<String> problems = new ArrayList<>();
        ThemePatch read = ThemePatch.fromJson(JsonParser.parseString(
                "{\"cornerRadius\": -4, \"motion\": -100}").getAsJsonObject(), problems);

        assertNull(read.cornerRadius());
        assertNull(read.motion());
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.toString().contains("Use 0 for square"), problems.toString());
        assertTrue(problems.toString().contains("Use 0 for instant"), problems.toString());
    }

    @Test
    @DisplayName("a bad easing name lists the ones that exist rather than guessing one")
    void aBadEasingIsReported() {
        List<String> problems = new ArrayList<>();
        ThemePatch read = ThemePatch.fromJson(JsonParser.parseString(
                "{\"easing\": \"QUAD_SIDEWAYS\"}").getAsJsonObject(), problems);

        assertNull(read.easing());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("QUAD_OUT"),
                "the message should list the real curves: " + problems.get(0));
    }

    @Test
    @DisplayName("basedOn is read from the file, and is not part of the patch")
    void basedOnIsTheReadersBusiness() {
        // By the time a patch is being applied the base has already been chosen, so `basedOn` does not
        // belong on the record -- but one file format has to carry both a file that names a base and a
        // chapter that cannot. So it is read and ignored, and both are handled by the same code.
        JsonObject json = JsonParser.parseString(
                "{\"name\": \"mine\", \"basedOn\": \"modern\", \"colours\": {\"panel\": \"#26212E\"}}")
                .getAsJsonObject();

        assertEquals("modern", ThemePatch.basedOn(json));
        List<String> problems = new ArrayList<>();
        ThemePatch read = ThemePatch.fromJson(json, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(DARK_PANEL, read.colours().get("panel"));
        assertEquals("mine", read.name());
    }

    // ------------------------------------------------------------------
    // The shape of the format
    // ------------------------------------------------------------------

    @Test
    @DisplayName("colours are written in sorted order, so two saves of one theme are identical")
    void coloursAreWrittenSorted() {
        // A file the editor writes should have stable diffs, and a person comparing two saved themes
        // should see the difference rather than the insertion order. A LinkedHashMap is used to build
        // this one precisely because its order is insertion order -- so a `toJson` that walked the map
        // as given would produce `title, available, panel` and fail here.
        java.util.Map<String, Integer> insertionOrdered = new java.util.LinkedHashMap<>();
        insertionOrdered.put("title", VIOLET);
        insertionOrdered.put("available", VIOLET);
        insertionOrdered.put("panel", DARK_PANEL);

        String written = new ThemePatch("x", insertionOrdered, null, null, null, null)
                .toJson().getAsJsonObject("colours").keySet().toString();

        assertEquals("[available, panel, title]", written, "keys should be sorted: " + written);
    }

    @Test
    @DisplayName("a missing colours object is not a problem, and not an exception")
    void anAbsentColoursObjectIsFine() {
        // Reachable from a file that sets only a radius, and from a chapter that overrides nothing.
        List<String> problems = new ArrayList<>();
        ThemePatch read = ThemePatch.fromJson(JsonParser.parseString("{\"cornerRadius\": 9}")
                .getAsJsonObject(), problems);

        assertTrue(problems.isEmpty(), problems.toString());
        assertTrue(read.colours().isEmpty());
        assertEquals(9, read.cornerRadius());
        assertFalse(read.isEmpty(), "a patch that sets a radius is not empty");
    }

    @Test
    @DisplayName("colours that is not an object is reported rather than throwing")
    void aMalformedColoursObjectIsReported() {
        List<String> problems = new ArrayList<>();
        ThemePatch read = ThemePatch.fromJson(JsonParser.parseString("{\"colours\": [1, 2, 3]}")
                .getAsJsonObject(), problems);

        assertTrue(read.colours().isEmpty());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("'colours' should be an object"), problems.get(0));
    }

    @Test
    @DisplayName("Controls can be rebuilt from an array, and read back into one")
    void controlsRoundTrip() {
        // The nested record's own array, which is the other half of the token registry's ordering. Its
        // order is written down in exactly two places -- `allColours` and `from` -- and this asserts the
        // two agree, which is what `Theme.from` relies on when it splits the array.
        Theme.Controls controls = Themes.MODERN.controls();
        assertArrayEqualsQuietly(controls.allColours(), Theme.Controls.from(controls.allColours()).allColours());

        Theme.Controls oneChanged = controls.with("accent", VIOLET);
        assertEquals(VIOLET, oneChanged.accent());
        assertEquals(controls.fill(), oneChanged.fill(), "changing one control colour changed another");

        assertSame(controls, controls.with("panel", VIOLET),
                "a top-level token is not a control colour, so `with` should decline it");

        assertEquals(10, controls.allColours().length);
    }

    @Test
    @DisplayName("Controls.shaded moves every one of them, and none transposes")
    void shadingMovesAllOfThem() {
        // Used to derive a variant. `Colour.shade` clamps, so a large amount could in principle make
        // two colours equal -- what must not happen is one of them ending up as another's value, which
        // is the failure a hand-written positional list produces.
        Theme.Controls shaded = Themes.MODERN.controls().shaded(0.2F);
        assertEquals(10, shaded.allColours().length);
        // A shade of zero is identity. That is the assertion that catches a wrong argument to
        // `Colour.shade` -- and a hand-written positional list would show up not as one wrong colour but
        // as ten shifted by one, which this would catch as every entry failing at once.
        Theme.Controls same = Themes.MODERN.controls().shaded(0F);
        assertArrayEqualsQuietly(Themes.MODERN.controls().allColours(), same.allColours());
    }

    @Test
    @DisplayName("a Controls array of the wrong length is refused rather than transposed")
    void controlsValidateTheirArray() {
        assertThrows(IllegalArgumentException.class, () -> Theme.Controls.from(new int[9]));
        assertThrows(IllegalArgumentException.class, () -> Theme.Controls.from(new int[11]));
        assertNotNull(Theme.Controls.from(new int[10]));
    }

    private static void assertArrayEqualsQuietly(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }

    private static void assertOnlyDiffersAt(Theme base, Theme changed, String token) {
        int[] before = base.allColours();
        int[] after = changed.allColours();
        assertEquals(before.length, after.length);
        for (int i = 0; i < before.length; i++) {
            String id = ThemeToken.ALL.get(i).id();
            if (id.equals(token)) {
                assertTrue(before[i] != after[i], "the named colour should have changed");
            }
            else {
                assertEquals(before[i], after[i],
                        "'" + id + "' changed as a side effect of changing '" + token + "'");
            }
        }
    }
}
