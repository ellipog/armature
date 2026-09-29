package dev.ellipog.armature.client.ui.kit;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

/**
 * How a folder that declares its own children resolves them: one bare segment, resolved inward.
 *
 * <h2>What problem this answers</h2>
 *
 * <p>A tree on disk — a folder holding a manifest that names its children, each child a folder holding
 * a manifest of its own — needs one rule for turning a declared name into a path. The rule this class
 * settles is deliberately the narrowest one that works:
 *
 * <ul>
 *   <li><b>A declared name is a single segment.</b> Not a path. No separator, no {@code .}, no
 *       {@code ..}. Every reference is resolved against <b>the declaring file's own folder</b>, so
 *       there is nowhere else for a name to point and no way to write a reference that escapes the
 *       tree.</li>
 *   <li><b>A name beginning with {@code _} is skipped.</b> Everywhere, at every level, so a tree can
 *       carry a folder of its own schema files beside its content without the walker tripping over
 *       them. This is the rule that makes shipping a {@code _schema} folder next to the content safe,
 *       and getting it wrong is the difference between a mod that starts and a mod that fills a log
 *       with errors about files it shipped itself.</li>
 *   <li><b>A reference that resolves to nothing, or to the wrong kind of thing, is a named problem.</b>
 *       Named with the declared string, the folder it was resolved against, and what was expected —
 *       because "a file is missing" without those three is a message that sends the reader looking
 *       through a directory listing.</li>
 * </ul>
 *
 * <h2>Why the problems are strings rather than a problem type</h2>
 *
 * <p>Because this is a kit primitive and the kit does not decide how a caller reports things. A caller
 * with an error collector turns the message into whatever it collects; a caller with no collector can
 * print it. Returning a rich problem type here would mean the kit owning a vocabulary — severity, a
 * location, a code — that belongs to whoever is loading the data, and every later caller would have to
 * convert it anyway.
 *
 * <p>The messages are still <b>pointed</b>: each one names the string that was declared, so a caller
 * that wraps it does not have to restate the name, and a caller that prints it has the name in front
 * of it. That is the property worth preserving across the boundary, and it is the one a bare
 * {@code false} would lose.
 *
 * <h2>Why it lives beside the outline</h2>
 *
 * <p>Because the two are the same tree seen twice: {@link Outline} is which rows are visible once a
 * hierarchy is known, and this is how the hierarchy is discovered and validated in the first place.
 * Splitting them would put the shape of a tree in one package and the rules for reading one in
 * another, and a caller would have to know both to use either.
 *
 * <p><b>Game-free by design.</b> Paths, strings and two enumerations; no game type appears here, which
 * is what lets the rules above be asserted on with a temporary folder and nothing else.
 */
public final class DeclaredPaths {

    /** A name, or a path segment, beginning with this is ignored everywhere. */
    public static final String IGNORED_PREFIX = "_";

    /** What a caller expects a declared name to resolve to. */
    public enum Kind {

        /** A file, which is what one whole declaration in one file should be. */
        FILE("file"),

        /** A folder, which is what a declaration that itself holds children should be. */
        DIRECTORY("folder");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** How to say it in a message. */
        public String word() {
            return word;
        }
    }

    /**
     * The outcome of resolving one declared name.
     *
     * <p>Both fields are present on a failure, and that is deliberate rather than sloppy: the path is
     * the place the name <i>would</i> have resolved to, which is exactly what a message needs to name
     * so the reader can look at the directory and see what is actually there. {@link #ok} is the only
     * thing a caller should branch on.
     *
     * @param path    where the name resolved to, or null when the name was unusable before resolution
     * @param problem what is wrong, or null when nothing is
     */
    public record Resolved(Path path, String problem) {

        /** A resolution that found what it was looking for. */
        public static Resolved found(Path path) {
            return new Resolved(path, null);
        }

        /** A resolution that did not. */
        public static Resolved failed(Path path, String problem) {
            return new Resolved(path, problem);
        }

        /** Whether the name resolved to a thing of the expected kind. */
        public boolean ok() {
            return problem == null;
        }
    }

    private DeclaredPaths() {
    }

    // ------------------------------------------------------------------
    // The skip rule
    // ------------------------------------------------------------------

    /**
     * Whether a single name is skipped.
     *
     * <p>Blank counts as ignored, and that is not pedantry: an empty name cannot be declared and cannot
     * be listed, so a caller that reaches this with one has already lost the thread, and treating it as
     * content would be the wrong direction to guess in.
     */
    public static boolean isIgnoredName(String name) {
        return name == null || name.isBlank() || name.startsWith(IGNORED_PREFIX);
    }

