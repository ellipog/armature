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
 * <p>{@code Look} is process-wide — {@code ArmatureTheme.current()} reads it — so a test that sets
 * a theme leaves it set for the next one. {@code look.reset()} clears the settings, the pack's
 * default and the file association, and {@code ThemeFiles.reset()} clears the disk catalogue; without
 * both, a test could pass because of a theme another test wrote to a temporary directory that no longer
 * exists.
 */
@DisplayName("The main theme, and who chooses it")
class LookTest {

    /** One look per test: there is no global any more, which is the property this file now rests on. */
    private final Look look = new Look();


    @BeforeEach
    void resetLook() {
        look.reset();
        ThemeFiles.reset();
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("settings survive a round trip through the file format")
    void theFormatRoundTrips() {
        Look.Settings written =
                new Look.Settings("tome", false, true, Map.of("panel", 0xFF26212E));
        assertEquals(written, Look.read(Look.write(written)));
    }

    @Test
    @DisplayName("a missing field takes its default rather than failing")
    void missingFieldsTakeDefaults() {
        assertEquals(Themes.DEFAULT.name(), Look.read("{}").theme());
        assertTrue(Look.read("{}").motion(),
                "an absent motion field must mean on — a file written by an older build, or by hand"
                        + " with one field in it, must not turn animation off for someone who never"
                        + " asked for that");
        // Absent means "the player has not chosen", which is what lets a pack set a theme at all. A
        // file that defaulted this to true would make a pack's theme apply only until the file was
        // written for the first time, which is a bug that would look like the pack's theme not working.
        assertFalse(Look.read("{}").chosen(),
                "an absent 'chosen' must mean the player has not chosen, or a pack could never set one");

        assertEquals("tome", Look.read("{\"theme\": \"tome\"}").theme());
        assertFalse(Look.read("{\"motion\": false}").motion());
        assertTrue(Look.read("{\"chosen\": true}").chosen());
    }

    @Test
    @DisplayName("a theme name this build does not have falls back instead of throwing")
    void anUnknownThemeNameFallsBack() {
        // Reachable two ways, and both are ordinary: a file edited by hand, and a file left behind by a
        // build that had a theme this one does not. The alternative — throwing — would take the client
        // down on a typo in a config file, which is not a proportionate answer.
        assertEquals(Themes.DEFAULT.name(), Look.read("{\"theme\": \"marble\"}").theme());
    }

    @Test
    @DisplayName("an edited colour naming a token this build lost is dropped, with a warning")
    void anUnknownCustomTokenIsDropped() {
        // A custom edit is a value the editor wrote. A build that no longer has that token would
        // otherwise carry it around forever doing nothing, which is a file that lies about what it
        // contains — and this is exactly the shape of bug this project has found three times.
        Look.Settings read = Look.read(
                "{\"custom\": {\"panel\": \"#26212E\", \"panell\": \"#445566\"}}");

        assertEquals(1, read.custom().size());
        assertEquals(0xFF26212E, read.custom().get("panel"));
        assertFalse(read.custom().containsKey("panell"));
    }

    @Test
    @DisplayName("a custom colour that is not a hex value is dropped, and the others kept")
    void aBadCustomColourIsDropped() {
        Look.Settings read = Look.read(
                "{\"custom\": {\"panel\": \"#26212E\", \"canvas\": \"not a colour\"}}");
        assertEquals(1, read.custom().size());
        assertEquals(0xFF26212E, read.custom().get("panel"));
    }

    @Test
    @DisplayName("a file that is not JSON leaves the defaults in use, and does not throw")
    void garbageInTheFileIsSurvivable(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("appearance.json");
        Files.writeString(path, "this is not json at all {{{", StandardCharsets.UTF_8);

        look.load(path);

        // The assertion is really "this did not throw": `load` is called once during client startup,
        // and a startup that dies because a config file is malformed is the failure this guards.
        assertEquals(Look.Settings.DEFAULT, look.settings());
        assertEquals(Themes.DEFAULT.name(), look.main().name());
    }

    @Test
    @DisplayName("a missing file is not a problem, and is not treated as one")
    void aMissingFileIsNormal(@TempDir Path dir) {
        // A first run. There is nothing to assert beyond the defaults, which is the point: this has no
        // error path at all, so there is no branch here that a malformed file could fall into.
        look.load(dir.resolve("nope").resolve("appearance.json"));
        assertEquals(Look.Settings.DEFAULT, look.settings());
    }

    @Test
    @DisplayName("a change is written to the file it was loaded from")
    void changesAreSaved(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("appearance.json");
        look.load(path);

        look.setTheme("tome");

        // Read back through the real parser rather than through a field, because the field is what
        // would be right if `save` did nothing at all — this is the half that a restart exercises.
        Look.Settings onDisk = Look.read(Files.readString(path, StandardCharsets.UTF_8));
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
        look.load(dir.resolve("appearance.json"), dir);
        look.setTheme("vanilla_plus");

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
        look.setServerDefault("amethyst");

        assertEquals("amethyst", look.main().name());
        assertEquals("amethyst", look.serverDefault(),
                "the pack's name should be remembered, so a control can say where the theme came from");
        assertFalse(look.chosen());
    }

    @Test
    @DisplayName("the player's own choice beats the pack, and keeps beating it")
    void aPlayersChoiceBeatsThePack() {
        // The precedence rule, and the reason `chosen` is a field rather than being inferred from the
        // theme's presence. "I picked modern" and "nobody has picked and the pack happens to say
        // modern" are different facts that produce the same appearance, and only one of them should
        // survive a pack sending something else.
        look.setServerDefault("amethyst");
        look.setTheme("tome");

        assertEquals("tome", look.main().name());

        // And the pack changing its mind does not undo the player's choice, which is the case a
        // naive implementation gets wrong: reconnecting to a pack that now asks for `neon`.
        look.setServerDefault("neon");
        assertEquals("tome", look.main().name(),
                "a pack overrode a player who had chosen for themselves");
        assertTrue(look.chosen());
    }

    @Test
    @DisplayName("a pack with no theme leaves the player's own choice alone, and the default otherwise")
    void aPackMaySendNothing() {
        look.setServerDefault(null);
        assertEquals(Themes.DEFAULT.name(), look.main().name());

        look.setTheme("paper");
        look.setServerDefault(null);
        assertEquals("paper", look.main().name());
    }

    @Test
    @DisplayName("a pack's theme this build does not have is kept as sent, and does not crash a frame")
    void anUnknownPackThemeIsNotFatal() {
        // Two things at once. The name is kept rather than dropped, because the failure is worth
        // reporting once and because the client may load a file that defines it later. And `main` treats
        // an unresolvable name as absent, because it is resolved while drawing a frame -- where there is
        // nobody to tell and the only useful behaviour is to draw something.
        look.setServerDefault("a_theme_this_build_has_never_had");

        assertEquals("a_theme_this_build_has_never_had", look.serverDefault());
        assertEquals(Themes.DEFAULT.name(), look.main().name(),
                "an unresolvable pack theme must fall through rather than draw nothing");
    }

    @Test
    @DisplayName("clearing the pack's theme is not 'leave it alone', and a disconnect must clear it")
    void clearingThePackThemeIsDistinctFromNotSettingIt() {
        // The sticky-state bug this is written against: a pack's look persisting onto the next server
        // is an appearance nobody chose, with nothing on screen saying where it came from.
        // `ClientQuestCache.clear` calls this with null on disconnect, and null has to *clear*.
        look.setServerDefault("neon");
        assertEquals("neon", look.main().name());

        look.setServerDefault(null);

        assertNull(look.serverDefault());
        assertEquals(Themes.DEFAULT.name(), look.main().name());
    }

    @Test
    @DisplayName("the player's choice outranks the pack even when the player picked the default")
    void choosingTheDefaultStillCountsAsChoosing() {
        // The subtle case, and the reason `chosen` cannot be inferred from "does the name differ".
        // A player who deliberately picks `modern` must keep it when a pack asks for `neon` — a
        // comparison of names would see "modern vs neon" and let the pack win, and the player would
        // watch their explicit choice be replaced by a pack's preference.
        look.setServerDefault("neon");
        assertTrue(look.setTheme("modern"));

        look.setServerDefault("amethyst");

        assertEquals("modern", look.main().name(),
                "a player who chose the default theme had it taken away by a pack");
    }

    // ------------------------------------------------------------------
    // The player's own edits, which sit on top of whichever theme won
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an edit applies over the theme in force, and over a pack's theme too")
    void editsSitOnTop() {
        look.setServerDefault("amethyst");
        look.setCustom("panel", 0xFF26212E);

        Theme resolved = look.main();
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
        look.setCustom("panel", 0xFF010203);
        assertEquals(0xFF010203, look.main().panel());

        look.setTheme("paper");

        assertEquals(0xFF010203, look.main().panel(), "a theme switch discarded the player's edit");
        assertEquals(Themes.PAPER.canvas(), look.main().canvas(),
                "and the rest of the new theme should be in force");
    }

    @Test
    @DisplayName("an edit can be undone one at a time or all at once")
    void editsCanBeUndone() {
        look.setTheme("tome");
        look.setCustom("panel", 0xFF010203);
        look.setCustom("canvas", 0xFF040506);
        assertEquals(2, look.custom().size());

        look.clearCustom("panel");

        assertEquals(1, look.custom().size());
        assertEquals(Themes.TOME.panel(), look.main().panel(),
                "clearing an edit should reveal the theme underneath it");
        assertEquals(0xFF040506, look.main().canvas(), "and leave the other edit alone");

        look.clearAllCustom();
        assertTrue(look.custom().isEmpty());
        assertEquals(Themes.TOME.panel(), look.main().panel());
        assertEquals(Themes.TOME.canvas(), look.main().canvas());
    }

    @Test
    @DisplayName("an edit naming no token this build has is refused rather than stored")
    void anUnknownEditIsRefused() {
        look.setCustom("panell", 0xFF010203);
        assertTrue(look.custom().isEmpty(),
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
        look.setTheme("amethyst");
        look.beginEditing();

        assertEquals(ThemeToken.ALL.size(), look.custom().size(),
                "editing should start from the resolved theme, with every colour a visible decision");

        for (ThemeToken token : ThemeToken.ALL) {
            assertEquals(Themes.AMETHYST.colour(token.id()), look.custom().get(token.id()),
                    token.id() + " was not materialised, so the editor would show it as unset");
        }
        // And the resolved appearance is unchanged by materialising it, which is the point: the
        // editor opens showing exactly what was on screen.
        assertEquals(Themes.AMETHYST.panel(), look.main().panel());
        assertEquals(Themes.AMETHYST.title(), look.main().title());
    }

    @Test
    @DisplayName("a saved theme is written, reloaded, and becomes the player's theme")
    void saveAsThemeWritesAndSelects(@TempDir Path dir) throws Exception {
        // The editor's whole round trip, asserted without a screen: edit, save, and the theme is on
        // disk, in the catalogue, and in use. `ThemeFiles.reload` at the end is what makes this work in
        // one session — without it a theme saved in game would be invisible until the next launch,
        // which is the difference between an editor being usable and being a way to generate a file.
        ThemeFiles.reload(dir);
        // The directory the theme is written into is the caller's now -- that is the whole point of the
        // split, so a test that saves supplies one exactly as a mod does.
        look.load(dir.resolve("appearance.json"), dir);
        // Note that the two files live side by side here, which is *not* how a client arranges them --
        // see ThemeFiles.NOT_THEMES. It is worth keeping that way in a test: the settings file and a
        // theme file share three key names with different types, so a reader that confuses them fails
        // here rather than only on the machine of somebody who happens to keep a backup in the folder.
        look.setTheme("modern");
        look.beginEditing();
        look.setCustom("panel", 0xFF26212E);

        String saved = look.saveAsTheme("My Theme!");

        assertEquals("my_theme", saved, "the name should be sanitised into a filename and a lookup key");
        assertTrue(Files.isRegularFile(dir.resolve("my_theme.json")),
                "the theme file should be on disk: " + Files.list(dir).toList());

        Theme loaded = ThemeFiles.byName("my_theme");
        assertNotNull(loaded, "the saved theme should be in the catalogue without a restart");
        assertEquals(0xFF26212E, loaded.panel());

        // And the player is now using it, because saving a theme and not switching to it leaves them
        // looking at something other than what they just made -- with the only clue being a file.
        assertEquals("my_theme", look.main().name());
        assertTrue(look.custom().isEmpty(),
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
        // The directory the theme is written into is the caller's now -- that is the whole point of the
        // split, so a test that saves supplies one exactly as a mod does.
        look.load(dir.resolve("appearance.json"), dir);
        look.setTheme("tome");
        look.beginEditing();
        look.setCustom("panel", 0xFF010203);

        String saved = look.saveAsTheme(null);

        assertNotNull(saved);
        assertTrue(saved.startsWith("tome"), "the derived name should say what it came from: " + saved);
        assertTrue(Files.isRegularFile(dir.resolve(saved + ".json")));
    }

    @Test
    @DisplayName("two saves of one name do not collide with the derived name of the next")
    void derivedNamesAvoidWhatExists(@TempDir Path dir) throws Exception {
        ThemeFiles.reload(dir);
        // The directory the theme is written into is the caller's now -- that is the whole point of the
        // split, so a test that saves supplies one exactly as a mod does.
        look.load(dir.resolve("appearance.json"), dir);

        look.setTheme("tome");
        look.beginEditing();
        look.setCustom("panel", 0xFF010203);
        String first = look.saveAsTheme(null);

        look.setTheme("tome");
        look.beginEditing();
        look.setCustom("panel", 0xFF040506);
        String second = look.saveAsTheme(null);

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
        assertEquals("my_theme", Look.sanitise("My Theme!"));
        assertEquals("a_b_c", Look.sanitise("a/b\\c"));
        assertEquals("quoted", Look.sanitise("\"quoted\""));
        assertEquals("tome_2", Look.sanitise("Tome 2"));
        assertEquals("", Look.sanitise(""));
        assertEquals("", Look.sanitise(null));
        assertEquals("", Look.sanitise("!!!"), "a name of nothing but punctuation is no name");
        assertFalse(Look.sanitise("../../etc/passwd").contains("/"),
                "a saved theme's name becomes a path in a config directory");
    }

    @Test
    @DisplayName("saving with no theme directory supplied reports failure rather than throwing")
    void savingWithoutADirectoryFailsQuietly(@TempDir Path dir) {
        // Still a real case, and now a *caller's* mistake rather than a missing platform: a mod that
        // loads its settings without telling the library where to save. The refusal is a null and a log
        // line, because an editor's Save button that throws is worse than one that says nothing happened.
        // One argument, and the missing one is the point of the test: this is a caller that named its
        // settings file and never said where a theme should go.
        look.reset();
        look.load(dir.resolve("appearance.json"));
        look.setTheme("tome");
        look.beginEditing();
        look.setCustom("panel", 0xFF010203);

        assertNull(look.saveAsTheme(null), "nowhere to write is not a crash");
        assertNull(look.saveAsTheme("named"), "and a name does not conjure a directory");
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
            seen = Look.nextName(seen);
        }
        assertEquals(first, Look.nextName(seen),
                "cycling past the last theme must come back to the first, not stop");

        // And every theme is reachable, which is the half a wrap test alone would miss.
        Set<String> visited = new LinkedHashSet<>();
        String step = first;
        for (int i = 0; i < Themes.ALL.size(); i++) {
            visited.add(step);
            step = Look.nextName(step);
        }
        assertEquals(Themes.ALL.size(), visited.size());
    }

    @Test
    @DisplayName("cycling from a name this build does not have starts at the beginning")
    void cyclingFromAnUnknownNameStarts() {
        assertEquals(Themes.ALL.get(0).name(), Look.nextName("marble"));
        assertEquals(Themes.ALL.get(0).name(), Look.nextName(null));
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
            assertEquals(next, Look.nextName(thisOne));
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
            step = Look.nextName(step);
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
        look.load(dir.resolve("appearance.json"), dir);
        String before = look.main().name();

        look.cycleTheme();

        assertFalse(before.equals(look.main().name()), "the theme did not change");
        assertEquals(look.main().name(), ArmatureTheme.current().name(),
                "cycling changed the setting without applying it");
        assertTrue(look.chosen(), "and it is the player's own choice from here on");
    }

    @Test
    @DisplayName("setting an unknown theme is refused rather than applied")
    void anUnknownThemeIsRefused() {
        look.setTheme("tome");

        assertFalse(look.setTheme("nope"));
        assertEquals("tome", look.main().name());
    }

    @Test
    @DisplayName("the current name is the resolved theme's, whatever decided it")
    void currentNameIsTheResolvedOne() {
        look.setServerDefault("obsidian");
        assertEquals("obsidian", look.currentName(),
                "an appearance set by a pack is still the appearance in force");

        look.setTheme("paper");
        assertEquals("paper", look.currentName());
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
        look.setMotion(false);
        look.setTheme("tome");

        assertFalse(look.motion());
        assertFalse(Motion.enabled(),
                "choosing a theme re-enabled animation for someone who turned it off");
        assertEquals(0L, Motion.scaledDuration(Themes.TOME.motion()),
                "with motion off every duration is zero, whatever the theme asked for");
    }

    @Test
    @DisplayName("motion on is the default, and survives a reload of a file that does not mention it")
    void motionDefaultsOn() {
        assertTrue(look.motion());
        assertEquals(Look.Settings.DEFAULT, look.settings());
        assertFalse(look.chosen(), "a fresh client has not chosen a theme");
        assertTrue(look.custom().isEmpty());
        assertNull(look.serverDefault());
    }

    @Test
    @DisplayName("themeProblems exposes what the last theme read complained about")
    void themeProblemsAreReachable(@TempDir Path dir) throws Exception {
        // The editor shows these, which is the only reason the list is public. A theme file that was
        // partially read is a palette that is mostly right -- which looks like a colour decision rather
        // than a mistake -- so the one place it can be surfaced is where the author is looking.
        Files.writeString(dir.resolve("broken.json"), "not json {{{", StandardCharsets.UTF_8);
        ThemeFiles.reload(dir);

        assertFalse(look.themeProblems().isEmpty());
        assertTrue(look.themeProblems().get(0).contains("broken.json"),
                look.themeProblems().toString());
    }

    @Test
    @DisplayName("a reset leaves nothing behind, which is what every test here depends on")
    void resetClearsEverything() {
        look.setServerDefault("neon");
        look.setTheme("tome");
        look.setMotion(false);
        look.setCustom("panel", 0xFF010203);

        look.reset();

        assertEquals(Look.Settings.DEFAULT, look.settings());
        assertNull(look.serverDefault());
        assertTrue(look.motion());
        assertTrue(look.custom().isEmpty());
        assertEquals(Themes.DEFAULT.name(), look.main().name());
        assertSame(Themes.DEFAULT, ArmatureTheme.current());
    }

    @Test
    @DisplayName("an edit and a theme with the same colour are indistinguishable, and that is fine")
    void anEditMatchingTheThemeIsHarmless() {
        // A floor case worth having: `beginEditing` materialises every colour, so this is the normal
        // state of a freshly opened editor -- forty-one edits that happen to equal the theme. Nothing
        // about that should change the appearance, which is what stops the editor shifting a colour the
        // moment it is opened.
        look.setTheme("copper");
        look.beginEditing();

        Theme before = Themes.COPPER;
        Theme after = look.main();

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
        look.setTheme("modern");
        look.setCustom("panel", 0xFF112233);

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
        look.load(path);
        look.setCustom("title", 0xFFFFFFFF);
        look.setCustom("available", 0xFF7FB4E8);

        String first = Files.readString(path, StandardCharsets.UTF_8);
        look.setCustom("panel", 0xFF24242E);
        String second = Files.readString(path, StandardCharsets.UTF_8);

        assertTrue(second.contains("\"available\""), second);
        assertEquals(Colour.toHex(0xFFFFFFFF),
                JsonParser.parseString(second).getAsJsonObject()
                        .getAsJsonObject("custom").get("title").getAsString());
    }

    @Test
    @DisplayName("the corner radius is a setting: clamped, written, and revertible")
    void theRadiusIsASetting(@TempDir Path dir) throws java.io.IOException {
        // A setting rather than a theme edit, so it gets the treatment every setting in this class gets:
        // clamped at the boundary it declares, written to the file only when somebody chose it, and
        // revertible to what the theme underneath says.
        look.reset();
        Path file = dir.resolve("appearance.json");
        look.load(file);

        int themeRadius = look.radius();
        assertFalse(look.radiusChosen(), "a fresh client takes the theme's corners");

        look.setRadius(6);
        assertEquals(6, look.radius());
        assertTrue(look.radiusChosen());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"radius\":6"),
                "and it is on disk, because a setting that is not written is not a setting");

        look.setRadius(999);
        assertEquals(Look.MAX_RADIUS, look.radius(),
                "clamped by the setting rather than by the control that offers it");

        look.clearRadius();
        assertFalse(look.radiusChosen());
        assertEquals(themeRadius, look.radius(), "back to the theme's own corners");
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("radius"),
                "and the file stops carrying one, which is what makes the theme's own reachable again");

        // A file with no radius is the theme's own: an older file keeps the look it always had.
        look.load(file);
        assertFalse(look.radiusChosen());
        assertEquals(themeRadius, look.radius());

        // And a theme's own radius is never taken away -- only overridden and given back.
        look.setRadius(0);
        assertEquals(0, look.radius(), "square corners are a look, not the absence of one");

        look.reset();
    }
}
