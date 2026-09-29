package dev.ellipog.armature.client;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.ThemeFiles;
import dev.ellipog.armature.client.ui.ThemePatch;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Motion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How this client's UI looks and moves: the main theme, and the player's own edits to it.
 *
 * <h2>Two kinds of theme, and neither is a command any more</h2>
 *
 * <p>There are two quite different questions a theme can answer, and this class answers only the first:
 *
 * <ul>
 *   <li><b>The main theme</b> — this. How the <i>program</i> looks: the panel, the sidebar, the header,
 *       the title, every control, every tooltip. It is a setting, it persists, and it is what the chrome
 *       is drawn in. {@link #main()} is the answer.</li>
 *   <li><b>A content theme</b> — a chapter or a quest saying how <i>it</i> looks. That does not persist
 *       and is not a setting at all; it is a region drawn in someone else's colours, and it lives in
 *       {@code ArmatureTheme.scope}. This class does not know it exists.</li>
 * </ul>
 *
 * <p>Separating them by <b>region</b> rather than by <b>priority</b> is the point, and it replaced a
 * design that got this wrong. Previously a chapter's theme and the player's theme were one field with a
 * precedence rule, so a click could be overruled by the chapter you were standing in; the code needed a
 * flag to remember that the player had meant it, and a second flag to forget that on the next visit.
 * With the two separated by where they are drawn, nothing competes: the sidebar is chrome and reads the
 * main theme, the canvas is content and reads the chapter's, and both are visible in the same frame
 * with no rule between them. Those two flags are gone, and their absence is the improvement.
 *
 * <h2>Who may choose the main theme: the player, and also the pack</h2>
 *
 * <p>A modpack that has a look should get it without every player having to configure anything — that is
 * most of the point of a themed pack. So a server may send a default, and it applies to a player who has
 * never chosen. <b>The moment the player chooses, their choice wins and keeps winning</b>, including
 * across a reconnection and including on that server: {@link #chosen} is the state that records it,
 * because "the player picked modern" and "the player has not picked and this pack says modern" are
 * different facts that happen to produce the same appearance.
 *
 * <p>What is deliberately absent is a way for a server to <i>lock</i> the appearance. A pack can
 * propose; the escape hatch has to exist, because the alternative is a server that can make a client
 * unusable for someone with a visual impairment and no recourse. If a pack genuinely wants its identity
 * enforced, that is a conversation about a different mechanism, not a flag on this one.
 *
 * <h2>Three sources of the main theme, in order</h2>
 *
 * <ol>
 *   <li>{@link #serverDefault()} — what the pack this client is connected to asked for, if anything.</li>
 *   <li>The player's own stored choice, which beats it.</li>
 *   <li>{@link Themes#DEFAULT}, when neither exists.</li>
 * </ol>
 *
 * <p>And a fourth thing on top of whichever wins: {@link #custom()}, the player's own colour edits from
 * the editor. It is a {@link ThemePatch} rather than a theme, so it applies over any base — which is
 * what makes "I changed the panel colour" survive switching from one theme to another instead of being
 * discarded, and what lets a player tweak a pack's theme without having to reproduce it.
 *
 * <h2>Why the file is written on every change</h2>
 *
 * <p>Because the alternative is a setting that survives a clean shutdown and is lost to a crash, and the
 * amount of state is four fields. There is no batching worth the complexity here.
 */
public final class Appearance {

    /** Under {@code config/armature/}. Named for what it holds rather than for the mod. */
    private static final String FILE_NAME = "appearance.json";

    private Appearance() {
    }

    /**
     * What is kept on disk.
     *
     * <p>A record rather than four fields, so that reading, writing and comparing it are one thing. The
     * server default is deliberately <b>not</b> in here: it arrives over the wire and describes a
     * connection, so writing it to disk would make one server's look outlive the visit to it.
     */
    public record Settings(String theme, boolean motion, boolean chosen, Map<String, Integer> custom) {

        /** The defaults, which are also what a missing or unreadable file produces. */
        public static final Settings DEFAULT =
                new Settings(Themes.DEFAULT.name(), true, false, Map.of());

        public Settings {
            if (theme == null || theme.isEmpty()) {
                theme = Themes.DEFAULT.name();
            }
            custom = Map.copyOf(custom);
        }

        /** The player's own colour edits as a patch. */
        public ThemePatch patch() {
            return custom.isEmpty() ? ThemePatch.NONE : ThemePatch.colours(custom);
        }
    }

    private static Settings settings = Settings.DEFAULT;

    /** What this connection's pack asked for, or null. Never persisted; see {@link Settings}. */
    private static String serverDefault;

    private static Path file;

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The player's stored settings, as they are on disk. */
    public static Settings settings() {
        return settings;
    }

    /** The name the pack asked for, or null. For a control that wants to say where a theme came from. */
    public static String serverDefault() {
        return serverDefault;
    }

    /** Whether the player has chosen for themselves, which is what beats the pack's default. */
    public static boolean chosen() {
        return settings.chosen();
    }

    /** The player's own colour edits, if any. */
    public static Map<String, Integer> custom() {
        return settings.custom();
    }

    /**
     * The main theme in force: the player's choice, or the pack's, or the default — plus their edits.
     *
     * <h2>The resolution order, in one place</h2>
     *
     * <p>Written as a sequence rather than as nested ternaries because the order <i>is</i> the design, and
     * because each step has a different reason for existing:
     *
     * <ol>
     *   <li>The player chose, so that wins. Nothing else is consulted — not even to check that their
     *       theme still exists in this build, which the fallback below covers.</li>
     *   <li>No choice, but a pack sent one. Applied to a player who has never expressed a preference,
     *       which is the whole reason a pack is allowed to send one.</li>
     *   <li>Neither, so the built-in default.</li>
     * </ol>
     *
     * <p><b>A name that resolves to nothing falls through to the next step rather than throwing</b>, and
     * that is the one place this differs from a control's behaviour. A control has a person to tell and
     * must not report success for a typo; a theme resolved while drawing a frame has nobody to tell and
     * must produce <i>something</i>. So an unresolvable stored name is reported once by {@link #read} and
     * then quietly ignored here, and the frame is drawn.
     *
     * <p>Finally the player's edits are applied over whichever base won. Note the order: the edits are
     * <b>last</b>, so they override the pack's theme as well as the built-in one. A player who has gone
     * to the trouble of editing a colour means it, and a pack that replaced the base must not undo it.
     */
    public static Theme main() {
        Theme base = null;

        if (settings.chosen()) {
            base = Themes.any(settings.theme());
        }
        if (base == null && serverDefault != null && !serverDefault.isEmpty()) {
            base = Themes.any(serverDefault);
        }
        if (base == null) {
            base = Themes.any(settings.theme());
        }
        if (base == null) {
            base = Themes.DEFAULT;
        }

        return settings.patch().applyTo(base);
    }

    /**
     * Whether transitions animate.
     *
     * <p>The player's flag, and it is not the theme's. A theme sets the <i>default</i> duration and curve;
     * this is the accessibility switch that wins over any theme, because a reskin must not be able to
     * re-enable animation for someone who cannot comfortably use it. See {@code Motion}, which is where
     * that precedence is enforced — in the one place that reads both.
     */
    public static boolean motion() {
        return settings.motion();
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Pushes the main theme into the toolkit, and saves the settings.
     *
     * <p>The single place {@code ArmatureTheme} is told what to draw, and the reason it is a method rather
     * than three call sites: {@code setCurrent} also sets {@link Motion}'s default duration and curve, so
     * a caller that did one and not the other would have a theme with the previous theme's motion. That
     * is not hypothetical — it is what {@code vanilla_plus}'s zero would look like if it went missing, and
     * it would present as "this theme's motion setting does nothing".
     */
    public static void apply() {
        // The player's motion flag first, and it was missing until a test caught it. `setCurrent`
        // pushes the *theme's* duration and curve into `Motion`; this pushes the player's own switch
        // into the same class. Without this line the switch did nothing at all: `Appearance.motion()`
        // reported the right answer, `Motion.enabled()` never heard about it, and turning motion off
        // left every hover easing exactly as before — a setting that is read by nothing, which is the
        // failure this codebase has now found four times.
        //
        // Order does not actually matter, and that is worth stating rather than leaving to be checked:
        // `setCurrent` touches the default duration and easing and never `enabled`, so the two write
        // different fields of `Motion` and cannot overwrite one another.
        Motion.setEnabled(settings.motion());

        ArmatureTheme.setCurrent(main());
    }

    /**
     * The player chooses a theme. Their choice, and it outranks anything a pack sends from now on.
     *
     * @return whether the name matched a theme; false leaves everything alone
     */
    public static boolean setTheme(String name) {
        if (Themes.any(name) == null) {
            return false;
        }
        settings = new Settings(name, settings.motion(), true, settings.custom());
        apply();
        save();
        return true;
    }

    /** Moves the player's choice to the next theme, wrapping. What the sidebar control does. */
    public static void cycleTheme() {
        setTheme(nextName(currentName()));
    }

    /** The name the main theme is currently taken from — the player's, the pack's, or the default. */
    public static String currentName() {
        return main().name();
    }

    /**
     * The name after {@code current} in the list a picker offers, wrapping.
     *
     * <p>Pure, and separate from {@link #cycleTheme} so the order and the wrap can be tested without a
     * client, a file or a theme being selected. An unknown name gives the first rather than nothing: it is
     * reachable from a hand-edited file, and the sensible reading of "cycle from a theme this build does
     * not have" is to start at the beginning.
     *
     * <p>Over {@link Themes#everything()} rather than the built-ins, so a theme added in
     * {@code config/armature/themes/} is reachable by clicking. An earlier version cycled the built-ins
     * only, which meant a file-loaded theme could be set by editing a file and never selected in game.
     *
     * <p>Compared by name rather than by {@code indexOf}, which looks like the longer way round and is the
     * shorter one: {@code Theme} is a record, so {@code equals} is component-wise, and two themes with the
     * same values would be one entry to {@code indexOf} and two to a reader. A name is the key everywhere
     * else in this system, so it is the key here.
     */
    public static String nextName(String current) {
        List<Theme> all = Themes.everything();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name().equalsIgnoreCase(current)) {
                return all.get((i + 1) % all.size()).name();
            }
        }
        return all.get(0).name();
    }

    /** Turns animation on or off for this client. The accessibility switch. */
    public static void setMotion(boolean on) {
        settings = new Settings(settings.theme(), on, settings.chosen(), settings.custom());
        apply();
        save();
    }

    // ------------------------------------------------------------------
    // The player's own edits
    // ------------------------------------------------------------------

    /**
     * Changes one colour, on top of whatever theme is in force. What the editor does on a click.
     *
     * <p>Stored as an edit rather than written into a copy of the theme, and that is the decision worth
     * stating: a player who has changed the panel colour and then switches theme keeps the change. The
     * alternative — editing a full theme and saving it — would mean a theme switch discarded their work,
     * which is the behaviour that makes an editor feel like it is fighting you.
     */
    public static void setCustom(String tokenId, int argb) {
        if (!ThemeToken.exists(tokenId)) {
            return;
        }
        Map<String, Integer> edits = new LinkedHashMap<>(settings.custom());
        edits.put(tokenId.toLowerCase(java.util.Locale.ROOT), argb);
        settings = new Settings(settings.theme(), settings.motion(), settings.chosen(), edits);
        apply();
        save();
    }

    /** Undoes one edit, back to whatever the theme under it says. */
    public static void clearCustom(String tokenId) {
        if (!settings.custom().containsKey(tokenId.toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        Map<String, Integer> edits = new LinkedHashMap<>(settings.custom());
        edits.remove(tokenId.toLowerCase(java.util.Locale.ROOT));
        settings = new Settings(settings.theme(), settings.motion(), settings.chosen(), edits);
        apply();
        save();
    }

    /** Undoes every edit. The editor's "revert". */
    public static void clearAllCustom() {
        if (settings.custom().isEmpty()) {
            return;
        }
        settings = new Settings(settings.theme(), settings.motion(), settings.chosen(), Map.of());
        apply();
        save();
    }

    /**
     * Begins editing from what is on screen now: every colour the main theme resolves to becomes an edit.
     *
     * <h2>Why an editor has to do this, and why it is not just a convenience</h2>
     *
     * <p>Because edits apply <i>over</i> a theme, and an editor that only recorded the tokens a player
     * touched would produce a file that depends on the theme it was written against. Save five colours on
     * top of {@code amethyst}, then switch to {@code paper}, and the result is a paper theme with five
     * amethyst colours in it — which is not what anybody meant, and which no amount of UI can explain.
     *
     * <p>So "edit" opens by materialising the resolved theme. From that point every colour is a decision
     * the player can see and change, and switching theme afterwards starts a new edit rather than
     * blending two. It costs a bigger file and it buys the property that matters: <b>what the editor
     * shows is what the file says.</b>
     */
    public static void beginEditing() {
        Theme resolved = main();
        Map<String, Integer> every = new LinkedHashMap<>();
        int[] colours = resolved.allColours();
        for (ThemeToken token : ThemeToken.ALL) {
            every.put(token.id(), colours[token.index()]);
        }
        settings = new Settings(settings.theme(), settings.motion(), settings.chosen(), every);
        apply();
        save();
    }

    /**
     * Writes the player's current edits out as a theme file, and returns its name.
     *
     * <p>The bridge between the editor and the file format, and the reason
     * {@link ThemeFiles#reload()} is called at the end: a theme saved in-game should be selectable in the
     * same session, which is the difference between an editor being usable and being a way to generate a
     * file you still have to restart to see.
     *
     * @param name the theme's name, sanitised to something that is a filename and a lookup key. Null or
     *     blank gives a name derived from the theme being edited, so a save never has to be refused
     * @return the name it was saved under, or null when there was nowhere to write
     */
    public static String saveAsTheme(String name) {
        Path directory = ThemeFiles.directory();
        if (directory == null) {
            Constants.LOG.warn("armature: no theme directory is known, so the theme could not be saved."
                    + " Is the platform layer installed?");
            return null;
        }

        String clean = sanitise(name);
        if (clean.isEmpty()) {
            clean = Themes.derivedName(settings.theme(), "edited");
        }

        // Written as a patch over the theme it was edited from, with every colour stated. Stating all of
        // them rather than only the changed ones is deliberate here even though a hand-written file should
        // do the opposite: a player who has just looked at forty colours and pressed save expects the file
        // to be the theme they saw, not a diff they would have to reason about. `basedOn` is kept so the
        // radius, motion and easing still come from somewhere, and so the file says what it started as.
        ThemePatch patch = new ThemePatch(clean, settings.custom(), null, null, null);
        JsonObject root = patch.toJson();
        root.addProperty("basedOn", settings.theme());

        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(clean + ".json"), root.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            Constants.LOG.warn("armature: the theme could not be written to {}", directory, e);
            return null;
        }

        ThemeFiles.reload(directory);
        // The directory it just wrote into, rather than `reload()`'s platform-resolved one. They are
        // the same path in a running client, and this is the version that is right for a reason: the
        // file was written to *this* directory, so *this* is the one whose catalogue is now stale. The
        // no-argument form re-asks the platform where the config directory is, which is a question
        // whose answer nothing here needs and which `ThemeFilesTest` found the hard way — a save into
        // a temporary directory reloaded the platform's directory, discarded the temp one, and the
        // theme it had just written became invisible in the same session it was saved in.
        // And the player is now using it, because saving a theme and not switching to it leaves the
        // player looking at something other than what they just made -- with the only clue being a file.
        setTheme(clean);
        clearAllCustom();
        return clean;
    }

    /**
     * A name fit to be a filename, a JSON key and a command argument.
     *
     * <p>Lowercased and stripped to letters, digits, dashes and underscores; runs of anything else become
     * one underscore. That is stricter than a display name needs to be, and it is the strictness that
     * makes a name safe everywhere at once: a name with a slash in it is a path traversal, a name with a
     * quote in it breaks the JSON, and a name with a space in it cannot be typed as a command argument
     * without quotes — three different bugs, prevented by one rule.
     */
    public static String sanitise(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean pendingUnderscore = false;
        for (char c : name.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                if (pendingUnderscore && !out.isEmpty()) {
                    out.append('_');
                }
                pendingUnderscore = false;
                out.append(Character.toLowerCase(c));
            }
            else {
                pendingUnderscore = true;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // The pack's default
    // ------------------------------------------------------------------

    /**
     * The theme the connected pack asked for, or null to clear it.
     *
     * <h2>Called on sync, and it must be, in both directions</h2>
     *
     * <p>A disconnect has to <i>clear</i> this, not leave it. Otherwise a pack's look would persist into
     * the main menu and into the next server — an appearance nobody chose, with nothing on screen saying
     * where it came from, which is the worst kind of sticky state. So the sync handler calls this with
     * null when the new server sends no theme, and {@code forgetViewState} calls it on disconnect.
     *
     * <p>A name this build has no theme for is kept as sent rather than dropped, because the failure is
     * worth reporting once and because the client may have loaded a file later. {@link #main} treats an
     * unresolvable name as absent, so a bad one costs nothing but a line in the log.
     */
    public static void setServerDefault(String name) {
        String trimmed = name == null ? null : name.trim();
        serverDefault = trimmed == null || trimmed.isEmpty() ? null : trimmed;
        apply();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Reads the settings from the platform's config directory, then applies them. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir("armature").resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            // The platform is installed by Armature's own entry point, so this is reachable only if
            // something calls this earlier than mod construction. Worth a line rather than a crash:
            // the defaults are a working appearance.
            Constants.LOG.warn("armature: the platform layer was not ready, so the appearance settings"
                    + " were not read. The defaults are in use.", e);
        }

        if (path == null) {
            apply();
            return;
        }
        load(path);
    }

    /**
     * Reads the settings from one file, then applies them.
     *
     * <p>A missing file is not a problem and not worth a word — it is what a first run looks like, and the
     * defaults are the answer. A file that exists and cannot be read is worth a word, and gets one that
     * says where it is and what to do about it. Neither case stops the client.
     */
    public static void load(Path path) {
        file = path;

        if (Files.isRegularFile(path)) {
            try {
                settings = read(Files.readString(path, StandardCharsets.UTF_8));
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("armature: {} could not be read, so the default appearance is in use."
                        + " Deleting the file will stop this message.", path, e);
            }
        }
        apply();
    }

    /**
     * Parses settings from JSON text.
     *
     * <p>Tolerant in both directions and strict about nothing, which is the right trade for a file a
     * player may hand-edit. A missing field takes its default; an unknown theme name falls back and
     * <b>says so</b>, because a name that is silently ignored is the exact shape of bug this codebase
     * keeps finding — a value that is parsed, kept, and read by nothing.
     *
     * <p>The unknown-token check on {@code custom} is worth its length: an edited colour is a value the
     * editor wrote, and a build that no longer has that token would otherwise carry it around forever
     * doing nothing, which is a file that lies about what it contains.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Settings read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        boolean chosen = root.has("chosen") && root.get("chosen").getAsBoolean();
        String theme = root.has("theme") ? root.get("theme").getAsString() : Themes.DEFAULT.name();
        if (Themes.any(theme) == null) {
            Constants.LOG.warn("armature: \"{}\" is not a theme this build has, so {} is in use instead."
                    + " The ones it has are: {}", theme, Themes.DEFAULT.name(), Themes.names());
            theme = Themes.DEFAULT.name();
        }

        // Absent means on, so a file written by an older build — or by hand with one field in it — does
        // not silently turn animation off for someone who never asked.
        boolean motion = !root.has("motion") || root.get("motion").getAsBoolean();

        Map<String, Integer> custom = new LinkedHashMap<>();
        if (root.has("custom") && root.get("custom").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : root.getAsJsonObject("custom").entrySet()) {
                String token = entry.getKey().trim();
                if (!ThemeToken.exists(token)) {
                    Constants.LOG.warn("armature: \"{}\" in {} is not a colour this build can set, so it"
                            + " was dropped. The ones it has are: {}", token, FILE_NAME, ThemeToken.ids());
                    continue;
                }
                Integer argb = entry.getValue().isJsonPrimitive()
                        ? Colour.fromHex(entry.getValue().getAsString())
                        : null;
                if (argb == null) {
                    Constants.LOG.warn("armature: the custom colour for \"{}\" in {} is not a hex colour",
                            token, FILE_NAME);
                    continue;
                }
                custom.put(token.toLowerCase(java.util.Locale.ROOT), argb);
            }
        }

        return new Settings(theme, motion, chosen, custom);
    }

    /** The settings as JSON text. Paired with {@link #read}, and the only writer of that format. */
    public static String write(Settings toWrite) {
        JsonObject root = new JsonObject();
        root.addProperty("theme", toWrite.theme());
        root.addProperty("motion", toWrite.motion());
        // Written explicitly rather than inferred from the theme's presence, because the whole point of
        // this field is that "I chose modern" and "nobody has chosen and the default happens to be
        // modern" are different facts with the same name in them.
        root.addProperty("chosen", toWrite.chosen());
        if (!toWrite.custom().isEmpty()) {
            root.add("custom", new ThemePatch(null, toWrite.custom(), null, null, null).toJson()
                    .getAsJsonObject("colours"));
        }
        return root.toString();
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, write(settings), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            // Not fatal and not worth stopping a frame for. The setting is live either way; it just
            // will not be there next time, which is worth a line so it is not a mystery later.
            Constants.LOG.warn("armature: the appearance settings could not be saved to {}", file, e);
        }
    }

    /**
     * Back to the defaults, detached from any file.
     *
     * <p>For a client shutdown, and for a test that changed it — the same framing as
     * {@code ArmatureTheme.resetCurrent}. Detaching from the file matters for the second case: a test
     * that has loaded a temporary file and then sets a theme would otherwise write to it, and a test
     * that writes to the file it is reading is one that passes for the wrong reason.
     */
    public static void reset() {
        settings = Settings.DEFAULT;
        serverDefault = null;
        file = null;
        ThemeFiles.reset();
        apply();
    }

    /** Every problem from the last theme-file read, for the editor to show. */
    public static List<String> themeProblems() {
        return new ArrayList<>(ThemeFiles.problems());
    }
}
