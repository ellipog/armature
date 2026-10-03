package dev.ellipog.armature.client.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Themes read from {@code config/armature/themes/}: the file half of the catalogue.
 *
 * <h2>Every failure here is a warning and never fatal, and that is worth testing explicitly</h2>
 *
 * <p>This runs during client initialisation, where an exception is a launcher-level failure. A player
 * whose appearance directory has a typo in it should get the default appearance and their saved progress — so
 * every test below that supplies something broken asserts two things: that it was reported, and that
 * the client carries on with what it could read.
 *
 * <p>The other half matters just as much and is easier to get wrong. A failure that is survivable must
 * not be <i>silent</i>, because the failure mode of a partially-read theme is a palette that is mostly
 * right — which looks like a colour decision rather than a mistake. That is the specific thing this
 * project has recorded three times: a value that is parsed, validated and printed by something while
 * nothing reads it.
 *
 * <h2>A temporary directory per test, and the registry reset between them</h2>
 *
 * <p>{@link ThemeFiles} is consulted by {@code Themes.any}, which means a test that loaded a theme
 * leaves it resolvable for the next one. {@code @AfterEach} clears that: a test that passes because of
 * another test's state is worse than one that fails.
 */
@DisplayName("Themes read from a file")
class ThemeFilesTest {

