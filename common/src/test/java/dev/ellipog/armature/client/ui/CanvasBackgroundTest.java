package dev.ellipog.armature.client.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pattern names, the tolerance of the reader, the clamps, and the canonical texture id.
 *
 * <h2>Why the failures matter as much as the successes</h2>
 *
 * <p>A canvas background is written by hand in a theme file, so the reader's whole job is to be
 * right about the typos: a misspelled pattern must name what it could have said, an unreadable field
 * must keep its default rather than take the whole pattern down with it, and a missing value must be
 * a default rather than a null drawn over the player's theme. Each of those is one assertion here.
 *
 * <h2>Why canonicalTexture has its own test</h2>
 *
 * <p>Because it is the one function three spellings have to pass through to become one file — the
 * theme file, the editor's text field and the renderer's lookup — and a missed case is not a wrong
 * label, it is a wallpaper that silently does not draw for the one spelling somebody typed.
 */
@DisplayName("CanvasBackground")
class CanvasBackgroundTest {

    private static CanvasBackground read(String json, List<String> problems) {
        return CanvasBackground.fromJson(JsonParser.parseString(json), problems);
    }

    @Test
    @DisplayName("a full object reads back as itself, and round-trips through JSON")
    void readsAndWritesTheSameShape() {
        List<String> problems = new ArrayList<>();
        CanvasBackground parsed = read("""
                {"pattern": "image", "space": "screen", "spacing": 32, "size": 3, "density": 7,
                 "direction": "backslash", "fit": "cover", "texture": "MyMod:GUI/Bg.PNG", "tile": 48}""",
                problems);

        assertEquals(CanvasBackground.Kind.IMAGE, parsed.kind());
        assertEquals(CanvasBackground.Space.SCREEN, parsed.space());
        assertEquals(32, parsed.spacing());
        assertEquals(new CanvasBackground.Tuning(3, 7, true), parsed.tuning());
        assertEquals(new CanvasBackground.Image("mymod:textures/gui/bg.png",
                CanvasBackground.Fit.COVER, 48), parsed.image());
        assertTrue(problems.isEmpty(), "nothing to report: " + problems);

        assertEquals(parsed, CanvasBackground.fromJson(parsed.toJson(), problems),
                "a background written by the editor must read back as the one it wrote");
        assertTrue(problems.isEmpty(), problems.toString());

        JsonObject written = parsed.toJson();
        assertEquals("mymod:textures/gui/bg.png", written.get("texture").getAsString(),
                "the canonical id is what a saved theme carries");
        assertEquals("backslash", written.get("direction").getAsString());
        assertEquals("cover", written.get("fit").getAsString());
        assertEquals(3, written.get("size").getAsInt());
        assertEquals(48, written.get("tile").getAsInt());
    }

    @Test
    @DisplayName("a missing field takes its default; every kind's id is accepted")
    void defaultsAndKindIds() {
        List<String> problems = new ArrayList<>();
        CanvasBackground bare = read("{}", problems);

        assertEquals(CanvasBackground.Kind.NONE, bare.kind());
        assertEquals(CanvasBackground.Space.GRAPH, bare.space());
        assertEquals(CanvasBackground.DEFAULT_SPACING, bare.spacing());
        assertEquals(CanvasBackground.Tuning.DEFAULT, bare.tuning());
        assertEquals(CanvasBackground.Image.NONE, bare.image());
        assertTrue(problems.isEmpty(), problems.toString());

        assertTrue(CanvasBackground.NONE.isNone());
        assertEquals(CanvasBackground.Tuning.DEFAULT, CanvasBackground.NONE.tuning(),
                "the flat canvas is every default at once");
        assertEquals(CanvasBackground.Image.NONE, CanvasBackground.NONE.image());

        for (CanvasBackground.Kind kind : CanvasBackground.Kind.values()) {
            assertEquals(kind, CanvasBackground.Kind.byId(kind.id()), kind.id());
        }
        assertEquals(CanvasBackground.Kind.IMAGE, CanvasBackground.Kind.byId(" image "),
                "the one new name, trimmed and case-insensitive like the rest");
        assertEquals(CanvasBackground.Kind.GRID_LINES,
                CanvasBackground.Kind.byId("  GRID_LINES  "), "trimmed and case-insensitive");
    }

    @Test
    @DisplayName("an unknown pattern leaves the whole background unread, and says what it could be")
    void unknownPatternsAreNamedNotGuessed() {
        List<String> problems = new ArrayList<>();
        assertNull(read("{\"pattern\": \"dots\"}", problems),
                "a typo must not silently become a different pattern");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("dot_grid"), problems.get(0));
        assertTrue(problems.get(0).contains("image"),
                "the list of names this build draws now includes image: " + problems.get(0));

