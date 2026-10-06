package dev.ellipog.armature.api.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonWrite}, which exists so that a save cannot leave a half-written file.
 *
 * <h2>What these tests can and cannot prove</h2>
 *
 * <p>They can prove the contract's observable half: the bytes land exactly, the charset is UTF-8, the
 * target ends up holding either its old content or the new one, and no temporary file survives — on
 * success or on any failure this class handles.
 *
 * <p>They <b>cannot</b> prove the property the class is named for, because a mid-write crash is not
 * something a unit test can stage. That property comes from the <i>ordering</i> — the whole text is
 * written to a temporary file and only then renamed over the target — and the tests below pin the
 * ordering indirectly, by asserting that no partial artifact is ever left anywhere the caller can see
 * it. Saying so here rather than letting the class name imply more than the suite checks.
 *
 * <p>No non-ASCII appears in a string literal: a codepoint the bitmap font lacks fails
 * {@code check_glyphs.py}, and the one UTF-8 test builds its character arithmetically instead. That is
 * a constraint of this repository's own guards rather than of the test.
 */
@DisplayName("JsonWrite")
class JsonWriteTest {

    @TempDir
    Path temp;

    /** The temporary name the class documents, mirrored so a failure test can occupy it. */
    private static Path temporaryFor(Path file) {
        return file.getParent().resolve("_" + file.getFileName() + ".tmp");
    }

    private static List<Path> entriesIn(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.toList();
        }
    }

    @Test
    @DisplayName("the exact text lands, with nothing added")
    void theExactTextLands() throws IOException {
        Path file = temp.resolve("one.json");

        JsonWrite.atomically(file, "{\"a\": 1}");

        assertEquals("{\"a\": 1}", Files.readString(file, StandardCharsets.UTF_8),
                "the text is written as given");
        // Byte-for-byte, so a BOM or a newline the class decided to add would fail here. A caller that
        // wants a trailing newline is a caller that put one in the string.
        assertArrayEquals("{\"a\": 1}".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file),
                "no BOM, no trailing newline, no rewriting");
    }

    @Test
    @DisplayName("the charset is UTF-8, which a byte count can prove")
    void theCharsetIsUtf8() throws IOException {
        // Built arithmetically rather than written as a literal, so this file stays ASCII for
        // check_glyphs.py. U+00E9 is two bytes in UTF-8 and one in Latin-1, so the length is the proof:
        // a writer that had picked the platform charset would produce four bytes, not five.
        String text = "caf" + (char) 0x00E9;
        Path file = temp.resolve("one.json");

        JsonWrite.atomically(file, text);

        assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(5, Files.readAllBytes(file).length,
                "c, a, f and a two-byte e-acute: five bytes, so the writer used UTF-8");
    }

    @Test
    @DisplayName("an existing file is replaced, not appended to")
    void anExistingFileIsReplaced() throws IOException {
        Path file = temp.resolve("one.json");
        Files.writeString(file, "{\"old\": true}", StandardCharsets.UTF_8);

        JsonWrite.atomically(file, "{\"new\": true}");

        assertEquals("{\"new\": true}", Files.readString(file, StandardCharsets.UTF_8),
                "the old content is gone rather than sitting in front of the new");
    }

    @Test
    @DisplayName("a missing parent directory is created")
    void aMissingParentIsCreated() throws IOException {
        Path file = temp.resolve("content").resolve("nested").resolve("one.json");

        JsonWrite.atomically(file, "{}");

        assertTrue(Files.isRegularFile(file), "the file and its folders exist");
        assertEquals("{}", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a successful write leaves no temporary file behind")
    void aSuccessfulWriteLeavesNoTemporaryFile() throws IOException {
        Path file = temp.resolve("one.json");

        JsonWrite.atomically(file, "{}");

        assertEquals(List.of(file), entriesIn(temp),
                "the directory holds the target and nothing else - a stray _one.json.tmp would be a "
                        + "file the author never asked for, and one that a crash later would keep");
    }

    @Test
    @DisplayName("a write that cannot happen leaves the original alone and cleans up")
    void aFailedWriteLeavesTheOriginalAlone() throws IOException {
        Path file = temp.resolve("one.json");
        Files.writeString(file, "{\"original\": true}", StandardCharsets.UTF_8);

        // The temporary path is occupied by a directory, so writing the temporary file fails. This is
        // the closest a test can get to "the write itself went wrong" without playing with permissions,
        // which behave differently on Windows and are unreliable in a test suite.
        Files.createDirectory(temporaryFor(file));

        assertThrows(IOException.class, () -> JsonWrite.atomically(file, "{\"new\": true}"),
                "the failure must reach the caller rather than being swallowed");

        assertEquals("{\"original\": true}", Files.readString(file, StandardCharsets.UTF_8),
                "the original is exactly what it was: this is the whole point of the class");
        assertFalse(Files.exists(temporaryFor(file)),
                "and the temporary is cleaned up, so a retry starts from a clean directory");
    }

    @Test
    @DisplayName("a rename that cannot happen cleans up the temporary file")
    void aFailedMoveCleansUp() throws IOException {
        // The write succeeds and the rename does not, because the target is a directory with something
        // in it. That is the other half of the failure surface: the temporary file exists at the moment
        // the rename fails, so this is the path where a missing cleanup would leave litter.
        Path file = temp.resolve("one.json");
        Files.createDirectory(file);
        Files.writeString(file.resolve("occupied.txt"), "in the way", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> JsonWrite.atomically(file, "{\"new\": true}"),
                "a target that cannot be replaced is a failure the caller has to hear about");

        assertFalse(Files.exists(temporaryFor(file)),
                "the temporary file must not survive a failed rename");
        assertTrue(Files.isDirectory(file), "and the thing in the way is untouched");
    }
}
