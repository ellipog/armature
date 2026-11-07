package dev.ellipog.armature.client;

import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.ThemeFiles;
import dev.ellipog.armature.client.ui.Themes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remembered main theme: asked twice it is one object, and asked after any change it is never the old
 * one.
 *
 * <h2>The shape of every test here</h2>
 *
 * <p>{@code main()} keeps its last answer, keyed on the settings record, the pack's default and the theme
 * catalogue's generation. The failure worth fearing is a stale theme (a click that changes nothing on
 * screen), so each test warms the memo first, then takes one path that changes an input, and holds the
 * answer to {@link #resolvedAfresh}: a copy of the resolution as it was before the memo, read from the
 * look's public state, which cannot be stale because it remembers nothing. Where the path has an obvious
 * visible effect, that is asserted too, so the test does not rest on the oracle alone.
 *
 * <p>The catalogue paths are the ones that need the generation: a file theme edited and reloaded, or the
 * catalogue forgotten, leaves the settings and the pack's default exactly as they were (the same objects),
 * so nothing on the look itself says anything changed.
 */
@DisplayName("The main theme, remembered")
class LookMainCacheTest {

    private final Look look = new Look();

    @BeforeEach
    void resetLook() {
        look.reset();
        ThemeFiles.reset();
    }

    @AfterEach
    void leaveNothingBehind() {
        // The catalogue and the toolkit's current theme are process-wide, and a test that wrote a theme
        // directory must not leave its themes resolvable by the next one.
        look.reset();
        ThemeFiles.reset();
    }

    /** {@code Look.main} as it was before it remembered anything: the oracle for every test here. */
    private static Theme resolvedAfresh(Look look) {
        Look.Settings settings = look.settings();
        String serverDefault = look.serverDefault();
        Theme base = null;
        if (settings.chosen()) {
            base = Themes.any(settings.theme());
        }
        if (base == null && serverDefault != null && !serverDefault.isEmpty()) {
            base = Themes.any(serverDefault);
        }
        if (base == null) {
            base = Themes.any(settings.theme());
        }
        if (base == null) {
            base = Themes.DEFAULT;
        }
        return settings.patch().applyTo(base);
    }

    /** Warms the memo, so the path that follows has something it could wrongly serve. */
    private Theme warm() {
        Theme theme = look.main();
        assertSame(theme, look.main(), "nothing changed, so the second ask should be the remembered answer");
        return theme;
    }

    /** The answer is the fresh resolution, and the toolkit was handed the same one. */
    private void assertCurrent(String after) {
        Theme expected = resolvedAfresh(look);
        assertEquals(expected, look.main(), "after " + after + ", main() served a stale theme");
        assertEquals(expected, ArmatureTheme.current(),
                "after " + after + ", the toolkit was handed a stale theme");
    }

    /** A file theme over modern that changes one colour, which is enough for the catalogue to load it. */
    private static void writeTheme(Path dir, String name, int panel) throws Exception {
        String json = String.format("{\"name\": \"%s\", \"basedOn\": \"modern\","
                + " \"colours\": {\"panel\": \"#%08X\"}}", name, panel);
        Files.writeString(dir.resolve(name + ".json"), json, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Remembering at all
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with nothing changed, main() is one object however often it is asked")
    void anUnchangedLookIsOneObject() {
        Theme plain = warm();
        assertEquals(resolvedAfresh(look), plain);

        look.setCustom("panel", 0xFF26212E);
        look.setRadius(3);
        Theme edited = warm();
        assertEquals(resolvedAfresh(look), edited);
        for (int i = 0; i < 50; i++) {
            assertSame(edited, look.main(), "a frame asks dozens of times, and each ask is the same answer");
        }
        assertEquals(edited.cornerRadius(), look.radius(), "and the accessors that go through it agree");
        assertEquals(edited.background(), look.background());
    }

    // ------------------------------------------------------------------
    // Every setting that changes what main() reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("choosing a theme, and clearing the choice, are never served stale")
    void themeChoices() {
        warm();
        look.setTheme("tome");
        assertCurrent("setTheme");
        assertEquals("tome", look.main().name());

        warm();
        look.setServerDefault("amethyst");
        look.clearChoice();
        assertCurrent("clearChoice");
        assertEquals("amethyst", look.main().name());

        warm();
        look.cycleTheme();
        assertCurrent("cycleTheme");
    }

    @Test
    @DisplayName("every colour edit, and every undo of one, is never served stale")
    void colourEdits() {
        look.setTheme("modern");
        warm();
        look.setCustom("panel", 0xFF010203);
        assertCurrent("setCustom");
        assertEquals(0xFF010203, look.main().panel());

        warm();
        look.setCustom("panel", 0xFF040506);
        assertCurrent("setCustom over an edit");
        assertEquals(0xFF040506, look.main().panel());

        look.setCustom("canvas", 0xFF070809);
        warm();
        look.clearCustom("panel");
        assertCurrent("clearCustom");
        assertEquals(Themes.MODERN.panel(), look.main().panel());

        warm();
        look.clearAllCustom();
        assertCurrent("clearAllCustom");
        assertEquals(Themes.MODERN.canvas(), look.main().canvas());

        look.setTheme("amethyst");
        warm();
        look.beginEditing();
        assertCurrent("beginEditing");
    }

    @Test
    @DisplayName("the radius and the background are never served stale")
    void radiusAndBackground() {
        look.setTheme("tome");
        warm();
        look.setRadius(11);
        assertCurrent("setRadius");
        assertEquals(11, look.radius());

        warm();
        look.clearRadius();
        assertCurrent("clearRadius");
        assertEquals(Themes.TOME.cornerRadius(), look.radius());

        warm();
        look.setBackground(CanvasBackground.NONE);
        assertCurrent("setBackground");
        assertTrue(look.background().isNone());

        warm();
        look.setBackground(null);
        assertCurrent("setBackground(null)");
        assertEquals(Themes.TOME.background(), look.background());
    }

    @Test
    @DisplayName("the accessibility settings replace the settings too, and change no colour")
    void accessibilitySettings() {
        look.setCustom("panel", 0xFF112233);
        Theme before = warm();

        look.setMotion(false);
        assertCurrent("setMotion");
        look.setTextScale(1.5);
        assertCurrent("setTextScale");
        assertEquals(before, look.main(), "neither is part of the theme, so the value is unchanged");
    }

    @Test
    @DisplayName("the pack's default, set, changed and cleared, is never served stale")
    void thePacksDefault() {
        warm();
        look.setServerDefault("neon");
        assertCurrent("setServerDefault");
        assertEquals("neon", look.main().name());

        warm();
        look.setServerDefault("obsidian");
        assertCurrent("setServerDefault to another");
        assertEquals("obsidian", look.main().name());

        warm();
        look.setServerDefault(null);
        assertCurrent("setServerDefault(null)");
        assertEquals(Themes.DEFAULT.name(), look.main().name());
    }

    @Test
    @DisplayName("loading a settings file, and resetting, are never served stale")
    void loadAndReset(@TempDir Path dir) throws Exception {
        Path settings = dir.resolve("appearance.json");
        Files.writeString(settings, "{\"theme\": \"paper\", \"chosen\": true, \"radius\": 9}",
                StandardCharsets.UTF_8);

        warm();
        look.load(settings);
        assertCurrent("load");
        assertEquals("paper", look.main().name());
        assertEquals(9, look.radius());

        warm();
        look.reset();
        assertCurrent("reset");
        assertSame(Themes.DEFAULT, look.main(), "a reset look draws the default itself");
    }

    // ------------------------------------------------------------------
    // The catalogue, which changes with nothing on the look changing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a file theme edited and reloaded is the new file, not the remembered one")
    void aReloadedFileTheme(@TempDir Path dir) throws Exception {
        writeTheme(dir, "mine", 0xFF111111);
        ThemeFiles.reload(dir);
        look.setTheme("mine");
        Theme first = warm();
        assertEquals(0xFF111111, first.panel());

        // The settings and the pack's default are the very same objects across this reload, so the only
        // thing that can say the answer moved is the catalogue's generation.
        Look.Settings settingsBefore = look.settings();
        writeTheme(dir, "mine", 0xFF222222);
        ThemeFiles.reload(dir);

        assertSame(settingsBefore, look.settings(), "the reload is the only change");
        assertEquals(0xFF222222, look.main().panel(), "main() served the theme from before the reload");
        assertEquals(resolvedAfresh(look), look.main());
    }

    @Test
    @DisplayName("a forgotten catalogue falls back at once, and a late file theme is picked up at once")
    void aChangedCatalogue(@TempDir Path dir) throws Exception {
        writeTheme(dir, "mine", 0xFF333333);
        ThemeFiles.reload(dir);
        look.setTheme("mine");
        warm();

        ThemeFiles.reset();
        assertEquals(Themes.DEFAULT.name(), look.main().name(),
                "the file theme is gone, so the frame has to fall through rather than draw it");
        assertEquals(resolvedAfresh(look), look.main());

        // The other direction: a pack names a theme this client has not loaded, and then it loads. The
        // look's class note promises the name is kept for exactly this.
        look.reset();
        ThemeFiles.reset();
        look.setServerDefault("late");
        assertEquals(Themes.DEFAULT.name(), warm().name());
        writeTheme(dir, "late", 0xFF444444);
        ThemeFiles.reload(dir);
        assertEquals("late", look.main().name(), "a theme that arrived after the pack named it was not seen");
        assertEquals(0xFF444444, look.main().panel());
    }

    @Test
    @DisplayName("loading with a theme directory reads it, and the theme it holds is what main() resolves")
    void loadReadsTheDirectory(@TempDir Path dir) throws Exception {
        Path settings = dir.resolve("appearance.json");
        Files.writeString(settings, "{\"theme\": \"mine\", \"chosen\": true}", StandardCharsets.UTF_8);
        warm();

        writeTheme(dir, "mine", 0xFF555555);
        look.load(settings, dir);
        assertCurrent("load with a theme directory");
        assertEquals("mine", look.main().name());

        // And a second load of the same files, after one of them changed on disk.
        writeTheme(dir, "mine", 0xFF666666);
        warm();
        look.load(settings, dir);
        assertCurrent("a second load");
        assertEquals(0xFF666666, look.main().panel());
    }

    @Test
    @DisplayName("saving a theme from the editor is the saved theme at once, and so is overwriting it")
    void savingATheme(@TempDir Path dir) throws Exception {
        look.load(dir.resolve("appearance.json"), dir);
        look.setTheme("modern");
        look.beginEditing();
        look.setCustom("panel", 0xFF777777);
        warm();

        assertEquals("saved", look.saveAsTheme("saved"));
        assertCurrent("saveAsTheme");
        assertEquals("saved", look.main().name());
        assertEquals(0xFF777777, look.main().panel());

        // Over the same name, edited again from the theme it was first made from: the file changes, and the
        // theme in force has to change with it.
        look.setTheme("modern");
        look.beginEditing();
        look.setCustom("panel", 0xFF888888);
        warm();
        assertEquals("saved", look.saveAsTheme("saved"));
        assertCurrent("saveAsTheme over itself");
        assertEquals("saved", look.main().name(), "the overwritten file did not load");
        assertEquals(0xFF888888, look.main().panel());
        assertNotEquals(0xFF777777, look.main().panel());
    }

    @Test
    @DisplayName("two looks over one catalogue each see its changes")
    void twoLooksShareTheCatalogue(@TempDir Path dir) throws Exception {
        // The catalogue is process-wide and a look is not, so one look's reload has to reach the other's
        // memo -- a book in one look and a panel in another is the arrangement the library is built for.
        Look other = new Look();
        try {
            writeTheme(dir, "shared", 0xFF999999);
            ThemeFiles.reload(dir);
            look.setTheme("shared");
            other.setTheme("shared");
            warm();
            other.main();

            writeTheme(dir, "shared", 0xFFAAAAAA);
            other.load(dir.resolve("other.json"), dir);

            assertEquals(0xFFAAAAAA, look.main().panel(), "the other look's reload did not reach this one");
            assertEquals(0xFFAAAAAA, other.main().panel());
        }
        finally {
            other.reset();
        }
    }
}