        assertNull(read("{\"pattern\": \"texture\"}", new ArrayList<>()),
                "\"texture\" was the reserved spelling before image was real and is not a kind now");
    }

    @Test
    @DisplayName("image is a real pattern, and its texture is stored canonically")
    void imageParses() {
        List<String> problems = new ArrayList<>();
        CanvasBackground parsed = read(
                "{\"pattern\": \"image\", \"texture\": \"minecraft:gui/bg\"}", problems);

        assertEquals(CanvasBackground.Kind.IMAGE, parsed.kind());
        assertEquals("minecraft:textures/gui/bg.png", parsed.image().texture());
        assertEquals(CanvasBackground.Fit.TILE, parsed.image().fit());
        assertEquals(CanvasBackground.Image.NONE.tileSize(), parsed.image().tileSize());
        assertTrue(problems.isEmpty(), problems.toString());
        assertFalse(parsed.isNone(), "an image is a pattern, whether or not it has been given a file");
    }

    @Test
    @DisplayName("canonicalTexture accepts what a person types and returns the one spelling")
    void canonicalTextureFoldsSpellings() {
        assertEquals("minecraft:textures/gui/bg.png",
                CanvasBackground.canonicalTexture("minecraft:gui/bg"));
        assertEquals("minecraft:textures/gui/bg.png",
                CanvasBackground.canonicalTexture("minecraft:textures/gui/bg.png"),
                "already canonical is a fixed point");
        assertEquals("mymod:textures/gui/bg.png",
                CanvasBackground.canonicalTexture("MyMod:GUI/Bg.PNG"),
                "namespace, directory case, name case and the extension all fold");
        assertEquals("minecraft:textures/gui/bg.png",
                CanvasBackground.canonicalTexture("  textures/gui/bg  "),
                "a leading textures/ is optional, and whitespace is not part of the path");
        assertEquals("minecraft:textures/gui/bg.png",
                CanvasBackground.canonicalTexture("gui/bg.png"), "a missing namespace defaults");
        assertEquals("mymod:textures/a/b/c.png",
                CanvasBackground.canonicalTexture("mymod:textures/a/b/c.png"),
                "only a leading textures/ goes: one deeper in the path is part of the name");

        assertEquals("", CanvasBackground.canonicalTexture(""));
        assertEquals("", CanvasBackground.canonicalTexture("   "));
        assertEquals("", CanvasBackground.canonicalTexture(null));
        assertEquals("", CanvasBackground.canonicalTexture("My Mod:GUI/Bg.PNG"),
                "a space is not a path character the resource location rules allow");
    }

    @Test
    @DisplayName("spacing, size, density and tile are clamped with a message")
    void clampsAreReportedNotWrapped() {
        List<String> tooBig = new ArrayList<>();
        CanvasBackground big = read("{\"pattern\": \"speckle\", \"spacing\": 4000}", tooBig);
        assertEquals(CanvasBackground.MAX_SPACING, big.spacing());
        assertTrue(tooBig.get(0).contains("clamped"), tooBig.get(0));

        List<String> tooSmall = new ArrayList<>();
        assertEquals(CanvasBackground.MIN_SPACING,
                read("{\"pattern\": \"speckle\", \"spacing\": 1}", tooSmall).spacing());
        assertTrue(tooSmall.get(0).contains("clamped"), tooSmall.get(0));

        List<String> clamped = new ArrayList<>();
        CanvasBackground squeezed = read(
                "{\"pattern\": \"hatch\", \"size\": 9, \"density\": 1, \"tile\": 2000}", clamped);
        assertEquals(CanvasBackground.Tuning.MAX_SIZE, squeezed.tuning().size());
        assertEquals(CanvasBackground.Tuning.MIN_DENSITY, squeezed.tuning().density());
        assertEquals(CanvasBackground.Image.MAX_TILE, squeezed.image().tileSize());
        assertEquals(3, clamped.size(), "one message per clamped field: " + clamped);
        assertTrue(clamped.get(0).contains("size"), clamped.get(0));
        assertTrue(clamped.get(1).contains("density"), clamped.get(1));
        assertTrue(clamped.get(2).contains("tile"), clamped.get(2));

        List<String> words = new ArrayList<>();
        CanvasBackground defaulted = read(
                "{\"pattern\": \"hatch\", \"size\": \"thick\", \"density\": \"dense\", \"tile\": \"big\"}",
                words);
        assertEquals(CanvasBackground.Tuning.DEFAULT.size(), defaulted.tuning().size(),
                "a value that is not a number keeps the default rather than guessing");
        assertEquals(CanvasBackground.Tuning.DEFAULT.density(), defaulted.tuning().density());
        assertEquals(CanvasBackground.Image.NONE.tileSize(), defaulted.image().tileSize());
        assertEquals(3, words.size(), words.toString());
    }

    @Test
    @DisplayName("a bad space keeps the pattern and names the two it could have been")
    void badSpaceKeepsThePattern() {
        List<String> problems = new ArrayList<>();
        CanvasBackground parsed = read("{\"pattern\": \"hatch\", \"space\": \"world\"}", problems);

        assertEquals(CanvasBackground.Kind.HATCH, parsed.kind(), "one bad field, not the whole object");
        assertEquals(CanvasBackground.Space.GRAPH, parsed.space());
        assertTrue(problems.get(0).contains("screen"), problems.get(0));

        assertNull(read("\"dot_grid\"", new ArrayList<>()),
                "a bare string is not an object, and null keeps whatever the base theme had");
    }

    @Test
    @DisplayName("a bad direction, fit or texture keeps its default and names the value")
    void badImageFieldsKeepDefaults() {
        List<String> problems = new ArrayList<>();
        CanvasBackground parsed = read(
                "{\"pattern\": \"image\", \"fit\": \"stretch\", \"texture\": \"not a texture!\","
                        + " \"direction\": 7}",
                problems);

        assertEquals(CanvasBackground.Kind.IMAGE, parsed.kind(), "one bad field, not the whole object");
        assertEquals(CanvasBackground.Fit.TILE, parsed.image().fit());
        assertEquals("", parsed.image().texture(), "an unusable id is no id, not a guess at one");
        assertFalse(parsed.tuning().backslash(), "an unreadable direction keeps the slash");
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("direction"), problems.get(0));
        assertTrue(problems.get(1).contains("tile") && problems.get(1).contains("cover"),
                problems.get(1));
        assertTrue(problems.get(2).contains("texture"), problems.get(2));

        List<String> blank = new ArrayList<>();
        CanvasBackground empty = read("{\"pattern\": \"image\", \"texture\": \"\"}", blank);
        assertEquals("", empty.image().texture());
        assertTrue(blank.isEmpty(), "an empty texture is the default said out loud, not an error: " + blank);

        List<String> slash = new ArrayList<>();
        assertEquals(new CanvasBackground.Tuning(1, 5, false),
                read("{\"pattern\": \"hatch\", \"direction\": \"slash\"}", slash).tuning());
        assertTrue(slash.isEmpty(), slash.toString());
    }

    @Test
    @DisplayName("the constructors clamp and fill, so a control cannot build an unreadable one")
    void constructorIsTolerant() {
        CanvasBackground built = new CanvasBackground(null, null, 0);
        assertEquals(CanvasBackground.Kind.NONE, built.kind());
        assertEquals(CanvasBackground.Space.GRAPH, built.space());
        assertEquals(CanvasBackground.MIN_SPACING, built.spacing());
        assertEquals(CanvasBackground.Tuning.DEFAULT, built.tuning());
        assertEquals(CanvasBackground.Image.NONE, built.image());

        CanvasBackground tightened = new CanvasBackground(CanvasBackground.Kind.SPECKLE,
                CanvasBackground.Space.GRAPH, 24, new CanvasBackground.Tuning(0, 99, true),
                new CanvasBackground.Image("MyMod:GUI/Bg.PNG", null, 1));
        assertEquals(CanvasBackground.Tuning.MIN_SIZE, tightened.tuning().size(), "size clamps up");
        assertEquals(CanvasBackground.Tuning.MAX_DENSITY, tightened.tuning().density(), "density clamps down");
        assertTrue(tightened.tuning().backslash());
        assertEquals("mymod:textures/gui/bg.png", tightened.image().texture(),
                "every construction canonicalises, not just the reader");
        assertEquals(CanvasBackground.Fit.TILE, tightened.image().fit(), "a null fit fills");
        assertEquals(CanvasBackground.Image.MIN_TILE, tightened.image().tileSize(), "tile clamps up");

        assertFalse(new CanvasBackground(CanvasBackground.Kind.DOTS, CanvasBackground.Space.GRAPH, 24)
                .isNone());
        assertTrue(new CanvasBackground(CanvasBackground.Kind.IMAGE, CanvasBackground.Space.SCREEN, 24)
                .image().equals(CanvasBackground.Image.NONE),
                "the three-argument form leaves an image background with no file, which draws nothing");
    }
}
