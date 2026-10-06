package dev.ellipog.armature.api.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings file: what it reads, what it writes, and what it refuses to guess.
 *
 * <h2>Why every failure here is a warning and never fatal</h2>
 *
 * <p>{@link ArmatureConfig#install} runs during mod initialisation, where an exception is a
 * launcher-level failure. A server operator whose config has a typo in it should get the default
 * parties and their world, not a crash — so every test that supplies something broken asserts the
 * same pair of things: the defaults are in force, and the file was not damaged in the process.
 *
 * <h2>What is tested beyond the happy path, and why those are the ones</h2>
 *
 * <p>A wrong <i>type</i> is the case worth pinning: Gson reading {@code "eight"} as a number
 * answers {@code 0} (a party nobody can form) and {@code "yes"} as a boolean answers {@code false}
 * (a plausible-looking reading of a value that is not a boolean at all). Both are the recorded
 * shape of fault in this project — a value that is parsed, validated, and then quietly means
 * something else — so both are refused by type and asserted here.
 *
 * <p>{@link ArmatureConfig} is a holder as well as a reader, so a test that installed one file
 * leaves its settings in force for the next one. {@code @BeforeEach}/{@code @AfterEach} clears
 * that: a test that passes because of another test's state is worse than one that fails.
 */
@DisplayName("The settings file")
class ArmatureConfigTest {

    @BeforeEach
    @AfterEach
    void resetConfig() {
        ArmatureConfig.reset();
    }

    // ------------------------------------------------------------------
    // The file on disk
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing file is written with the defaults, and they are in force")
    void aMissingFileIsWrittenWithTheDefaults() throws IOException {
        Path dir = Files.createTempDirectory("armature-config");

        ArmatureConfig installed = ArmatureConfig.install(dir);

        Path file = dir.resolve(ArmatureConfig.FILE_NAME);
        assertTrue(Files.isRegularFile(file),
                "an operator should have a file with every key in it to edit, not a documentation"
                        + " page to translate into JSON");
        assertEquals(TeamSettings.DEFAULT, installed.teams());
        assertSame(installed, ArmatureConfig.current(),
                "installing is both halves at once: what is returned is what is in force");

        // And the written file is the format the reader reads. A default file this class's own
        // reader would reject would mean the shipped file is not the documented one.
        assertEquals(TeamSettings.DEFAULT,
                ArmatureConfig.read(Files.readString(file, StandardCharsets.UTF_8)).teams());
    }

    @Test
    @DisplayName("a file's values are read back, all three of them")
    void aFilesValuesAreReadBack() throws IOException {
        Path dir = Files.createTempDirectory("armature-config");
        // Every value differs from the default, and the two booleans differ in opposite directions,
        // so a reader that swapped its two arguments cannot pass by coincidence.
        write(dir, """
                {
                  "teams": {
                    "maxMembers": 12,
                    "newPartyMembersCanInvite": false,
                    "newPartyOpenJoin": true
                  }
                }
                """);

        assertEquals(new TeamSettings(12, false, true), ArmatureConfig.install(dir).teams());
    }

    @Test
    @DisplayName("a corrupt file is reported, the defaults are in force, and the file is left as found")
    void aCorruptFileIsNotRewritten() throws IOException {
        Path dir = Files.createTempDirectory("armature-config");
        Path file = dir.resolve(ArmatureConfig.FILE_NAME);
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        assertEquals(TeamSettings.DEFAULT, ArmatureConfig.install(dir).teams());
        assertEquals("{ this is not json", Files.readString(file, StandardCharsets.UTF_8),
                "a file this class did not write is a file it does not overwrite -- a half-finished"
                        + " edit belongs to whoever is making it, and a silent rewrite would delete"
                        + " the typo's evidence along with the typo");
    }

    @Test
    @DisplayName("installing a second directory replaces the first, so one reader cannot leak into another")
    void installReplacesThePreviousSettings() throws IOException {
        Path first = Files.createTempDirectory("armature-config");
        write(first, "{\"teams\":{\"maxMembers\":3}}");
        assertEquals(3, ArmatureConfig.install(first).teams().maxMembers());

        Path second = Files.createTempDirectory("armature-config");
        write(second, "{\"teams\":{\"maxMembers\":5}}");

        assertEquals(5, ArmatureConfig.install(second).teams().maxMembers(),
                "the second file's value is in force, not the first's");
        assertEquals(5, ArmatureConfig.current().teams().maxMembers(),
                "and the holder agrees with what install returned");

        // And the same path, edited between two installs: a reload command re-installs a file it has
        // already read once, so the second read has to be a read rather than a remembered answer.
        write(first, "{\"teams\":{\"maxMembers\":4}}");
        assertEquals(4, ArmatureConfig.install(first).teams().maxMembers(),
                "an edited file at the same path is re-read, which is what a reload command asks for");
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a number outside 2 to 64 is clamped, and the bounds themselves are allowed")
    void valuesAreClampedToTheSupportedRange() {
        assertEquals(new TeamSettings(2, true, false),
                ArmatureConfig.read("{\"teams\":{\"maxMembers\":1}}").teams(),
                "below the floor is brought up to it");
        assertEquals(new TeamSettings(64, true, false),
                ArmatureConfig.read("{\"teams\":{\"maxMembers\":500}}").teams(),
                "and above the ceiling down to it");

        // Both bounds exactly, so the clamp is a clamp and not an off-by-one that quietly refuses
        // the documented extremes -- the sort of thing a test of 1 and 500 alone would not catch.
        assertEquals(2, ArmatureConfig.read("{\"teams\":{\"maxMembers\":2}}").teams().maxMembers());
        assertEquals(64, ArmatureConfig.read("{\"teams\":{\"maxMembers\":64}}").teams().maxMembers());
    }

    @Test
    @DisplayName("a field of the wrong type takes its default rather than the file's value")
    void aWrongTypeTakesTheDefault() {
        assertEquals(TeamSettings.DEFAULT, ArmatureConfig.read("""
                {"teams":{"maxMembers":"eight","newPartyMembersCanInvite":"yes","newPartyOpenJoin":1}}
                """).teams(),
                "a string is not a number and a number is not a boolean, whatever Gson would answer");
    }

    @Test
    @DisplayName("an absent field takes its default, so a one-line file is a valid file")
    void anAbsentFieldTakesItsDefault() {
        TeamSettings read = ArmatureConfig.read("{\"teams\":{\"maxMembers\":4}}").teams();

        assertEquals(4, read.maxMembers(), "the field that was there was read");
        assertEquals(TeamSettings.DEFAULT.newPartyMembersCanInvite(), read.newPartyMembersCanInvite());
        assertEquals(TeamSettings.DEFAULT.newPartyOpenJoin(), read.newPartyOpenJoin());
    }

    @Test
    @DisplayName("text that is not JSON, and a teams that is not an object, leave the defaults in force")
    void unreadableInputLeavesTheDefaultsInForce() {
        assertEquals(TeamSettings.DEFAULT, ArmatureConfig.read("not json at all").teams());
        assertEquals(TeamSettings.DEFAULT, ArmatureConfig.read("").teams());
        assertEquals(TeamSettings.DEFAULT, ArmatureConfig.read("[1,2,3]").teams(),
                "an array is not a settings object");
        assertEquals(TeamSettings.DEFAULT, ArmatureConfig.read("{\"teams\":5}").teams(),
                "a section that is not an object has no fields to read");
    }

    @Test
    @DisplayName("a key this build does not know is ignored rather than guessed at")
    void anUnknownKeyIsIgnored() {
        // The typo is the case that matters: "maxMember" is one letter from "maxMembers", and the
        // failure to avoid is reading it as if it were the real one -- or refusing the whole file.
        assertEquals(TeamSettings.DEFAULT,
                ArmatureConfig.read("{\"teams\":{\"maxMember\":9}}").teams());
        assertEquals(TeamSettings.DEFAULT,
                ArmatureConfig.read("{\"team\":{\"maxMembers\":9}}").teams(),
                "a misspelled section name leaves every field at its default");
    }

    @Test
    @DisplayName("the written form is the format the reader reads, for a value that is not the default")
    void theWrittenFormRoundTrips() {
        ArmatureConfig edited = ArmatureConfig.read(
                "{\"teams\":{\"maxMembers\":12,\"newPartyMembersCanInvite\":false,\"newPartyOpenJoin\":true}}");

        assertEquals(edited.teams(), ArmatureConfig.read(edited.write()).teams(),
                "write and read are each other's inverse, so a file this class writes is never one"
                        + " it would have to report");
    }

    // ------------------------------------------------------------------

    private static void write(Path dir, String json) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ArmatureConfig.FILE_NAME), json, StandardCharsets.UTF_8);
    }
}
