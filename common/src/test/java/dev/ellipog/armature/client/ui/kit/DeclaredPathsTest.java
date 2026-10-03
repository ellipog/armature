package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules for a folder that declares its own children.
 *
 * <h2>The two that matter most</h2>
 *
 * <p>{@link Skip#anUnderscoreAnywhereInThePathIsIgnored} and
 * {@link Names#aNameWithASeparatorIsRefused}. The first is what makes it safe to ship a folder of
 * schema files beside the content: getting it wrong does not produce a subtle bug, it produces a mod
 * that reports errors about files it wrote itself. The second is the whole reason a declared name is
 * called a name rather than a path -- and it is the rule that makes it impossible to write a reference
 * that escapes the tree, which is why there is no separate containment check anywhere.
 */
class DeclaredPathsTest {

    // ------------------------------------------------------------------
    // The skip rule
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the underscore rule")
    class Skip {

        @Test
        @DisplayName("a name beginning with an underscore is ignored, and a blank one is too")
        void ignoredNames() {
            assertTrue(DeclaredPaths.isIgnoredName("_schema"));
            assertTrue(DeclaredPaths.isIgnoredName("_snippets"));
            assertTrue(DeclaredPaths.isIgnoredName("__double"));

            assertFalse(DeclaredPaths.isIgnoredName("group"));
            assertFalse(DeclaredPaths.isIgnoredName("group_2"), "the underscore is only special at the start");

            // Blank, rather than false, and not pedantry: an empty name cannot be declared and cannot
            // be listed, so a caller that reaches here with one has already lost the thread, and
            // treating it as content is the wrong direction to guess in.
            assertTrue(DeclaredPaths.isIgnoredName(""));
            assertTrue(DeclaredPaths.isIgnoredName("   "));
            assertTrue(DeclaredPaths.isIgnoredName(null));
        }

        @Test
        @DisplayName("an underscore anywhere in a path ignores the whole path")
        void anUnderscoreAnywhereInThePathIsIgnored() {
            // Every segment, not just the last. A walker that tested only the final name would descend
            // into `_schema/` and report each file inside it as unlisted content -- which reads as "your
            // install is broken" about a folder the reporter put there.
            assertTrue(DeclaredPaths.isIgnored(Path.of("_schema", "entry.schema.json")),
                    "the folder is ignored, so everything in it is");
            assertTrue(DeclaredPaths.isIgnored(Path.of("getting_started", "_snippets", "notes.json")),
                    "and an ignored folder nested inside content is ignored too");
            assertTrue(DeclaredPaths.isIgnored(Path.of("_schema")));

            assertFalse(DeclaredPaths.isIgnored(Path.of("getting_started", "group.json")),
                    "an underscore in a file's own name is not, on its own, ignored by this rule --"
                            + " the prefix test is per segment and this segment does not begin with one");
            assertFalse(DeclaredPaths.isIgnored(Path.of("getting_started", "first_steps",
                    "punch_a_tree.json")));
        }

        @Test
        @DisplayName("a single segment is a path of one, and the rule still applies")
        void aSingleSegmentWorks() {
            assertTrue(DeclaredPaths.isIgnored(Path.of("_schema")));
            assertFalse(DeclaredPaths.isIgnored(Path.of("group.json")));
        }
    }

    // ------------------------------------------------------------------
    // The bare-segment rule
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("what counts as a name")
    class Names {

        @Test
        @DisplayName("an ordinary name is accepted")
        void ordinaryNamesAreFine() {
            assertTrue(DeclaredPaths.isBareSegment("first_steps"));
            assertTrue(DeclaredPaths.isBareSegment("punch_a_tree.json"));
            assertTrue(DeclaredPaths.isBareSegment("group.json"));
            assertTrue(DeclaredPaths.isBareSegment("a"));
        }

        @Test
        @DisplayName("a name with a separator is refused, and the message says why a name is not a path")
        void aNameWithASeparatorIsRefused() {
            // The rule that makes a reference unable to escape its own folder, and therefore the reason
            // there is no containment check. Both separators, because a data file is edited on both
            // and a check that only knew the platform's would pass on the machine it was written on.
            assertTrue(DeclaredPaths.problemWithName("chapters/first_steps").isPresent());
            assertTrue(DeclaredPaths.problemWithName("chapters\\first_steps").isPresent());
            assertTrue(DeclaredPaths.problemWithName("../elsewhere").isPresent());
            assertTrue(DeclaredPaths.problemWithName("/absolute").isPresent());
        }

        @Test
        @DisplayName("a name that steps out of the folder is refused, and the refusal names the string")
        void steppingOutwardIsRefused() {
            // "." and ".." are the two spellings of "not a name in this folder", and their fix is
            // different from a separator's -- an author who wrote one was thinking about the folder,
            // not about a subpath -- so they get their own message.
            String dotDot = DeclaredPaths.problemWithName("..").orElseThrow();
            assertTrue(dotDot.contains(".."), dotDot);
            assertTrue(DeclaredPaths.problemWithName(".").isPresent());
        }

        @Test
        @DisplayName("a name beginning with an underscore is refused even though the walker would skip it")
        void anIgnoredNameCannotBeDeclared() {
            // Refused rather than resolved, even when a file with that name is sitting there. It would
            // be skipped everywhere else -- the walk, the load, the listing -- so a reference to it is a
            // reference to something the rest of the system cannot see, and finding it on disk would
            // make this method disagree with everything downstream.
            //
            // The message explains the *rule* and does not echo the name, and that division is
            // deliberate rather than an oversight: problemWithName is the anonymous half, so
            // resolveSibling can prefix it with the declared string once and have the name appear
            // exactly once in the final sentence. Asserting on the name here was this test's own
            // mistake -- it read as obvious and was a claim about a helper that never had the job.
            String problem = DeclaredPaths.problemWithName("_schema").orElseThrow();
            assertTrue(problem.contains("begins with"), problem);
            assertTrue(problem.contains("skipped"), problem);
            assertFalse(problem.contains("_schema"),
                    "the prefix rule's message is anonymous on purpose; resolveSibling is what names"
                            + " the string: " + problem);
        }

        @Test
        @DisplayName("a missing, empty or blank name is refused with its own message")
        void missingAndEmptyNamesAreRefused() {
            // Four distinct ways to write a bad reference and four distinct fixes, which is why these
            // are messages rather than one boolean.
            assertEquals("it is missing", DeclaredPaths.problemWithName(null).orElseThrow());
            assertTrue(DeclaredPaths.problemWithName("").orElseThrow().contains("empty"));
            assertTrue(DeclaredPaths.problemWithName("   ").orElseThrow().contains("whitespace"));
        }

        @Test
        @DisplayName("an acceptable name has no problem, so the boolean form agrees with the message form")
        void theTwoFormsAgree() {
            for (String name : new String[] {"ok", "a.json", "first_steps"}) {
                assertTrue(DeclaredPaths.problemWithName(name).isEmpty(), name);
                assertTrue(DeclaredPaths.isBareSegment(name), name);
            }
            for (String name : new String[] {"a/b", "..", "_x", "", " "}) {
                assertTrue(DeclaredPaths.problemWithName(name).isPresent(), "'" + name + "'");
                assertFalse(DeclaredPaths.isBareSegment(name), "'" + name + "'");
            }
        }
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolving a declared sibling")
    class Resolving {

        @Test
        @DisplayName("a declared folder that is there resolves to it")
        void aFolderThatExistsResolves(@TempDir Path root) throws IOException {
            Files.createDirectory(root.resolve("first_steps"));

            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    root, "first_steps", DeclaredPaths.Kind.DIRECTORY);

            assertTrue(resolved.ok(), resolved.problem());
            assertEquals(root.resolve("first_steps"), resolved.path());
            assertNull(resolved.problem());
        }

        @Test
        @DisplayName("a declared file that is there resolves to it")
        void aFileThatExistsResolves(@TempDir Path root) throws IOException {
            Files.writeString(root.resolve("punch_a_tree.json"), "{}");

            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    root, "punch_a_tree.json", DeclaredPaths.Kind.FILE);

            assertTrue(resolved.ok(), resolved.problem());
            assertEquals(root.resolve("punch_a_tree.json"), resolved.path());
        }

        @Test
        @DisplayName("a name that resolves to nothing is a pointed problem, naming the name and the path")
        void aMissingChildIsPointed(@TempDir Path root) {
            // Pointed means three things: the string that was declared, the place it was resolved
            // against, and what was expected. "A file is missing" without them sends the reader through
            // a directory listing to work out which of the three it means.
            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    root, "first_steps", DeclaredPaths.Kind.DIRECTORY);

            assertFalse(resolved.ok());
            assertEquals(root.resolve("first_steps"), resolved.path(),
                    "the path is kept even on a failure -- it is what the message names");
            assertTrue(resolved.problem().contains("\"first_steps\""), resolved.problem());
            assertTrue(resolved.problem().contains(root.resolve("first_steps").toString()),
                    resolved.problem());
            assertTrue(resolved.problem().contains("folder"), resolved.problem());
        }

        @Test
        @DisplayName("the wrong kind of thing is reported as the wrong kind, not as a near miss")
        void theWrongKindIsItsOwnProblem(@TempDir Path root) throws IOException {
            // A chapter folder replaced by a file of the same name. Accepting it would mean the loader
            // reads a directory that is not there and reports something further down about a file it
            // could not parse, which names the wrong thing entirely.
            Files.writeString(root.resolve("first_steps"), "not a folder");

            DeclaredPaths.Resolved asFolder = DeclaredPaths.resolveSibling(
                    root, "first_steps", DeclaredPaths.Kind.DIRECTORY);

            assertFalse(asFolder.ok());
            assertTrue(asFolder.problem().contains("is a file"), asFolder.problem());
            assertTrue(asFolder.problem().contains("disagree"), asFolder.problem());
        }

        @Test
        @DisplayName("a folder where a file was declared is the same problem the other way round")
        void theWrongKindIsSymmetric(@TempDir Path root) throws IOException {
            Files.createDirectory(root.resolve("punch_a_tree.json"));

            DeclaredPaths.Resolved asFile = DeclaredPaths.resolveSibling(
                    root, "punch_a_tree.json", DeclaredPaths.Kind.FILE);

            assertFalse(asFile.ok());
            assertTrue(asFile.problem().contains("is a folder"), asFile.problem());
        }

        @Test
        @DisplayName("a bad name is refused before anything is looked at, and the message carries the name")
        void anUnusableNameIsRefusedBeforeResolution(@TempDir Path root) {
            // The path is null rather than the folder joined with the name, and that is honest: there
            // is no path this could have resolved to, because ".." resolved would be a place, and the
            // point is that the author wrote a reference that names nothing in this folder.
            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    root, "..", DeclaredPaths.Kind.DIRECTORY);

            assertFalse(resolved.ok());
            assertNull(resolved.path());
            assertTrue(resolved.problem().contains("\"..\""), resolved.problem());
        }

        @Test
        @DisplayName("a reference to an ignored name is refused, even with something of that name present")
        void anIgnoredNameIsRefusedEvenWhenPresent(@TempDir Path root) throws IOException {
            // The interesting case, and the one the rule is for. `_schema` is sitting right there, and
            // resolving to it would work -- so this is not a missing-file check, it is a check that the
            // declaration and the walk agree about what exists.
            Files.createDirectory(root.resolve("_schema"));

            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    root, "_schema", DeclaredPaths.Kind.DIRECTORY);

            assertFalse(resolved.ok(), "the walker skips this name everywhere, so a reference to it"
                    + " would be to something the rest of the system cannot see");
            assertTrue(resolved.problem().contains("_schema"), resolved.problem());
        }

        @Test
        @DisplayName("resolution is inward only, so a name can never reach a sibling of its folder")
        void resolutionCannotEscapeItsFolder(@TempDir Path root) throws IOException {
            // The structural claim of the whole class, asserted end to end: another group folder exists
            // next door, and no declared name can name anything in it. This is why there is no
            // containment check on the result -- the input language has nowhere for an escape to be
            // written in.
            Files.createDirectory(root.resolve("other_group"));
            Files.createDirectory(root.resolve("this_group"));

            Path inside = root.resolve("this_group");
            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    inside, "../other_group", DeclaredPaths.Kind.DIRECTORY);

            assertFalse(resolved.ok());
            assertNull(resolved.path(), "and it did not even build a path to the neighbour");
        }
    }
}
