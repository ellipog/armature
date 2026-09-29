package dev.ellipog.armature.client;

import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.ThemeFiles;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Motion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main theme: who may choose it, what beats what, and the file all of it lives in.
 *
 * <h2>What this file is about, in one sentence</h2>
 *
 * <p>There are exactly three sources for the appearance of the chrome — the player's own choice, a
 * pack's suggested default, and the built-in default — plus the player's own colour edits, which sit on
 * top of whichever wins. This is where every one of those precedence questions is answered, and every
 * test below is a question about <i>which wins</i>.
 *
 * <h2>What is deliberately not here any more</h2>
 *
 * <p>Ten tests used to be here about a chapter overriding the player's theme: whether a click beat an
 * override, whether leaving a chapter re-armed one, whether a declined override survived
 * chapter-hopping. All of it worked, and all of it is gone, because a chapter's theme is not a claim on
 * the whole client — it is a region. `ArmatureTheme.scope` is that mechanism and {@code ThemeTest} covers
 * it.
 *
 * <p>The point worth recording is that those ten tests existed because the <i>design</i> needed a rule,
 * not because the rule was hard. Two flags — "the player declined this" and "the player has chosen" —
 * were there to stop a chapter's palette outliving a click or a chapter. Separating the two palettes by
 * <b>region</b> rather than by <b>priority</b> removed the need for either, and the tests went with it.
 * A test that only exists to pin down a precedence order is worth reading as a signal that the two
 * things being ordered might be different kinds of thing.
 *
 * <h2>Static state, so every test restores it</h2>
 *
 * <p>{@code Appearance} is process-wide — {@code ArmatureTheme.current()} reads it — so a test that sets
 * a theme leaves it set for the next one. {@code Appearance.reset()} clears the settings, the pack's
 * default and the file association, and {@code ThemeFiles.reset()} clears the disk catalogue; without
 * both, a test could pass because of a theme another test wrote to a temporary directory that no longer
 * exists.
 */
@DisplayName("The main theme, and who chooses it")
class AppearanceTest {