    @BeforeEach
    @AfterEach
    void resetThemes() {
        ThemeFiles.reset();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a file is read, named, and resolvable through the ordinary lookup")
    void aFileBecomesATheme() throws IOException {
        // The whole feature in one test: a JSON file on disk becomes a theme that `Themes.any` finds,
        // and its colours are its own where it stated them and the base's where it did not.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "my_pack.json", """
                    {
                      "name": "my_pack",
                      "basedOn": "modern",
                      "colours": { "panel": "#26212E", "available": "#C79BF0" },
                      "cornerRadius": 6
                    }
                    """);

            ThemeFiles.reload(dir);

            assertTrue(ThemeFiles.problems().isEmpty(), "problems: " + ThemeFiles.problems());
            assertEquals(1, ThemeFiles.all().size());

            Theme loaded = ThemeFiles.byName("my_pack");
            assertNotNull(loaded);
            assertEquals(0xFF26212E, loaded.panel(), "the file's own colour should be in force");
            assertEquals(0xFFC79BF0, loaded.available());
            assertEquals(6, loaded.cornerRadius(), "the file set a radius");
            assertEquals(Themes.MODERN.title(), loaded.title(), "unstated colours come from basedOn");
            assertEquals(Themes.MODERN.motion(), loaded.motion(), "and so do the non-colour values");

            // And `Themes.any` -- which is what a setting, a chapter and the cycle all use -- resolves
            // it. If this failed, a theme could be loaded and not selectable, which is the state that
            // makes a feature look half-built from the outside.
            assertSame(loaded, Themes.any("my_pack"));
            assertTrue(Themes.everything().contains(loaded), "the picker should offer it");
            assertNull(Themes.byName("my_pack"),
                    "byName is the built-ins alone, so a file theme must not appear there; `any` is the"
                            + " lookup that includes files");
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("the name comes from the file, or from its filename when it does not say")
    void theFilenameIsTheFallbackName() throws IOException {
        // A file with no `name` is a reasonable thing to write, and the filename is the obvious answer
        // -- it is also what the editor uses, since it saves to `<name>.json`.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "from_the_filename.json",
                    "{\"basedOn\": \"modern\", \"colours\": {\"panel\": \"#26212E\"}}");

            ThemeFiles.reload(dir);

            assertNotNull(ThemeFiles.byName("from_the_filename"));
            assertEquals("from_the_filename", ThemeFiles.all().get(0).name());
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a file may be based on another file, in any filename order")
    void filesCanBuildOnEachOther() throws IOException {
        // The reason the reader does two passes. The filesystem returns names in whatever order it
        // likes, so a file based on another must not depend on which came first. Here "aaa" is based on
        // "zzz", which is the reverse of alphabetical order.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "aaa.json",
                    "{\"name\": \"aaa\", \"basedOn\": \"zzz\", \"colours\": {\"canvas\": \"#111111\"}}");
            write(dir, "zzz.json",
                    "{\"name\": \"zzz\", \"basedOn\": \"modern\", \"colours\": {\"panel\": \"#222222\"}}");

            ThemeFiles.reload(dir);

            assertTrue(ThemeFiles.problems().isEmpty(), "problems: " + ThemeFiles.problems());
            Theme derived = ThemeFiles.byName("aaa");
            assertNotNull(derived, "a file based on a file that loads later must still resolve");
            assertEquals(0xFF111111, derived.canvas(), "its own colour");
            assertEquals(0xFF222222, derived.panel(), "the colour from the file it builds on");
            assertEquals(Themes.MODERN.title(), derived.title(), "and the built-in underneath both");
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a file with no basedOn starts from the default theme")
    void noBaseMeansTheDefault() throws IOException {
        // `Themes.DEFAULT`, not `Themes.MODERN`, and the two are no longer the same object — so this
        // test is asserting something about the fallback that it previously could not. A theme file
        // written without a base takes the *shipped* appearance, which is what "the default" has to mean
        // for an author: the thing their client is actually drawn in while they are writing the file.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "bare.json", "{\"colours\": {\"panel\": \"#26212E\"}}");
            ThemeFiles.reload(dir);

            Theme bare = ThemeFiles.byName("bare");
            assertNotNull(bare);
            assertEquals(Themes.DEFAULT.title(), bare.title());
            assertEquals(Themes.DEFAULT.cornerRadius(), bare.cornerRadius(),
                    "a file with no base should be square, because the shipped default is -- a file that"
                            + " silently started rounded would mean the fallback was pointing at `modern`");
            assertEquals(0, bare.cornerRadius());
        }
        finally {
            deleteRecursively(dir);
        }
    }

    // ------------------------------------------------------------------
    // All the ways a file can be wrong, and none of them fatal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty or missing directory is not a problem and not a warning")
    void anAbsentDirectoryIsNormal() {
        // A first run. There is nothing to assert beyond the empty list, which is the point: this has
        // no error path at all, so there is no branch here that a broken file could fall into.
        ThemeFiles.reload(Path.of("this", "directory", "does", "not", "exist"));

        assertTrue(ThemeFiles.all().isEmpty());
        assertTrue(ThemeFiles.problems().isEmpty(),
                "a missing directory is what a first run looks like, and it must not warn");
        assertTrue(ThemeFiles.isEmpty());
    }

    @Test
    @DisplayName("a file that is not JSON is reported, and the others still load")
    void malformedJsonIsSurvivable() throws IOException {
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "broken.json", "this is not json at all {{{");
            write(dir, "fine.json", "{\"name\": \"fine\", \"colours\": {\"panel\": \"#26212E\"}}");

            ThemeFiles.reload(dir);

            assertEquals(1, ThemeFiles.problems().size(), ThemeFiles.problems().toString());
            assertTrue(ThemeFiles.problems().get(0).contains("broken.json"), ThemeFiles.problems().toString());
            assertNotNull(ThemeFiles.byName("fine"), "one bad file must not stop the good ones loading");
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a JSON array at the top level is reported rather than crashing on a cast")
    void aNonObjectIsReported() throws IOException {
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "array.json", "[1, 2, 3]");
            ThemeFiles.reload(dir);

            assertEquals(1, ThemeFiles.problems().size(), ThemeFiles.problems().toString());
            assertTrue(ThemeFiles.problems().get(0).contains("should be a JSON object"),
                    ThemeFiles.problems().toString());
            assertTrue(ThemeFiles.isEmpty());
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a file that changes nothing is reported rather than registered")
    void aNoOpFileIsNotRegistered() throws IOException {
        // A file that changes nothing is almost always a mistake rather than a statement, and
        // registering it would put a name in the picker that looks exactly like the theme it is based
        // on -- the same "a control that does nothing looks broken" argument as the empty chapter.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "nothing.json", "{\"basedOn\": \"modern\"}");
            ThemeFiles.reload(dir);

            assertTrue(ThemeFiles.isEmpty());
            assertEquals(1, ThemeFiles.problems().size());
            assertTrue(ThemeFiles.problems().get(0).contains("changes nothing"),
                    ThemeFiles.problems().toString());
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("two themes naming each other are reported as unresolvable, with a reason")
    void aCycleIsReported() throws IOException {
        // The failure the resolution loop's "progress" flag exists for: with two files naming each
        // other, neither can resolve and the loop has to stop rather than spin. Both are reported, and
        // the message has to explain that the reason is a cycle rather than a typo -- because against a
        // file that clearly defines the name it asks for, "no theme called x" sends an author looking in
        // the wrong place entirely.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "one.json", "{\"name\": \"one\", \"basedOn\": \"two\", \"colours\": {\"panel\": \"#111111\"}}");
            write(dir, "two.json", "{\"name\": \"two\", \"basedOn\": \"one\", \"colours\": {\"panel\": \"#222222\"}}");

            ThemeFiles.reload(dir);

            assertTrue(ThemeFiles.isEmpty(), "neither file can resolve, so neither is a theme");
            assertEquals(2, ThemeFiles.problems().size());
            for (String problem : ThemeFiles.problems()) {
                assertTrue(problem.contains("which is neither a built-in nor a theme that loaded"),
                        problem);
                assertTrue(problem.contains("name each other"),
                        "the message should name the cycle as the likely cause: " + problem);
            }
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a basedOn that names nothing lists what does exist")
    void anUnknownBaseIsReported() throws IOException {
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "mine.json", "{\"basedOn\": \"marble\", \"colours\": {\"panel\": \"#111111\"}}");
            ThemeFiles.reload(dir);

            assertTrue(ThemeFiles.isEmpty());
            assertEquals(1, ThemeFiles.problems().size());
            assertTrue(ThemeFiles.problems().get(0).contains("modern"),
                    "the message should list the built-ins: " + ThemeFiles.problems().get(0));
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("two files claiming one name: the first wins and the second is reported")
    void aDuplicateNameIsReported() throws IOException {
        // Reachable by copying a file and forgetting to rename it, which is a thing people do. One of
        // them has to win, and the other being silently discarded is exactly the failure this project
        // keeps removing -- so it is reported and the surviving theme is the one that loaded first.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "a.json", "{\"name\": \"duplicate\", \"colours\": {\"panel\": \"#111111\"}}");
            write(dir, "b.json", "{\"name\": \"duplicate\", \"colours\": {\"panel\": \"#222222\"}}");

            ThemeFiles.reload(dir);

            assertEquals(1, ThemeFiles.all().size(), "only one theme should be registered");
            assertEquals(1, ThemeFiles.problems().size(), ThemeFiles.problems().toString());
            assertTrue(ThemeFiles.problems().get(0).contains("already loaded"),
                    ThemeFiles.problems().get(0));
            assertNotNull(ThemeFiles.byName("duplicate"));
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a misspelled colour token is reported, and the file's other colours still apply")
    void anUnknownTokenIsReported() throws IOException {
        // The quietest failure of the whole feature, and the reason it is tested here: the theme loads,
        // the picker lists it, and one colour is simply not what the author wrote. Nothing looks broken
        // -- it looks like a colour choice.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "typo.json", """
                    {
                      "name": "typo",
                      "colours": { "panel": "#26212E", "panell": "#445566" }
                    }
                    """);

            ThemeFiles.reload(dir);

            assertNotNull(ThemeFiles.byName("typo"), "one bad key must not lose the whole file");
            assertEquals(1, ThemeFiles.problems().size(), ThemeFiles.problems().toString());
            assertTrue(ThemeFiles.problems().get(0).contains("panell"), ThemeFiles.problems().get(0));
            assertTrue(ThemeFiles.problems().get(0).contains("panel"),
                    "the message should list the real tokens: " + ThemeFiles.problems().get(0));
        }
        finally {
            deleteRecursively(dir);
        }
    }

    // ------------------------------------------------------------------
    // Reloading, and the editor's round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reloading picks up a file added after the first read")
    void reloadingSeesNewFiles() throws IOException {
        // The property that makes an in-game editor usable rather than a way to generate a file you
        // still have to restart to see. The editor writes and then reloads; if this did not work, a
        // theme saved in game would be invisible until the next launch.
        Path dir = Files.createTempDirectory("themes");
        try {
            ThemeFiles.reload(dir);
            assertTrue(ThemeFiles.isEmpty());

            write(dir, "later.json", "{\"name\": \"later\", \"colours\": {\"panel\": \"#111111\"}}");
            ThemeFiles.reload(dir);

            assertNotNull(ThemeFiles.byName("later"));
            assertTrue(ThemeFiles.problems().isEmpty(), ThemeFiles.problems().toString());
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("reloading a directory forgets what the previous one had")
    void reloadingForgets() throws IOException {
        // Two directories, and the second read must not inherit the first's themes -- a colour that
        // resolves because of somewhere the client is no longer looking is the sort of state that
        // produces a bug report nobody can reproduce.
        Path first = Files.createTempDirectory("themes-a");
        Path second = Files.createTempDirectory("themes-b");
        try {
            write(first, "one.json", "{\"name\": \"one\", \"colours\": {\"panel\": \"#111111\"}}");
            write(second, "two.json", "{\"name\": \"two\", \"colours\": {\"panel\": \"#222222\"}}");

            ThemeFiles.reload(first);
            assertNotNull(ThemeFiles.byName("one"));

            ThemeFiles.reload(second);
            assertNull(ThemeFiles.byName("one"), "an earlier directory's theme is still resolvable");
            assertNotNull(ThemeFiles.byName("two"));
        }
        finally {
            deleteRecursively(first);
            deleteRecursively(second);
        }
    }

    @Test
    @DisplayName("a theme that came from a file says so, so the editor can offer to overwrite")
    void filesAreDistinguishableFromBuiltIns() throws IOException {
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "mine.json", "{\"name\": \"mine\", \"colours\": {\"panel\": \"#111111\"}}");
            ThemeFiles.reload(dir);

            assertTrue(ThemeFiles.isFromFile("mine"));
            assertTrue(ThemeFiles.isFromFile("MINE"), "the lookup is case-insensitive everywhere else");
            assertFalse(ThemeFiles.isFromFile("modern"),
                    "a built-in is not a file, and the editor must not offer to overwrite it");
            assertFalse(ThemeFiles.isFromFile(null));
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("a file-loaded theme and a built-in may not share a name, and the built-in is found first")
    void theBuiltInWinsOnACollision() throws IOException {
        // `Themes.any` consults the built-ins before the files, so a file called `modern` cannot shadow
        // the reference theme. Worth asserting rather than leaving implicit: shadowing would make the
        // theme every screenshot was taken with unselectable, which is the kind of thing noticed only
        // by someone wondering why their palette is wrong.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "modern.json", "{\"name\": \"modern\", \"colours\": {\"panel\": \"#111111\"}}");
            ThemeFiles.reload(dir);

            assertSame(Themes.MODERN, Themes.any("modern"));
            assertNotNull(ThemeFiles.byName("modern"),
                    "the file still loaded; it is just not the one that resolves by that name");
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("reset forgets everything, including the directory")
    void resetClears() throws IOException {
        // A test that read one directory and then went on to another would otherwise resolve names
        // against the first test's themes.
        Path dir = Files.createTempDirectory("themes");
        try {
            write(dir, "mine.json", "{\"name\": \"mine\", \"colours\": {\"panel\": \"#111111\"}}");
            ThemeFiles.reload(dir);
            assertFalse(ThemeFiles.isEmpty());

            ThemeFiles.reset();

            assertTrue(ThemeFiles.isEmpty());
            assertNull(ThemeFiles.byName("mine"));
            assertTrue(ThemeFiles.problems().isEmpty());
        }
        finally {
            deleteRecursively(dir);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void write(Path dir, String name, String json) throws IOException {
        Files.writeString(dir.resolve(name), json, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored) {
                    // A temporary directory that outlives the test is the operating system's problem.
                }
            });
        }
    }
}
