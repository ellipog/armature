package dev.ellipog.armature.client.ui;

import dev.ellipog.armature.Constants;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Themes read from {@code config/armature/themes/}: the file-based half of the catalogue.
 *
 * <h2>Why files rather than a resource pack, and how this can change later</h2>
 *
 * <p>A config directory is where a <b>player</b> and a <b>modpack author</b> both look. A resource pack
 * is where a <b>resource-pack author</b> looks. For the first version of this, config is the one that
 * matters more: a pack that wants its own look can ship the file inside its own configs and nothing has
 * to be reloaded, whereas a resource pack needs assets namespaced, an atlas, and F3+T to see a change.
 *
 * <p>What that costs is stated rather than discovered: a theme in a resource pack cannot be seen without
 * a restart <i>and</i> cannot be overridden per-pack without a resource-pack precedence puzzle. If a pack
 * source is added later, it should feed this same registry — {@link #register} is the seam for that, and
 * the merge order between the two sources should be decided then rather than guessed now.
 *
 * <h2>One file per theme, and a file is a diff</h2>
 *
 * <pre>{@code
 * {
 *   "name": "my_pack",
 *   "basedOn": "modern",
 *   "colours": { "panel": "#2A2233", "canvas": "#1A1622", "available": "#C79BF0" },
 *   "cornerRadius": 6,
 *   "motion": 120,
 *   "easing": "QUAD_OUT"
 * }
 * }</pre>
 *
 * <p>{@code basedOn} is what makes a hand-written theme practical: a file says only what it changes, and
 * the rest comes from a built-in. It may name another file-loaded theme too, since a pack author may
 * reasonably want a variant of their own.
 *
 * <h2>Nothing here is fatal, and nothing here is silent</h2>
 *
 * <p>Every failure — an unreadable file, malformed JSON, an unknown {@code basedOn}, a misspelled colour
 * — is a warning naming the file and the field, and the client carries on with what it could read. There
 * are two reasons for that combination rather than either extreme:
 *
 * <ul>
 *   <li><b>Not fatal</b>, because a theme is the least important thing on the screen. A player whose
 *       appearance file has a typo should get the default appearance and their quests, not a crash on
 *       startup — and this runs during client initialisation, where an exception is a launcher-level
 *       failure.</li>
 *   <li><b>Not silent</b>, because the failure mode of a partially-read theme is a palette that is mostly
 *       right, which looks like a decision rather than a mistake. That is the specific thing this project
 *       has recorded twice: a value that is parsed, validated and printed by something while nothing
 *       reads it.</li>
 * </ul>
 *
 * <h2>Reloading</h2>
 *
 * <p>{@link #reload} re-reads the directory. It is called on client start and by the editor after it
 * saves, so a theme saved in-game is selectable in the same session without a restart — which is the
 * difference between an editor being usable and being a way to generate a file you still have to
 * restart to see.
 */
public final class ThemeFiles {

    private ThemeFiles() {
    }

    /** Under {@code config/armature/}. */
    private static final String DIRECTORY = "themes";

    /**
     * Files in here that are not themes, and are therefore not even reported as broken.
     *
     * <h2>Why an ignore list rather than "read everything and complain"</h2>
     *
     * <p>This directory is one the mod owns, so in principle it holds only themes. In practice a person
     * puts files near other files — a backup, a snippet, a README renamed to .json — and the difference
     * between a reader and a nuisance is whether it has an opinion about them. A file called
     * {@code appearance.json} beside the themes is the specific case: it is this mod's own file, it is a
     * valid JSON object, and it would otherwise be parsed as a theme, fail on {@code "motion": true}, and
     * log a warning about the mod's own config on every startup.
     *
     * <p>Subdirectories are the same reasoning with less excuse: a {@code backup/} folder holding last
     * week's themes is not a set of themes, and reading it would register two of everything.
     */
    private static final Set<String> NOT_THEMES = Set.of("appearance.json", "readme.json");

    /** Every theme loaded from a file, in file-name order. */
    private static Map<String, Theme> loaded = Map.of();

    /** Every problem from the last read, for a message. Empty is the normal case. */
    private static List<String> problems = List.of();

    /**
     * The directory the last read used, or null before one.
     *
     * <p>Kept so {@code reset} and a message can name it. It is <b>the caller's path</b>, never one this
     * class resolved -- see {@code .utils/check_library.py}, which fails the build if that ever changes.
     */
    private static Path directory;

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Reads one directory of theme files.
     *
     * <p>Two passes, and the second is why: a file may name another file-loaded theme as its
     * {@code basedOn}, and the order the filesystem returns names in is not the order the author meant.
     * So the first pass parses everything, and the second resolves bases with the parsed set in hand,
     * retrying until nothing more can be resolved. A file left over at the end has a genuine cycle or a
     * missing base, and saying which is the whole reason for the loop rather than a topological sort
     * with a nicer shape.
     */
    public static void reload(Path path) {
        directory = path;
        List<String> found = new ArrayList<>();
        Map<String, Theme> themes = new LinkedHashMap<>();

        List<Pending> pending = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.json")) {
                for (Path file : stream) {
                    // Only files, not directories: `newDirectoryStream` with a glob will hand back a
                    // directory whose name happens to end in .json, and `read` would then fail on it with
                    // a message about JSON. See NOT_THEMES for the other reason a file is skipped.
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    if (NOT_THEMES.contains(file.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    Pending read = read(file, found);
                    if (read != null) {
                        pending.add(read);
                    }
                }
            }
            catch (IOException e) {
                found.add("could not list " + path + ": " + e.getMessage());
            }
        }

        // Resolve as many as possible, one pass per layer of dependency. The loop terminates because
        // every pass that resolves anything removes it from `pending`, and a pass that resolves nothing
        // is the end.
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            for (int i = pending.size() - 1; i >= 0; i--) {
                Pending candidate = pending.get(i);
                Theme base = resolve(candidate.basedOn, themes);
                if (base == null) {
                    continue;
                }
                String name = candidate.patch.name() != null ? candidate.patch.name() : candidate.stem;
                if (themes.containsKey(name.toLowerCase(Locale.ROOT))) {
                    found.add(candidate.file + ": a theme called '" + name + "' is already loaded. The"
                            + " other one wins; give this file a distinct name");
                    pending.remove(i);
                    progress = true;
                    continue;
                }
                Theme theme = candidate.patch.applyTo(base).withName(name);
                themes.put(name.toLowerCase(Locale.ROOT), theme);
                found.addAll(unrecognised(candidate));
                pending.remove(i);
                progress = true;
            }
        }

        for (Pending unresolved : pending) {
            // Reached when a `basedOn` names something that is not loaded and never will be -- either a
            // typo, or two themes that name each other. Both are the author's to fix, and the message
            // has to say which, because "no theme called x" against a file that clearly defines x is a
            // message that sends someone looking in the wrong place.
            found.add(unresolved.file + ": 'basedOn' is '" + unresolved.basedOn + "', which is neither a"
                    + " built-in nor a theme that loaded. If it is another file here, the two may name"
                    + " each other. The built-ins are: " + Themes.names());
        }

        loaded = Map.copyOf(themes);
        problems = List.copyOf(found);

        for (String problem : found) {
            Constants.LOG.warn("armature theme: {}", problem);
        }
    }

    /** The tokens a patch named that are not colours, for the problems list. */
    private static List<String> unrecognised(Pending candidate) {
        if (candidate.patch.unknownTokens().isEmpty()) {
            // `fromJson` already reported and dropped these; this exists for a patch built in code, and
            // is empty for every file that goes through the reader. Kept because the check is one line
            // and the alternative is trusting that the only caller is the one that already checks.
            return List.of();
        }
        return List.of(candidate.file + ": " + candidate.patch.unknownTokens()
                + " are not colours a theme can set, so they were ignored");
    }

    /**
     * The base a file asked for, or null when it is not available <i>yet</i>.
     *
     * <p>Distinguishing "not yet" from "never" is the caller's problem, not this method's — it answers a
     * plain question and the resolution loop treats null as "try again later". That is what lets a file
     * be based on another file without the two being read in a particular order.
     */
    private static Theme resolve(String basedOn, Map<String, Theme> soFar) {
        if (basedOn == null || basedOn.isEmpty()) {
            return Themes.DEFAULT;
        }
        Theme fromFile = soFar.get(basedOn.toLowerCase(Locale.ROOT));
        return fromFile != null ? fromFile : Themes.byName(basedOn);
    }

    /** One parsed file, waiting for its base. */
    private record Pending(Path file, String stem, String basedOn, ThemePatch patch) {
    }

    /**
     * Parses one file, appending anything wrong to {@code problems}.
     *
     * @return the parsed file, or null when nothing about it could be read at all — which is distinct
     *     from a file that parsed with warnings, and produces a theme that is missing a few colours
     */
    private static Pending read(Path file, List<String> problems) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            problems.add(file + ": could not be read: " + e.getMessage());
            return null;
        }

        JsonObject root;
        try {
            var parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                problems.add(file + ": should be a JSON object with 'colours' in it");
                return null;
            }
            root = parsed.getAsJsonObject();
        }
        catch (RuntimeException e) {
            problems.add(file + ": is not valid JSON: " + e.getMessage());
            return null;
        }

        List<String> local = new ArrayList<>();
        ThemePatch patch;
        try {
            patch = ThemePatch.fromJson(root, local);
        }
        catch (RuntimeException e) {
            // The second guard, and it is not redundant with the parse above. `ThemePatch.fromJson`'s
            // contract is that it collects problems rather than throwing, but a contract is not a
            // check -- and this method's caller runs during client startup, where an exception is a
            // launcher-level failure with no frame to report it in. So anything at all escaping the
            // reader is caught here, named with its file, and costs the player one theme rather than
            // the game.
            problems.add(file + ": could not be read as a theme: " + e);
            return null;
        }
        for (String problem : local) {
            problems.add(file + ": " + problem);
        }

        if (patch.colours().isEmpty() && patch.cornerRadius() == null && patch.motion() == null
                && patch.easing() == null) {
            // A file that changes nothing is almost always a mistake rather than a statement, and
            // registering it would put a name in the picker that looks exactly like the theme it is
            // based on -- the same "a control that does nothing looks broken" argument as everywhere
            // else. Said out loud rather than registered quietly.
            problems.add(file + ": changes nothing, so it was not loaded. A theme file needs at least a"
                    + " 'colours' entry, or 'cornerRadius', 'motion' or 'easing'");
            return null;
        }

        String stem = file.getFileName().toString().replaceFirst("\\.json$", "");
        return new Pending(file, stem, ThemePatch.basedOn(root), patch);
    }

    // ------------------------------------------------------------------
    // Reading back
    // ------------------------------------------------------------------

    /** Every theme loaded from a file. What a picker appends to the built-ins. */
    public static List<Theme> all() {
        return List.copyOf(loaded.values());
    }

    /** A file-loaded theme by name, or null. Case-insensitive, and trims. */
    public static Theme byName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return loaded.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether a file in this directory defines {@code name}. For the editor to offer "overwrite". */
    public static boolean isFromFile(String name) {
        return name != null && loaded.containsKey(name.trim().toLowerCase(Locale.ROOT));
    }

    /** The directory being read, or null. The editor shows it, since a saved theme's home matters. */
    /** Everything wrong with the last read, in the order it was found. Empty is the normal case. */
    public static List<String> problems() {
        return problems;
    }

    /** Whether any file was read at all. A picker can say "no themes in config/armature/themes". */
    public static boolean isEmpty() {
        return loaded.isEmpty();
    }

    /**
     * Forgets everything read, and the directory with it.
     *
     * <p>For a test that read a temporary directory and then went on to another one — without this, the
     * second test would resolve names against the first test's themes, and a test that passes because of
     * another test's state is worse than one that fails.
     */
    public static void reset() {
        loaded = Map.of();
        problems = List.of();
        directory = null;
    }
}
