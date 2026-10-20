package dev.ellipog.armature.api.config;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.data.Checks;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonLocation;
import dev.ellipog.armature.api.data.JsonParseException;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.armature.api.data.Problems;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The settings in force, and the file they were read from.
 *
 * <h2>One file, one holder</h2>
 *
 * <p>{@code config/armature/config.json} holds a section per subsystem, and {@code teams} is the
 * first. {@link #current()} is the settings in force for this process: {@code Armature.init()} reads
 * the file once at startup (writing it with the defaults when it is not there, so an operator has
 * something to edit rather than a documentation page to translate into JSON), and every reader asks
 * the holder.
 *
 * <p>A holder rather than a value passed down because the readers are spread out: the stored
 * manager is built lazily per server by {@code TeamProviders}, a future config screen would be a
 * client class, and neither has somebody to hand it a settings object. And it is asked <i>live</i>
 * rather than captured at construction, so a second {@link #install} — a reload command, a screen —
 * takes effect at the next check instead of at the next server.
 *
 * <h2>The path is the caller's, and that is a rule rather than a preference</h2>
 *
 * <p>This class takes the directory {@code Armature.init()} was handed by the platform; it never
 * resolves one for itself. {@code .utils/check_library.py} fails the build over the difference,
 * because a library that chooses where its own configuration lives is a library holding a
 * <i>choice</i> — see the check's own note. The settings here are Armature's own (they are about
 * Armature's stored parties, which every consumer shares anyway), but the rule is mechanical: take
 * the Path, do not go looking for it.
 *
 * <h2>Nothing here is fatal, and nothing it writes is silent</h2>
 *
 * <p>This runs during mod initialisation, where an exception is a launcher-level failure, so a
 * missing, half-written or hand-mangled file costs the server its settings and nothing else —
 * {@link #read} is total and never throws. What it must not do is fail <i>quietly</i>: a value that
 * was read as something other than what the file said is a setting the operator believes is in
 * force and is not. So a wrong type, an impossible number and an unknown key each produce a warning
 * naming the key and the value used instead. The one case that is not reported is an absent field,
 * which takes its default — that is a minimal file, not a mistake.
 *
 * <p>And a file that could not be parsed is <b>left exactly as it was found</b>. Rewriting it would
 * delete the evidence along with the typo, and a half-finished edit is the operator's to finish.
 */
public final class ArmatureConfig {

    /** The file, inside the directory {@link #install} is given. */
    public static final String FILE_NAME = "config.json";

    /** The section holding {@link TeamSettings}. Not spelled in more than one place. */
    private static final String TEAMS = "teams";

    private static final String MAX_MEMBERS = "maxMembers";
    private static final String MEMBERS_CAN_INVITE = "newPartyMembersCanInvite";
    private static final String OPEN_JOIN = "newPartyOpenJoin";

    private static final Set<String> TEAM_KEYS = Set.of(MAX_MEMBERS, MEMBERS_CAN_INVITE, OPEN_JOIN);

    /**
     * What a process that never installed a file uses, and what a failed read falls back to.
     *
     * <p>Immutable and shared, so falling back is an assignment rather than a construction: a
     * default that differed between a fresh process and a recovered one would make "the defaults"
     * mean two things.
     */
    private static final ArmatureConfig DEFAULTS = new ArmatureConfig(TeamSettings.DEFAULT);

    private static volatile ArmatureConfig current = DEFAULTS;

    private final TeamSettings teams;

    private ArmatureConfig(TeamSettings teams) {
        this.teams = teams;
    }

    /**
     * The settings in force. Never null and never throws: before an install, or after one that
     * failed, the documented defaults.
     */
    public static ArmatureConfig current() {
        return current;
    }

    /** The teams section. {@link TeamSettings#DEFAULT} unless a file said otherwise. */
    public TeamSettings teams() {
        return teams;
    }

    /**
     * Reads {@code config.json} from {@code directory} and installs it, writing the defaults first
     * when the file is not there.
     *
     * <p>The write is the only time this class creates the file, and it does it once: after that the
     * file belongs to whoever edits it, and an unreadable one is reported rather than repaired.
     *
     * @return what was installed, so a caller can log or assert on it; also {@link #current()}
     */
    public static ArmatureConfig install(Path directory) {
        Path file = directory.resolve(FILE_NAME);

        if (Files.isRegularFile(file)) {
            try {
                ArmatureConfig loaded = read(Files.readString(file, StandardCharsets.UTF_8));
                current = loaded;
                return loaded;
            }
            catch (IOException | RuntimeException e) {
                // `read` is total, so the RuntimeException here is the second guard -- an
                // OutOfMemoryError's smaller cousins and a Gson that changed its mind. A contract
                // is not a check, and this runs where a crash has no frame to report in.
                Constants.LOG.warn("{} could not be read, so the default settings are in"
                        + " use. Fix or delete the file; it has not been changed.", file, e);
                current = DEFAULTS;
                return DEFAULTS;
            }
        }

        current = DEFAULTS;
        try {
            // Through JsonWrite, which creates the directory itself. The file it replaces is written
            // once, on first run -- and that is exactly the run where a half-written file would leave a
            // player with settings that neither parse nor exist. See JsonWrite.
            JsonWrite.atomically(file, DEFAULTS.write());
            Constants.LOG.info("wrote the default settings to {}", file);
        }
        catch (IOException e) {
            Constants.LOG.warn("the settings could not be written to {}. The defaults are in"
                    + " use for this run, and nothing will persist.", file, e);
        }
        return DEFAULTS;
    }

    /**
     * Back to the defaults.
     *
     * <p>For a test that installed one directory and moves to the next: a setting that leaked from
     * one test into another is a test that passes for the wrong reason, which is worse than one that
     * fails. Production code has no use for this — a process has one settings file.
     */
    public static void reset() {
        current = DEFAULTS;
    }

    // ------------------------------------------------------------------
    // A consumer's own settings file
    // ------------------------------------------------------------------

    /**
     * Reads a consumer's own settings file, with the same tolerance and the same refusal to
     * rewrite a file it did not write.
     *
     * <p>This is the generic half of this class. Armature's own settings are one file with one
     * holder because there is one Armature per process; a mod built on Armature owns its own file
     * and its own holder, so it needs the reader and not the holder. The path is the caller's,
     * which is the rule {@code check_library.py} enforces: a library that chooses where a
     * consumer's configuration lives is a library holding a choice.
     *
     * <p>When the file is absent it is written from {@code defaultJson}, so an operator has every
     * key in front of them rather than a documentation page to translate. When it is present it is
     * read and never rewritten, even when it is broken, because a silent repair would delete the
     * evidence along with the typo.
     *
     * <p>Nothing here is fatal. A missing, unreadable or malformed file returns a document whose
     * {@link Document#usable()} is false and whose {@link Document#problems()} says why; the
     * caller keeps whatever it had in force. That shape is deliberate: a reload that cannot read
     * the file must leave the previous settings alone, and a caller that wanted an exception could
     * not tell "the file is gone" from "the disk failed".
     *
     * @param file        the file to read, usually {@code config/<mod>.json}
     * @param defaultJson the complete text to write when the file is absent
     */
    public static Document document(Path file, String defaultJson) {
        return Document.load(file, defaultJson);
    }

    /**
     * A typed view over a parsed settings object.
     *
     * <p>For a caller that already has a tree, such as a test or a synced copy, and for
     * {@link Document#values()}. It has no file and no defaults: absent keys are absent, which is
     * how the caller knows to keep its previous value.
     */
    public static Values values(JsonObject root, String name) {
        Objects.requireNonNull(root, "root");
        return new Values(name, root, null, new Problems());
    }

    /**
     * A settings file, parsed as far as it could be.
     *
     * <p>{@link #usable()} is false for a missing file that could not be written, a file that is
     * not valid JSON, and a root that is not an object. {@link #values()} is always present so a
     * caller can read defaults from it without a null check, but a caller that applies the file
     * must check {@code usable()} first or it would silently apply an empty document over its
     * previous values.
     */
    public static final class Document {

        private final Path file;
        private final Values values;
        private final Problems problems;
        private final boolean usable;

        private Document(Path file, Values values, Problems problems, boolean usable) {
            this.file = file;
            this.values = values;
            this.problems = problems;
            this.usable = usable;
        }

        static Document load(Path file, String defaultJson) {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(defaultJson, "defaultJson");

            Problems problems = new Problems();
            String name = file.toString();

            if (!Files.isRegularFile(file)) {
                try {
                    JsonWrite.atomically(file, defaultJson);
                    Constants.LOG.info("wrote the default settings to {}", file);
                }
                catch (IOException | RuntimeException e) {
                    problems.add(name, new JsonLocation(1, 1, "$"),
                            DataProblem.Severity.ERROR,
                            "the file is absent and the defaults could not be written: " + e);
                    return empty(file, problems);
                }
            }

            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            }
            catch (IOException | RuntimeException e) {
                problems.add(name, new JsonLocation(1, 1, "$"),
                        DataProblem.Severity.ERROR, "the file could not be read: " + e);
                return empty(file, problems);
            }

            JsonDocument document;
            try {
                document = JsonDocument.parse(name, source);
            }
            catch (JsonParseException e) {
                // Reported at the position the parser found. The file is left exactly as it is:
                // a half-finished edit belongs to whoever is making it.
                problems.add(name, e.location(), DataProblem.Severity.ERROR, e.detail());
                return empty(file, problems);
            }

            if (!document.root().isJsonObject()) {
                problems.add(document, "$", DataProblem.Severity.ERROR,
                        "expected a JSON object at the top level, found "
                                + Checks.kindOf(document.root()));
                return empty(file, problems);
            }

            Values values = new Values(name, document.root().getAsJsonObject(), document, problems);
            return new Document(file, values, problems, true);
        }

        private static Document empty(Path file, Problems problems) {
            Values values = new Values(file.toString(), new JsonObject(), null, problems);
            return new Document(file, values, problems, false);
        }

        /** The file this document came from. */
        public Path file() {
            return file;
        }

        /** Whether the file parsed to a JSON object. */
        public boolean usable() {
            return usable;
        }

        /** The typed view. Never null; see {@link #usable()} before applying it. */
        public Values values() {
            return values;
        }

        /** Everything wrong with the file, with line and column where the parser knew them. */
        public Problems problems() {
            return problems;
        }
    }

    /**
     * Typed reads over one settings object.
     *
     * <p>Each read answers empty for an absent key, and reports a problem for a present key of
     * the wrong type or outside its bounds. Empty is the important half: it is what lets a caller
     * keep its previous value on a bad line rather than overwrite it with a default. The caller
     * owns the defaults; this class owns the shape check and the message.
     */
    public static final class Values {

        private final String name;
        private final JsonObject root;
        private final JsonDocument document;
        private final Problems problems;

        private Values(String name, JsonObject root, JsonDocument document, Problems problems) {
            this.name = name;
            this.root = root;
            this.document = document;
            this.problems = problems;
        }

        /** The file or object name used in messages. */
        public String name() {
            return name;
        }

        public boolean has(String key) {
            return root.has(key);
        }

        /** Every key present, in file order. For a caller that reports unknown keys itself. */
        public Set<String> keys() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(root.keySet()));
        }

        /** The raw value, for a field whose shape belongs to the caller. */
        public Optional<JsonElement> raw(String key) {
            return Optional.ofNullable(root.get(key));
        }

        /** A boolean, or empty when absent or not a boolean. */
        public Optional<Boolean> bool(String key) {
            JsonElement element = root.get(key);
            if (element == null) {
                return Optional.empty();
            }
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
                problem(key, "expected true or false, found " + Checks.kindOf(element));
                return Optional.empty();
            }
            return Optional.of(element.getAsBoolean());
        }

        /**
         * A finite number inside {@code [min, max]}, or empty when absent, not a number, or out
         * of bounds. The bound is reported rather than clamped: a config's edge is a value the
         * operator asked for, and silently reading 200 as 16 would claim a setting they did not
         * choose.
         */
        public Optional<Float> number(String key, float min, float max) {
            JsonElement element = root.get(key);
            if (element == null) {
                return Optional.empty();
            }
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                problem(key, "expected a number, found " + Checks.kindOf(element));
                return Optional.empty();
            }
            float value = element.getAsFloat();
            if (!Float.isFinite(value) || value < min || value > max) {
                problem(key, "must be between " + min + " and " + max + ", found " + element);
                return Optional.empty();
            }
            return Optional.of(value);
        }

        /** A whole number inside {@code [min, max]}, or empty when absent or of the wrong shape. */
        public OptionalInt integer(String key, int min, int max) {
            JsonElement element = root.get(key);
            if (element == null) {
                return OptionalInt.empty();
            }
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                problem(key, "expected a whole number, found " + Checks.kindOf(element));
                return OptionalInt.empty();
            }
            double raw = element.getAsDouble();
            if (!Double.isFinite(raw) || raw != Math.rint(raw)
                    || raw < min || raw > max) {
                problem(key, "must be a whole number between " + min + " and " + max
                        + ", found " + element);
                return OptionalInt.empty();
            }
            return OptionalInt.of((int) raw);
        }

        /** The problems reported by the reads so far. */
        public Problems problems() {
            return problems;
        }

        private void problem(String key, String message) {
            String path = "$." + key;
            if (document != null) {
                problems.add(document, path, DataProblem.Severity.ERROR, message);
            }
            else {
                // No parsed document, so no line to point at. The key is the useful half of the
                // position, and a synthetic first line is still better than dropping the message.
                problems.add(name, new JsonLocation(1, 1, path),
                        DataProblem.Severity.ERROR, message);
            }
        }
    }

    /**
     * Parses settings from JSON text. Total: every failure is a warning and a default, never a throw.
     *
     * <p>Tolerant in both directions and strict about nothing except types, which is the right trade
     * for a file a server operator hand-edits. A missing field takes its default; a field of the
     * wrong type takes its default <b>and says so</b>, because the alternative is not neutral — Gson
     * reading {@code "eight"} as a number answers {@code 0} (a party nobody could form) and reading
     * {@code "yes"} as a boolean answers {@code false} (a plausible-looking reading of a value that
     * is not a boolean at all). Both are the recorded shape of fault: a value that is parsed,
     * validated and then quietly means something else.
     */
    public static ArmatureConfig read(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                Constants.LOG.warn("the settings file should be a JSON object with a 'teams'"
                        + " section in it; the defaults are in use");
                return DEFAULTS;
            }
            root = parsed.getAsJsonObject();
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the settings file is not valid JSON, so the defaults are in"
                    + " use ({})", e.getMessage());
            return DEFAULTS;
        }

        for (String key : root.keySet()) {
            if (!TEAMS.equals(key)) {
                Constants.LOG.warn("\"{}\" in the settings is not a section this build"
                        + " knows, so it was ignored", key);
            }
        }

        JsonElement section = root.get(TEAMS);
        if (section == null) {
            // A file with no teams section: every field takes its default. Which is what a file
            // written before a setting existed looks like, and no more alarming than a minimal one.
            return DEFAULTS;
        }
        if (!section.isJsonObject()) {
            Constants.LOG.warn("\"teams\" in the settings should be an object; the defaults"
                    + " are in use for it");
            return DEFAULTS;
        }
        return new ArmatureConfig(readTeams(section.getAsJsonObject()));
    }

    private static TeamSettings readTeams(JsonObject section) {
        for (String key : section.keySet()) {
            if (!TEAM_KEYS.contains(key)) {
                Constants.LOG.warn("\"{}\" in the teams settings is not a setting this build"
                        + " knows, so it was ignored. The ones it has are: {}", key, TEAM_KEYS);
            }
        }

        return new TeamSettings(
                readMaxMembers(section),
                readBoolean(section, MEMBERS_CAN_INVITE, TeamSettings.DEFAULT.newPartyMembersCanInvite()),
                readBoolean(section, OPEN_JOIN, TeamSettings.DEFAULT.newPartyOpenJoin()));
    }

    private static int readMaxMembers(JsonObject section) {
        JsonElement value = section.get(MAX_MEMBERS);
        if (value == null) {
            return TeamSettings.DEFAULT.maxMembers();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            Constants.LOG.warn("\"{}\" is not a number, so {} is in use",
                    MAX_MEMBERS, TeamSettings.DEFAULT.maxMembers());
            return TeamSettings.DEFAULT.maxMembers();
        }

        double raw = value.getAsDouble();
        int wanted = (int) Math.floor(raw);
        if (wanted != raw) {
            Constants.LOG.warn("\"{}\" is {}, which is not a whole number; using {}",
                    MAX_MEMBERS, raw, wanted);
        }

        int clamped = TeamSettings.clampMaxMembers(wanted);
        if (clamped != wanted) {
            Constants.LOG.warn("\"{}\" is {}, and a party may be configured from {} to {}"
                            + " members; using {}", MAX_MEMBERS, wanted, TeamSettings.MIN_MAX_MEMBERS,
                    TeamSettings.MAX_MAX_MEMBERS, clamped);
        }
        return clamped;
    }

    private static boolean readBoolean(JsonObject section, String key, boolean fallback) {
        JsonElement value = section.get(key);
        if (value == null) {
            return fallback;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        Constants.LOG.warn("\"{}\" is not true or false, so {} is in use", key, fallback);
        return fallback;
    }

    /** The settings as JSON text. Paired with {@link #read}, and the only writer of that format. */
    public String write() {
        JsonObject teamsObject = new JsonObject();
        teamsObject.addProperty(MAX_MEMBERS, teams.maxMembers());
        teamsObject.addProperty(MEMBERS_CAN_INVITE, teams.newPartyMembersCanInvite());
        teamsObject.addProperty(OPEN_JOIN, teams.newPartyOpenJoin());

        JsonObject root = new JsonObject();
        root.add(TEAMS, teamsObject);
        return root.toString();
    }
}