    /**
     * Whether a path is skipped, by the same prefix rule applied to <b>every</b> segment.
     *
     * <p>Every segment, not just the last, and that is the whole point. A walker that tested only the
     * final name would happily descend into {@code _schema/} and report each file inside it as
     * unlisted content — which reads as "your install is broken" about a folder the thing doing the
     * reporting put there.
     *
     * <p><b>Expects a path relative to the tree's root.</b> Given an absolute path this checks the
     * names of the ancestors too, so a name beginning with an underscore anywhere above the tree — a
     * home directory called {@code _build}, say — would ignore everything below it. That is a real
     * hazard, and it is left in rather than papered over because the alternative is a guess about which
     * prefix is "the root", which this class cannot know. Every caller walks relative paths.
     */
    public static boolean isIgnored(Path path) {
        Objects.requireNonNull(path, "path");
        for (Path segment : path) {
            if (isIgnoredName(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The bare-segment rule
    // ------------------------------------------------------------------

    /**
     * Why a declared name is not usable, or empty when it is.
     *
     * <p>Returned as a message rather than as a boolean because there are four distinct ways to write a
     * bad reference and the fix differs for each: a separator means the author was thinking of a path,
     * a {@code ..} means they were trying to step sideways, an underscore means they declared something
     * the walker skips, and a blank means they wrote the field and did not fill it in. Reporting all
     * four as "invalid name" would leave the author to work out which they had done.
     */
    public static Optional<String> problemWithName(String declared) {
        if (declared == null) {
            return Optional.of("it is missing");
        }
        if (declared.isEmpty()) {
            return Optional.of("it is empty");
        }
        if (declared.isBlank()) {
            return Optional.of("it is entirely whitespace, so it names no file that could exist");
        }
        if (declared.contains("/") || declared.contains("\\")) {
            return Optional.of("it contains a path separator. A declared name is resolved against the"
                    + " folder that declares it, so it can never point anywhere else - write the"
                    + " child's own name and nothing more");
        }
        if (declared.equals(".") || declared.equals("..")) {
            return Optional.of("it is \"" + declared + "\", which steps out of the folder rather than"
                    + " naming something in it. A declared name is always resolved inward");
        }
        if (declared.startsWith(IGNORED_PREFIX)) {
            return Optional.of("it begins with \"" + IGNORED_PREFIX + "\", and every name beginning with"
                    + " that is skipped - so a reference to one could never resolve. Rename it if it is"
                    + " meant to be content");
        }
        try {
            Path asPath = Paths.get(declared);
            if (asPath.isAbsolute()) {
                return Optional.of("it is an absolute path. A declared name is resolved against the"
                        + " folder that declares it, never from the root of the filesystem");
            }
            if (asPath.getNameCount() != 1) {
                // Reached on a platform whose separators the checks above did not cover, or for a name
                // the platform itself splits. Cheaper to catch here than to explain later.
                return Optional.of("it parses as " + asPath.getNameCount()
                        + " path segments rather than one name");
            }
        } catch (InvalidPathException e) {
            return Optional.of("it is not usable as a name on this system: " + e.getReason());
        }
        return Optional.empty();
    }

    /** Whether a declared name is a single bare segment. The boolean form of {@link #problemWithName}. */
    public static boolean isBareSegment(String declared) {
        return problemWithName(declared).isEmpty();
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Resolves a name a manifest declared, against the folder that manifest lives in.
     *
     * <p>The one method a discovery walk needs. It applies the bare-segment rule, resolves <b>inward
     * only</b>, and checks that what it found is the kind of thing the caller was told to expect — so a
     * chapter folder renamed without updating the manifest that lists it is reported as a name that
     * resolves to nothing, and a chapter folder replaced by a file of the same name is reported as the
     * wrong kind of thing rather than silently accepted.
     *
     * <p>A name that is itself ignored is refused rather than resolved, even if a file with that name is
     * sitting there. It would be skipped everywhere else — the walker, the loader, the listing — so a
     * reference to it is a reference to something the rest of the system cannot see, and finding it on
     * disk would make this method disagree with everything downstream.
     *
     * @param folder   the folder the declaring file lives in. Must be a relative path inside the tree,
     *                 because {@link #isIgnored} is applied to what is declared and not to this.
     * @param declared the name exactly as the manifest spelled it
     * @param expected whether a file or a folder is expected. A name that resolves to the other kind is
     *                 a problem, not a near miss.
     */
    public static Resolved resolveSibling(Path folder, String declared, Kind expected) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(expected, "expected");

        Optional<String> unusable = problemWithName(declared);
        if (unusable.isPresent()) {
            return Resolved.failed(null, "\"" + String.valueOf(declared).trim() + "\" is declared here, but "
                    + unusable.get());
        }

        Path candidate = folder.resolve(declared);
        if (!Files.exists(candidate)) {
            return Resolved.failed(candidate, "\"" + declared + "\" is declared here, but there is no "
                    + expected.word() + " at " + candidate
                    + " - a declared name and the thing it names have to agree, or the tree and the"
                    + " manifest describing it have drifted apart");
        }

        boolean directory = Files.isDirectory(candidate);
        if (expected == Kind.DIRECTORY && !directory) {
            return Resolved.failed(candidate, "\"" + declared + "\" is declared here as a "
                    + expected.word() + ", and " + candidate + " is a file"
                    + " - the manifest and the tree disagree about what this is");
        }
        if (expected == Kind.FILE && directory) {
            return Resolved.failed(candidate, "\"" + declared + "\" is declared here as a "
                    + expected.word() + ", and " + candidate + " is a folder"
                    + " - the manifest and the tree disagree about what this is");
        }

        return Resolved.found(candidate);
    }
}
