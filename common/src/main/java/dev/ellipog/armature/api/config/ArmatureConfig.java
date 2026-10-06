package dev.ellipog.armature.api.config;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.data.JsonWrite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                Constants.LOG.warn("armature: {} could not be read, so the default settings are in"
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
            Constants.LOG.info("armature: wrote the default settings to {}", file);
        }
        catch (IOException e) {
            Constants.LOG.warn("armature: the settings could not be written to {}. The defaults are in"
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
                Constants.LOG.warn("armature: the settings file should be a JSON object with a 'teams'"
                        + " section in it; the defaults are in use");
                return DEFAULTS;
            }
            root = parsed.getAsJsonObject();
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("armature: the settings file is not valid JSON, so the defaults are in"
                    + " use ({})", e.getMessage());
            return DEFAULTS;
        }

        for (String key : root.keySet()) {
            if (!TEAMS.equals(key)) {
                Constants.LOG.warn("armature: \"{}\" in the settings is not a section this build"
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
            Constants.LOG.warn("armature: \"teams\" in the settings should be an object; the defaults"
                    + " are in use for it");
            return DEFAULTS;
        }
        return new ArmatureConfig(readTeams(section.getAsJsonObject()));
    }

    private static TeamSettings readTeams(JsonObject section) {
        for (String key : section.keySet()) {
            if (!TEAM_KEYS.contains(key)) {
                Constants.LOG.warn("armature: \"{}\" in the teams settings is not a setting this build"
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
            Constants.LOG.warn("armature: \"{}\" is not a number, so {} is in use",
                    MAX_MEMBERS, TeamSettings.DEFAULT.maxMembers());
            return TeamSettings.DEFAULT.maxMembers();
        }

        double raw = value.getAsDouble();
        int wanted = (int) Math.floor(raw);
        if (wanted != raw) {
            Constants.LOG.warn("armature: \"{}\" is {}, which is not a whole number; using {}",
                    MAX_MEMBERS, raw, wanted);
        }

        int clamped = TeamSettings.clampMaxMembers(wanted);
        if (clamped != wanted) {
            Constants.LOG.warn("armature: \"{}\" is {}, and a party may be configured from {} to {}"
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
        Constants.LOG.warn("armature: \"{}\" is not true or false, so {} is in use", key, fallback);
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
