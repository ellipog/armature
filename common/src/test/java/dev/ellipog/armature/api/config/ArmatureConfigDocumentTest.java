package dev.ellipog.armature.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A consumer's settings file: the reader a mod uses for its own JSON, without Armature's holder
 * or Armature's team schema.
 *
 * <p>The cases that matter are the ones a hand-edited file produces: absent, malformed, a field of
 * the wrong type, a number outside its bounds. The first must write defaults once and read them;
 * the rest must answer empty so the caller keeps its previous value, and must leave the file
 * exactly as found.
 */
@DisplayName("A consumer's settings file")
class ArmatureConfigDocumentTest {

    @Test
    @DisplayName("an absent file is written with the caller's defaults and read back")
    void anAbsentFileIsWrittenAndRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("stature.json");
        String defaults = "{\"micro_threshold\":0.25,\"porous_traversal\":true}";

        ArmatureConfig.Document document = ArmatureConfig.document(file, defaults);

        assertTrue(document.usable(), "a file this class just wrote must be readable");
        assertTrue(Files.isRegularFile(file), "an operator needs a file to edit");
        assertEquals(defaults, Files.readString(file, StandardCharsets.UTF_8),
                "the default text is written once, byte for byte");
        assertEquals(0.25F, document.values().number("micro_threshold", 0F, 16F).orElseThrow());
        assertEquals(Boolean.TRUE, document.values().bool("porous_traversal").orElseThrow());
        assertTrue(document.problems().isEmpty());
    }

    @Test
    @DisplayName("a malformed file is reported and left exactly as found")
    void aMalformedFileIsNotRewritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("stature.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        ArmatureConfig.Document document = ArmatureConfig.document(file, "{\"ok\":true}");

        assertFalse(document.usable(), "nothing parsed, so nothing is safe to apply");
        assertEquals("{ this is not json", Files.readString(file, StandardCharsets.UTF_8),
                "a typo's evidence must survive the report");
        assertTrue(document.problems().hasErrors(),
                "the caller has to know why its previous values are still in force");
    }

    @Test
    @DisplayName("a root that is not an object is refused, not read as an empty object")
    void aNonObjectRootIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("stature.json");
        Files.writeString(file, "[1,2,3]", StandardCharsets.UTF_8);

        ArmatureConfig.Document document = ArmatureConfig.document(file, "{}");

        assertFalse(document.usable());
        assertTrue(document.problems().hasErrors());
    }

    @Test
    @DisplayName("a wrong type and an out-of-bounds number both answer empty and report why")
    void badValuesAnswerEmpty() {
        ArmatureConfig.Values values = ArmatureConfig.values(JsonParser.parseString("""
                {"enabled":"yes","strength":200.0,"count":2.5,"whole":3}
                """).getAsJsonObject(), "test");

        assertFalse(values.bool("enabled").isPresent(), "a string is not true or false");
        assertFalse(values.number("strength", 0F, 4F).isPresent(), "200 is outside 0 to 4");
        assertFalse(values.integer("count", 1, 8).isPresent(), "2.5 is not whole");
        assertEquals(3, values.integer("whole", 1, 8).orElseThrow());
        assertEquals(3, values.problems().errorCount(),
                "one problem per bad read, and none for the good one");
    }

    @Test
    @DisplayName("an absent key is absent rather than a default")
    void anAbsentKeyIsEmpty() {
        ArmatureConfig.Values values = ArmatureConfig.values(
                JsonParser.parseString("{}").getAsJsonObject(), "test");

        assertFalse(values.has("anything"));
        assertFalse(values.bool("anything").isPresent());
        assertFalse(values.number("anything", 0F, 1F).isPresent());
        assertFalse(values.integer("anything", 0, 1).isPresent());
        assertTrue(values.problems().isEmpty(), "absence is not a mistake");
    }

    @Test
    @DisplayName("the raw keys are available so a caller can report what it does not know")
    void keysAreAvailable() {
        ArmatureConfig.Values values = ArmatureConfig.values(
                JsonParser.parseString("{\"known\":1,\"typo\":2}").getAsJsonObject(), "test");

        assertNotEquals(0, values.keys().size());
        assertTrue(values.keys().contains("typo"));
    }
}