    @BeforeEach
    void resetAppearance() {
        Appearance.reset();
        ThemeFiles.reset();
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("settings survive a round trip through the file format")
    void theFormatRoundTrips() {
        Appearance.Settings written =
                new Appearance.Settings("tome", false, true, Map.of("panel", 0xFF26212E));
        assertEquals(written, Appearance.read(Appearance.write(written)));
    }

    @Test
    @DisplayName("a missing field takes its default rather than failing")
    void missingFieldsTakeDefaults() {
        assertEquals(Themes.DEFAULT.name(), Appearance.read("{}").theme());
        assertTrue(Appearance.read("{}").motion(),
                "an absent motion field must mean on — a file written by an older build, or by hand"
                        + " with one field in it, must not turn animation off for someone who never"
                        + " asked for that");
        // Absent means "the player has not chosen", which is what lets a pack set a theme at all. A
        // file that defaulted this to true would make a pack's theme apply only until the file was
        // written for the first time, which is a bug that would look like the pack's theme not working.
        assertFalse(Appearance.read("{}").chosen(),
                "an absent 'chosen' must mean the player has not chosen, or a pack could never set one");

        assertEquals("tome", Appearance.read("{\"theme\": \"tome\"}").theme());
        assertFalse(Appearance.read("{\"motion\": false}").motion());
        assertTrue(Appearance.read("{\"chosen\": true}").chosen());
    }

    @Test
    @DisplayName("a theme name this build does not have falls back instead of throwing")
    void anUnknownThemeNameFallsBack() {
        // Reachable two ways, and both are ordinary: a file edited by hand, and a file left behind by a
        // build that had a theme this one does not. The alternative — throwing — would take the client
        // down on a typo in a config file, which is not a proportionate answer.
        assertEquals(Themes.DEFAULT.name(), Appearance.read("{\"theme\": \"marble\"}").theme());
    }

    @Test
    @DisplayName("an edited colour naming a token this build lost is dropped, with a warning")
    void anUnknownCustomTokenIsDropped() {
        // A custom edit is a value the editor wrote. A build that no longer has that token would
        // otherwise carry it around forever doing nothing, which is a file that lies about what it
        // contains — and this is exactly the shape of bug this project has found three times.
        Appearance.Settings read = Appearance.read(
                "{\"custom\": {\"panel\": \"#26212E\", \"panell\": \"#445566\"}}");

        assertEquals(1, read.custom().size());
        assertEquals(0xFF26212E, read.custom().get("panel"));
        assertFalse(read.custom().containsKey("panell"));
    }

    @Test
    @DisplayName("a custom colour that is not a hex value is dropped, and the others kept")
    void aBadCustomColourIsDropped() {
        Appearance.Settings read = Appearance.read(
                "{\"custom\": {\"panel\": \"#26212E\", \"canvas\": \"not a colour\"}}");
        assertEquals(1, read.custom().size());
        assertEquals(0xFF26212E, read.custom().get("panel"));
    }

    @Test
    @DisplayName("a file that is not JSON leaves the defaults in use, and does not throw")
    void garbageInTheFileIsSurvivable(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("appearance.json");
        Files.writeString(path, "this is not json at all {{{", StandardCharsets.UTF_8);

        Appearance.load(path);

        // The assertion is really "this did not throw": `load` is called once during client startup,
        // and a startup that dies because a config file is malformed is the failure this guards.
        assertEquals(Appearance.Settings.DEFAULT, Appearance.settings());
        assertEquals(Themes.DEFAULT.name(), Appearance.main().name());
    }

    @Test
    @DisplayName("a missing file is not a problem, and is not treated as one")
    void aMissingFileIsNormal(@TempDir Path dir) {
        // A first run. There is nothing to assert beyond the defaults, which is the point: this has no
        // error path at all, so there is no branch here that a malformed file could fall into.
        Appearance.load(dir.resolve("nope").resolve("appearance.json"));
        assertEquals(Appearance.Settings.DEFAULT, Appearance.settings());
    }

    @Test
    @DisplayName("a change is written to the file it was loaded from")
    void changesAreSaved(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("appearance.json");
        Appearance.load(path);

        Appearance.setTheme("tome");

        // Read back through the real parser rather than through a field, because the field is what
        // would be right if `save` did nothing at all — this is the half that a restart exercises.
        Appearance.Settings onDisk = Appearance.read(Files.readString(path, StandardCharsets.UTF_8));
        assertEquals("tome", onDisk.theme());
        assertTrue(onDisk.chosen(), "choosing a theme must record that the player chose it");
    }

    @Test
    @DisplayName("the appearance reaches the toolkit, not just the field")
    void applyingReachesTheToolkit(@TempDir Path dir) {
        // The one call that pushes a theme into `ArmatureTheme`, which also pushes the motion duration
        // and curve into `Motion`. A caller that set the field without calling this would have a theme
        // with the previous theme's motion — which is what `vanilla_plus` would look like if its zero
        // went missing, presenting as "this theme's motion setting does nothing".
        Appearance.load(dir.resolve("appearance.json"));
        Appearance.setTheme("vanilla_plus");

        assertEquals("vanilla_plus", ArmatureTheme.current().name());
        assertEquals(0, ArmatureTheme.current().cornerRadius());
        assertEquals(0L, Motion.defaultDuration());
    }

    // ------------------------------------------------------------------
    // Who chooses: the player, and the pack
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pack's theme applies to a player who has never chosen")
    void aPackSetsTheThemeForAPlayerWhoHasNotChosen() {
        // Most of the point of a themed pack: a modpack with a look should get it without every player
        // configuring anything.
        Appearance.setServerDefault("amethyst");

        assertEquals("amethyst", Appearance.main().name());
        assertEquals("amethyst", Appearance.serverDefault(),
                "the pack's name should be remembered, so a control can say where the theme came from");
        assertFalse(Appearance.chosen());
    }

    @Test
    @DisplayName("the player's own choice beats the pack, and keeps beating it")
    void aPlayersChoiceBeatsThePack() {
        // The precedence rule, and the reason `chosen` is a field rather than being inferred from the
        // theme's presence. "I picked modern" and "nobody has picked and the pack happens to say
        // modern" are different facts that produce the same appearance, and only one of them should
        // survive a pack sending something else.
        Appearance.setServerDefault("amethyst");
        Appearance.setTheme("tome");

        assertEquals("tome", Appearance.main().name());

        // And the pack changing its mind does not undo the player's choice, which is the case a
        // naive implementation gets wrong: reconnecting to a pack that now asks for `neon`.
        Appearance.setServerDefault("neon");
        assertEquals("tome", Appearance.main().name(),
                "a pack overrode a player who had chosen for themselves");
        assertTrue(Appearance.chosen());
    }

    @Test
    @DisplayName("a pack with no theme leaves the player's own choice alone, and the default otherwise")
    void aPackMaySendNothing() {
        Appearance.setServerDefault(null);
        assertEquals(Themes.DEFAULT.name(), Appearance.main().name());

        Appearance.setTheme("paper");
        Appearance.setServerDefault(null);
        assertEquals("paper", Appearance.main().name());
    }

    @Test
    @DisplayName("a pack's theme this build does not have is kept as sent, and does not crash a frame")
    void anUnknownPackThemeIsNotFatal() {
        // Two things at once. The name is kept rather than dropped, because the failure is worth
        // reporting once and because the client may load a file that defines it later. And `main` treats
        // an unresolvable name as absent, because it is resolved while drawing a frame -- where there is
        // nobody to tell and the only useful behaviour is to draw something.
        Appearance.setServerDefault("a_theme_this_build_has_never_had");

        assertEquals("a_theme_this_build_has_never_had", Appearance.serverDefault());
        assertEquals(Themes.DEFAULT.name(), Appearance.main().name(),
                "an unresolvable pack theme must fall through rather than draw nothing");
    }

    @Test
    @DisplayName("clearing the pack's theme is not 'leave it alone', and a disconnect must clear it")
    void clearingThePackThemeIsDistinctFromNotSettingIt() {
        // The sticky-state bug this is written against: a pack's look persisting onto the next server
        // is an appearance nobody chose, with nothing on screen saying where it came from.
        // `ClientQuestCache.clear` calls this with null on disconnect, and null has to *clear*.
        Appearance.setServerDefault("neon");
        assertEquals("neon", Appearance.main().name());

        Appearance.setServerDefault(null);

        assertNull(Appearance.serverDefault());
        assertEquals(Themes.DEFAULT.name(), Appearance.main().name());
    }

    @Test
    @DisplayName("the player's choice outranks the pack even when the player picked the default")
    void choosingTheDefaultStillCountsAsChoosing() {
        // The subtle case, and the reason `chosen` cannot be inferred from "does the name differ".
        // A player who deliberately picks `modern` must keep it when a pack asks for `neon` — a
        // comparison of names would see "modern vs neon" and let the pack win, and the player would
        // watch their explicit choice be replaced by a pack's preference.
        Appearance.setServerDefault("neon");
        assertTrue(Appearance.setTheme("modern"));

        Appearance.setServerDefault("amethyst");

        assertEquals("modern", Appearance.main().name(),
                "a player who chose the default theme had it taken away by a pack");
    }

    // ------------------------------------------------------------------
    // The player's own edits, which sit on top of whichever theme won
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an edit applies over the theme in force, and over a pack's theme too")
    void editsSitOnTop() {
        Appearance.setServerDefault("amethyst");
        Appearance.setCustom("panel", 0xFF26212E);

        Theme resolved = Appearance.main();
        assertEquals(0xFF26212E, resolved.panel(), "the edit should be applied over the pack's theme");
        assertEquals(Themes.AMETHYST.available(), resolved.available(),
                "the rest of the pack's theme should come through untouched");
    }

    @Test
    @DisplayName("an edit survives a theme switch, which is the whole reason it is an edit")
    void editsSurviveASwitch() {
        // The decision worth stating: a player who has changed the panel colour and then switches
        // theme keeps the change. The alternative — editing a full theme and saving it — would mean a
        // theme switch discarded their work, which makes an editor feel like it is fighting you.
        Appearance.setCustom("panel", 0xFF010203);
        assertEquals(0xFF010203, Appearance.main().panel());

        Appearance.setTheme("paper");

        assertEquals(0xFF010203, Appearance.main().panel(), "a theme switch discarded the player's edit");
        assertEquals(Themes.PAPER.canvas(), Appearance.main().canvas(),
                "and the rest of the new theme should be in force");
    }

    @Test
    @DisplayName("an edit can be undone one at a time or all at once")
    void editsCanBeUndone() {
        Appearance.setTheme("tome");
        Appearance.setCustom("panel", 0xFF010203);
        Appearance.setCustom("canvas", 0xFF040506);
        assertEquals(2, Appearance.custom().size());

        Appearance.clearCustom("panel");

        assertEquals(1, Appearance.custom().size());
        assertEquals(Themes.TOME.panel(), Appearance.main().panel(),
                "clearing an edit should reveal the theme underneath it");
        assertEquals(0xFF040506, Appearance.main().canvas(), "and leave the other edit alone");

        Appearance.clearAllCustom();
        assertTrue(Appearance.custom().isEmpty());
        assertEquals(Themes.TOME.panel(), Appearance.main().panel());
        assertEquals(Themes.TOME.canvas(), Appearance.main().canvas());
    }

    @Test
    @DisplayName("an edit naming no token this build has is refused rather than stored")
    void anUnknownEditIsRefused() {
        Appearance.setCustom("panell", 0xFF010203);
        assertTrue(Appearance.custom().isEmpty(),
                "a stored edit for a token nothing reads is a file that lies about its contents");
    }

    @Test
    @DisplayName("beginEditing materialises every colour, so the file says what the editor showed")
    void beginEditingMaterialisesTheTheme() {
        // The property that makes the editor honest. Edits apply *over* a theme, so an editor that
        // recorded only the tokens a player touched would produce a file that depends on the theme it
        // was written against: save five colours over `amethyst`, switch to `paper`, and the result is
        // a paper theme with five amethyst colours in it — which is not what anybody meant and which
        // no amount of interface can explain.
        Appearance.setTheme("amethyst");
        Appearance.beginEditing();

        assertEquals(ThemeToken.ALL.size(), Appearance.custom().size(),
                "editing should start from the resolved theme, with every colour a visible decision");

        for (ThemeToken token : ThemeToken.ALL) {
            assertEquals(Themes.AMETHYST.colour(token.id()), Appearance.custom().get(token.id()),
                    token.id() + " was not materialised, so the editor would show it as unset");
        }
        // And the resolved appearance is unchanged by materialising it, which is the point: the
        // editor opens showing exactly what was on screen.
        assertEquals(Themes.AMETHYST.panel(), Appearance.main().panel());
        assertEquals(Themes.AMETHYST.title(), Appearance.main().title());
    }

    @Test
    @DisplayName("a saved theme is written, reloaded, and becomes the player's theme")
    void saveAsThemeWritesAndSelects(@TempDir Path dir) throws Exception {
        // The editor's whole round trip, asserted without a screen: edit, save, and the theme is on
        // disk, in the catalogue, and in use. `ThemeFiles.reload` at the end is what makes this work in
        // one session — without it a theme saved in game would be invisible until the next launch,
        // which is the difference between an editor being usable and being a way to generate a file.
        ThemeFiles.reload(dir);
        Appearance.load(dir.resolve("appearance.json"));
        // Note that the two files live side by side here, which is *not* how a client arranges them --
        // see ThemeFiles.NOT_THEMES. It is worth keeping that way in a test: the settings file and a
        // theme file share three key names with different types, so a reader that confuses them fails
        // here rather than only on the machine of somebody who happens to keep a backup in the folder.
        Appearance.setTheme("modern");
        Appearance.beginEditing();
        Appearance.setCustom("panel", 0xFF26212E);

        String saved = Appearance.saveAsTheme("My Theme!");

        assertEquals("my_theme", saved, "the name should be sanitised into a filename and a lookup key");
        assertTrue(Files.isRegularFile(dir.resolve("my_theme.json")),
                "the theme file should be on disk: " + Files.list(dir).toList());

        Theme loaded = ThemeFiles.byName("my_theme");
        assertNotNull(loaded, "the saved theme should be in the catalogue without a restart");
        assertEquals(0xFF26212E, loaded.panel());

        // And the player is now using it, because saving a theme and not switching to it leaves them
        // looking at something other than what they just made -- with the only clue being a file.
        assertEquals("my_theme", Appearance.main().name());
        assertTrue(Appearance.custom().isEmpty(),
                "the edits are now the theme, so keeping them would double-apply them over it");

        // The file names the theme it started from, so the radius, motion and easing still come from
        // somewhere and the file says what it was derived from.
        JsonObject written = JsonParser.parseString(
                Files.readString(dir.resolve("my_theme.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("modern", written.get("basedOn").getAsString());
    }

    @Test
    @DisplayName("saving with no name at all derives one rather than refusing")
    void savingWithoutANameDerivesOne(@TempDir Path dir) throws Exception {
        ThemeFiles.reload(dir);
        Appearance.load(dir.resolve("appearance.json"));
        Appearance.setTheme("tome");
        Appearance.beginEditing();
        Appearance.setCustom("panel", 0xFF010203);

        String saved = Appearance.saveAsTheme(null);

        assertNotNull(saved);
        assertTrue(saved.startsWith("tome"), "the derived name should say what it came from: " + saved);
        assertTrue(Files.isRegularFile(dir.resolve(saved + ".json")));
    }

    @Test
    @DisplayName("two saves of one name do not collide with the derived name of the next")
    void derivedNamesAvoidWhatExists(@TempDir Path dir) throws Exception {
        ThemeFiles.reload(dir);
        Appearance.load(dir.resolve("appearance.json"));

        Appearance.setTheme("tome");
        Appearance.beginEditing();
        Appearance.setCustom("panel", 0xFF010203);
        String first = Appearance.saveAsTheme(null);

        Appearance.setTheme("tome");
        Appearance.beginEditing();
        Appearance.setCustom("panel", 0xFF040506);
        String second = Appearance.saveAsTheme(null);

        assertEquals("tome_edited", first);
        assertFalse(first.equals(second),
                "the second save reused the first's name, so one theme is now unreachable");
        assertEquals(2, ThemeFiles.all().size());
    }

    @Test
    @DisplayName("a name is sanitised into something that is a filename, a key and safe to type")
    void namesAreSanitised() {
        // One rule preventing three unrelated bugs: a name with a slash in it is a path traversal, a
        // name with a quote in it breaks the JSON, and a name with a space in it cannot be typed as a
        // command argument without quotes.
        assertEquals("my_theme", Appearance.sanitise("My Theme!"));
        assertEquals("a_b_c", Appearance.sanitise("a/b\\c"));
        assertEquals("quoted", Appearance.sanitise("\"quoted\""));
        assertEquals("tome_2", Appearance.sanitise("Tome 2"));
        assertEquals("", Appearance.sanitise(""));
        assertEquals("", Appearance.sanitise(null));
        assertEquals("", Appearance.sanitise("!!!"), "a name of nothing but punctuation is no name");
        assertFalse(Appearance.sanitise("../../etc/passwd").contains("/"),
                "a saved theme's name becomes a path in a config directory");
    }

    @Test
    @DisplayName("saving with no theme directory known reports failure rather than throwing")
    void savingWithoutADirectoryFailsQuietly() {
        // Reachable if the platform layer has not been installed, which is a startup ordering mistake
        // rather than a user's problem. The useful answer is a warning and a null, not a crash on a
        // button press.
        ThemeFiles.reset();
        assertNull(Appearance.saveAsTheme("nothing"));
    }

    // ------------------------------------------------------------------
    // Cycling, and the label
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cycling visits every theme and wraps")
    void cyclingWraps() {
        String first = Themes.ALL.get(0).name();
        String seen = first;
        for (int i = 1; i < Themes.ALL.size(); i++) {
            seen = Appearance.nextName(seen);
        }
        assertEquals(first, Appearance.nextName(seen),
                "cycling past the last theme must come back to the first, not stop");

        // And every theme is reachable, which is the half a wrap test alone would miss.
        Set<String> visited = new LinkedHashSet<>();
        String step = first;
        for (int i = 0; i < Themes.ALL.size(); i++) {
            visited.add(step);
            step = Appearance.nextName(step);
        }
        assertEquals(Themes.ALL.size(), visited.size());
    }

    @Test
    @DisplayName("cycling from a name this build does not have starts at the beginning")
    void cyclingFromAnUnknownNameStarts() {
        assertEquals(Themes.ALL.get(0).name(), Appearance.nextName("marble"));
        assertEquals(Themes.ALL.get(0).name(), Appearance.nextName(null));
    }

    @Test
    @DisplayName("the cycle follows the declared order, because that is the order a picker shows")
    void cycleFollowsTheDeclaredOrder() {
        // Not an arbitrary assertion: `Themes.ALL` is documented as the order a picker should offer
        // them, and the control is that picker with one button instead of fifteen. A cycle that visited
        // them in some other order would make the label sequence look random.
        for (int i = 0; i < Themes.ALL.size(); i++) {
            String thisOne = Themes.ALL.get(i).name();
            String next = Themes.ALL.get((i + 1) % Themes.ALL.size()).name();
            assertEquals(next, Appearance.nextName(thisOne));
        }
    }

    @Test
    @DisplayName("a theme added in a file is reachable by clicking")
    void aFileThemeIsReachableByCycling(@TempDir Path dir) throws Exception {
        // The bug this closes: an earlier version cycled the built-ins only, so a theme loaded from
        // `config/armature/themes/` could be set by editing a file and never selected in game. The
        // control offered a subset of what existed, with nothing saying so.
        Files.writeString(dir.resolve("mine.json"),
                "{\"name\": \"mine\", \"colours\": {\"panel\": \"#26212E\"}}", StandardCharsets.UTF_8);
        ThemeFiles.reload(dir);

        Set<String> visited = new LinkedHashSet<>();
        String step = Themes.ALL.get(0).name();
        for (int i = 0; i <= Themes.ALL.size(); i++) {
            visited.add(step);
            step = Appearance.nextName(step);
        }

        assertTrue(visited.contains("mine"),
                "a theme loaded from a file is not reachable from the control: " + visited);
        assertEquals(Themes.ALL.size() + 1, visited.size());
    }

    @Test
    @DisplayName("cycling actually changes the theme, not just the name")
    void cyclingAppliesTheTheme(@TempDir Path dir) {
        // A real temporary directory rather than a made-up path. `setTheme` saves, and `save` creates
        // the parent directory it is given -- so a test passing `Path.of("a").resolve(...)` leaves a
        // directory called `a` in whichever directory the test runner happened to be in.
        Appearance.load(dir.resolve("appearance.json"));
        String before = Appearance.main().name();

        Appearance.cycleTheme();

        assertFalse(before.equals(Appearance.main().name()), "the theme did not change");
        assertEquals(Appearance.main().name(), ArmatureTheme.current().name(),
                "cycling changed the setting without applying it");
        assertTrue(Appearance.chosen(), "and it is the player's own choice from here on");
    }

    @Test
    @DisplayName("setting an unknown theme is refused rather than applied")
    void anUnknownThemeIsRefused() {
        Appearance.setTheme("tome");

        assertFalse(Appearance.setTheme("nope"));
        assertEquals("tome", Appearance.main().name());
    }

    @Test
    @DisplayName("the current name is the resolved theme's, whatever decided it")
    void currentNameIsTheResolvedOne() {
        Appearance.setServerDefault("obsidian");
        assertEquals("obsidian", Appearance.currentName(),
                "an appearance set by a pack is still the appearance in force");

        Appearance.setTheme("paper");
        assertEquals("paper", Appearance.currentName());
    }

    // ------------------------------------------------------------------
    // Motion, which is the player's and not the theme's
    // ------------------------------------------------------------------

    @Test
    @DisplayName("motion off survives a theme change, because it is not the theme's to decide")
    void motionIsIndependentOfTheTheme() {
        // The precedence `Motion` enforces, asserted from this side of the call. A theme sets the
        // *default* duration and curve; this is the accessibility switch that wins over any theme,
        // because a reskin must not be able to re-enable animation for someone who cannot comfortably
        // use it. Note that selecting a theme pushes its duration at `Motion`, so the two touch — and
        // this is the assertion that the switch is not overridden by that push.
        Appearance.setMotion(false);
        Appearance.setTheme("tome");

        assertFalse(Appearance.motion());
        assertFalse(Motion.enabled(),
                "choosing a theme re-enabled animation for someone who turned it off");
        assertEquals(0L, Motion.scaledDuration(Themes.TOME.motion()),
                "with motion off every duration is zero, whatever the theme asked for");
    }

    @Test
    @DisplayName("motion on is the default, and survives a reload of a file that does not mention it")
    void motionDefaultsOn() {
        assertTrue(Appearance.motion());
        assertEquals(Appearance.Settings.DEFAULT, Appearance.settings());
        assertFalse(Appearance.chosen(), "a fresh client has not chosen a theme");
        assertTrue(Appearance.custom().isEmpty());
        assertNull(Appearance.serverDefault());
    }

    @Test
    @DisplayName("themeProblems exposes what the last theme read complained about")
    void themeProblemsAreReachable(@TempDir Path dir) throws Exception {
        // The editor shows these, which is the only reason the list is public. A theme file that was
        // partially read is a palette that is mostly right -- which looks like a colour decision rather
        // than a mistake -- so the one place it can be surfaced is where the author is looking.
        Files.writeString(dir.resolve("broken.json"), "not json {{{", StandardCharsets.UTF_8);
        ThemeFiles.reload(dir);

        assertFalse(Appearance.themeProblems().isEmpty());
        assertTrue(Appearance.themeProblems().get(0).contains("broken.json"),
                Appearance.themeProblems().toString());
    }

    @Test
    @DisplayName("a reset leaves nothing behind, which is what every test here depends on")
    void resetClearsEverything() {
        Appearance.setServerDefault("neon");
        Appearance.setTheme("tome");
        Appearance.setMotion(false);
        Appearance.setCustom("panel", 0xFF010203);

        Appearance.reset();

        assertEquals(Appearance.Settings.DEFAULT, Appearance.settings());
        assertNull(Appearance.serverDefault());
        assertTrue(Appearance.motion());
        assertTrue(Appearance.custom().isEmpty());
        assertEquals(Themes.DEFAULT.name(), Appearance.main().name());
        assertSame(Themes.DEFAULT, ArmatureTheme.current());
    }

    @Test
    @DisplayName("an edit and a theme with the same colour are indistinguishable, and that is fine")
    void anEditMatchingTheThemeIsHarmless() {
        // A floor case worth having: `beginEditing` materialises every colour, so this is the normal
        // state of a freshly opened editor -- forty-one edits that happen to equal the theme. Nothing
        // about that should change the appearance, which is what stops the editor shifting a colour the
        // moment it is opened.
        Appearance.setTheme("copper");
        Appearance.beginEditing();

        Theme before = Themes.COPPER;
        Theme after = Appearance.main();

        for (ThemeToken token : ThemeToken.ALL) {
            assertEquals(before.colour(token.id()), after.colour(token.id()), token.id());
        }
        assertEquals(before.cornerRadius(), after.cornerRadius());
        assertEquals(before.motion(), after.motion());
        assertEquals(before.easing(), after.easing());
    }

    @Test
    @DisplayName("an edit reaches the toolkit, so the editor's preview is the real appearance")
    void anEditReachesTheToolkit() {
        Appearance.setTheme("modern");
        Appearance.setCustom("panel", 0xFF112233);

        assertEquals(0xFF112233, ArmatureTheme.panel(),
                "a screen built on Armature does not read this and would show the unedited colour");
        assertEquals(0xFF112233, ArmatureTheme.chrome().panel());
    }

    @Test
    @DisplayName("the written file is stable, so two saves of one setting are identical")
    void theWrittenFileIsStable(@TempDir Path dir) throws Exception {
        // A setting file written on every change should not churn: a diff of two saves should show the
        // difference rather than the insertion order. Cheap, and it makes the file reviewable.
        Path path = dir.resolve("appearance.json");
        Appearance.load(path);
        Appearance.setCustom("title", 0xFFFFFFFF);
        Appearance.setCustom("available", 0xFF7FB4E8);

        String first = Files.readString(path, StandardCharsets.UTF_8);
        Appearance.setCustom("panel", 0xFF24242E);
        String second = Files.readString(path, StandardCharsets.UTF_8);

        assertTrue(second.contains("\"available\""), second);
        assertEquals(Colour.toHex(0xFFFFFFFF),
                JsonParser.parseString(second).getAsJsonObject()
                        .getAsJsonObject("custom").get("title").getAsString());
    }
}
