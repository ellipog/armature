package dev.ellipog.armature.client.ui;

import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Easing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A change to a theme: some colours, maybe a radius, maybe a motion, maybe a name.
 *
 * <h2>The thing that makes three levels of override one piece of code</h2>
 *
 * <p>Four separate features want to change part of an appearance, and before this they would have been
 * four implementations:
 *
 * <ol>
 *   <li><b>A theme file</b> in {@code config/armature/themes/} — a full theme, but written as a diff
 *       against a built-in so it says only what it changes.</li>
 *   <li><b>A group</b> in a data file, naming a theme and optionally a handful of colours.</li>
 *   <li><b>A single entry</b>, which may do the same on top of its group.</li>
 *   <li><b>The editor</b>, which is a person changing one colour at a time and expecting the rest to
 *       stay put.</li>
 * </ol>
 *
 * <p>All four are "these tokens become these values". So this is that, and the merge is written once.
 * A partial override is not a special case to be added later; it is the only case there is, and a
 * "full" theme is a patch that happens to name every token.
 *
 * <h2>Two ways to apply it, and the boundary between them is a decision</h2>
 *
 * <p>{@link #applyTo} and {@link #tint} look like near-duplicates and are not:
 *
 * <ul>
 *   <li><b>{@link #applyTo}</b> is how a whole theme is made — a theme file, or one of the derived
 *       built-ins. It takes the name, the radius, the motion and the easing as well as the colours,
 *       because a theme is those things.</li>
 *   <li><b>{@link #tint}</b> is how a region is reskinned — a group, an entry. It takes colours only.
 *       A group that could change the corner radius of every panel in the book, or the duration of
 *       every transition, would be a data file reaching into the screen's construction; and the
 *       motion one would silently do nothing anyway, since animation timing is pushed to
 *       {@code Motion} once per client rather than per region. <b>A value that cannot take effect is
 *       worse than one that is refused</b> — this codebase has already shipped a shape field that was
 *       parsed, validated, printed and drawn by nothing.</li>
 * </ul>
 *
 * <h2>Why an unknown token is a warning and not an error</h2>
 *
 * <p>Same reasoning as {@code Shapes.byName} falling back and {@code Themes.byName} not: a token id
 * arrives from a file a person wrote, and the failure modes differ by severity. Dropping the whole
 * patch because one of forty keys is misspelled would leave a chapter that looks like nothing happened;
 * applying the thirty-nine and naming the one that was ignored is strictly more useful, and the message
 * is what turns a silent nothing into a five-second fix. {@link #unknownTokens} is the list; the caller
 * decides how loudly to say it.
 *
 * @param name         the name to give the result, or null to keep the base's
 * @param colours      token id to ARGB. Ids are matched case-insensitively and trimmed, because they
 *                     come from JSON keys
 * @param cornerRadius a new radius, or null to keep the base's
 * @param motion       a new motion duration in milliseconds, or null to keep the base's. Zero is a
 *                     value — instant — and is not the same as null
 * @param easing       a new curve, or null to keep the base's
 * @param background   a new canvas background, or null to keep the base's
 */
public record ThemePatch(
        String name,
        Map<String, Integer> colours,
        Integer cornerRadius,
        Long motion,
        Easing easing,
        CanvasBackground background) {

    /** The patch that changes nothing. Applying it returns the base unchanged. */
    public static final ThemePatch NONE = new ThemePatch(null, Map.of(), null, null, null, null);

    /**
     * A node's border follows its state colour unless the patch says otherwise.
     *
     * <h2>Why a rule rather than a note in the documentation</h2>
     *
     * <p>The four node borders are tokens of their own — a node's ring is a shape outline on a canvas
     * and a state colour is a word on a panel, so an author needs to be able to move one without the
     * other. But that means an author who writes {@code "available": "#C79BF0"} and nothing else gets a
     * <b>violet word beside a blue ring</b>, which looks like the theme half-applied. Documenting "set
     * both" would be a rule nobody reads, and the failure is quiet: the theme loads, the picker lists
     * it, and one ring is simply the wrong colour.
     *
     * <p>So three of the four follow by default, and the fourth deliberately does not:
     *
     * <ul>
     *   <li>{@code available}, {@code inProgress} and {@code complete} each carry their node border
     *       along, because there is no reading of "this node is available" in which the ring
     *       should stay whatever the base theme's available colour was.</li>
     *   <li><b>{@code blocked} does not</b>, and that is a design decision rather than an oversight.
     *       {@code blocked} is the colour of the word "blocked"; {@code nodeEdgeBlocked} is the outline
     *       of a node you cannot click yet. In every shipped theme the second is considerably dimmer than
     *       the first — {@code #66666F} against {@code #43434F} in the default — because a locked node
     *       should recede and the word explaining why should stay legible. Making them follow would undo
     *       that in every theme, which is the opposite of what a default should do.</li>
     * </ul>
     *
     * <p>A patch that names both, which every shipped theme does, is unaffected: the explicit value
     * wins. So this only ever fills in an omission.
     */
    private static final Map<String, String> BORDER_FOLLOWS_STATE = Map.of(
            "available", "nodeEdgeAvailable",
            "inprogress", "nodeEdgeInProgress",
            "complete", "nodeEdgeComplete");

    public ThemePatch {
        colours = Map.copyOf(colours);
    }

    /** A named patch — a theme file, or one of the derived built-ins. */
    public static ThemePatch of(String name, Map<String, Integer> colours) {
        return new ThemePatch(name, colours, null, null, null, null);
    }

    /** An unnamed patch of colours, for a group or an entry. */
    public static ThemePatch colours(Map<String, Integer> colours) {
        return new ThemePatch(null, colours, null, null, null, null);
    }

    /** One token. What the editor does on a click. */
    public static ThemePatch one(String tokenId, int argb) {
        return colours(Map.of(tokenId, argb));
    }

    /** Whether this changes anything at all. */
    public boolean isEmpty() {
        return colours.isEmpty() && cornerRadius == null && motion == null && easing == null
                && background == null;
    }

    /**
     * The base, with everything this patch carries applied: colours, radius, motion, easing,
     * background and name.
     *
     * <p>For making a theme. See the class note for why {@link #tint} exists beside it.
     *
     * <h2>The three that are not colours, and the bug this fixes</h2>
     *
     * <p>This used to apply only the colours and the name, and it did so silently: a theme file with
     * <code>"cornerRadius": 6</code> in it produced a theme at the base's radius, with no warning and
     * nothing to compare against. That is precisely the failure this codebase has recorded three times
     * — a value that is parsed, validated and carried by something while nothing acts on it — and it
     * lives here rather than anywhere more interesting because <b>this is the only place a patch's
     * non-colour values are consumed at all.</b> {@link #tint} deliberately drops them, so if this
     * method does not apply them, nothing does.
     *
     * <p>Each is applied through the record's own copy methods, which is what keeps the field order in
     * {@link Theme#from} the only place that knows it. Chaining them also means the order here cannot
     * matter — none of the four touches another — which is worth knowing because the alternatives, an
     * argument list or a rebuild, are both order-dependent.
     */
    public Theme applyTo(Theme base) {
        Theme result = tint(base);
        if (name != null) {
            result = result.withName(name);
        }
        if (cornerRadius != null) {
            result = result.withRadius(cornerRadius);
        }
        if (motion != null) {
            result = result.withMotion(motion);
        }
        if (easing != null) {
            result = result.withEasing(easing);
        }
        if (background != null) {
            result = result.withBackground(background);
        }
        return result;
    }

    /**
     * The base, with this patch's colours applied and nothing else.
     *
     * <p>For reskinning a region inside a theme it does not own. The radius, the motion and the easing
     * are deliberately ignored — see the class note.
     */
    public Theme tint(Theme base) {
        if (colours.isEmpty()) {
            return base;
        }
        int[] values = base.allColours();
        for (Map.Entry<String, Integer> entry : colours.entrySet()) {
            int index = ThemeToken.indexOf(entry.getKey());
            if (index >= 0) {
                values[index] = entry.getValue();
            }
        }

        // Then the borders that follow their state, so a theme that names one of those three and not
        // its border gets both. Done after the explicit pass rather than inside it, because a patch
        // that names both must have its own border win -- see BORDER_FOLLOWS_STATE for why only three
        // of the four follow.
        for (Map.Entry<String, String> follows : BORDER_FOLLOWS_STATE.entrySet()) {
            Integer state = colourFor(colours, follows.getKey());
            if (state == null || colourFor(colours, follows.getValue()) != null) {
                continue;
            }
            int index = ThemeToken.indexOf(follows.getValue());
            if (index >= 0) {
                values[index] = state;
            }
        }
        // The three that are not colours are only reachable through `applyTo`; a `tint` keeps the base's
        // radius and motion, which is what "reskin this region" means.
        return Theme.from(base.name(), values, base.cornerRadius(), base.motion(), base.easing(),
                base.background());
    }

    /**
     * This patch with another applied on top of it.
     *
     * <p>The order is the whole point: {@code main.merge(group)} means the group wins, and
     * {@code group.merge(entry)} means the entry wins. That is how the three levels compose without
     * any of them knowing about the others — see {@code Appearance}, which is the only thing that knows
     * the order.
     *
     * <p>A null field in the child means "no opinion" and keeps the parent's, which is what makes the
     * merge associative enough to be applied in any grouping.
     */
    public ThemePatch merge(ThemePatch child) {
        if (child == null || child.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return child;
        }
        Map<String, Integer> merged = new LinkedHashMap<>(colours);
        merged.putAll(child.colours);
        return new ThemePatch(
                child.name != null ? child.name : name,
                merged,
                child.cornerRadius != null ? child.cornerRadius : cornerRadius,
                child.motion != null ? child.motion : motion,
                child.easing != null ? child.easing : easing,
                child.background != null ? child.background : background);
    }

    /**
     * A colour in this patch by token id, case-insensitively, or null.
     *
     * <p>Separate from {@link Theme#colour} because that one throws on an unknown id and this one is
     * answering a question — "does this patch mention X" — where the answer for a name that is not a
     * token at all is plainly "no". The keys here come from a JSON file and are lowercased on the way in
     * by {@link #fromJson}, but a patch built in code may not be, so the lookup normalises.
     */
    private static Integer colourFor(Map<String, Integer> from, String id) {
        Integer direct = from.get(id);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Integer> entry : from.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(id)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The ids in here that name no colour this build has. For a message, once per patch. */
    public Set<String> unknownTokens() {
        return colours.keySet().stream()
                .filter(id -> !ThemeToken.exists(id))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    /**
     * The patch as it is written in a file.
     *
     * <pre>{@code
     * {
     *   "name": "amethyst",
     *   "basedOn": "modern",
     *   "colours": { "panel": "#26212E", "canvas": "#1B1720", "available": "#C79BF0" },
     *   "cornerRadius": 6,
     *   "motion": 120,
     *   "easing": "QUAD_OUT"
     * }
     * }</pre>
     *
     * <p>{@code basedOn} is not part of this record — it is the reader's business, not a patch's, since
     * by the time a patch is being applied the base has already been chosen. {@link #fromJson} accepts
     * and ignores it, so a file that names one and a file that does not are read by the same code.
     */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        if (name != null) {
            root.addProperty("name", name);
        }
        JsonObject colourObject = new JsonObject();
        // Sorted, so a file written by the editor has stable diffs and a person comparing two saved
        // themes sees the difference rather than the insertion order.
        colours.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> colourObject.addProperty(entry.getKey(), Colour.toHex(entry.getValue())));
        root.add("colours", colourObject);

        if (cornerRadius != null) {
            root.addProperty("cornerRadius", cornerRadius);
        }
        if (motion != null) {
            root.addProperty("motion", motion);
        }
        if (easing != null) {
            root.addProperty("easing", easing.name());
        }
        if (background != null) {
            root.add("canvasBackground", background.toJson());
        }
        return root;
    }

    /** The base theme a file says it starts from, or null. Read here so one file format covers both. */
    public static String basedOn(JsonObject root) {
        return root.has("basedOn") && !root.get("basedOn").isJsonNull()
                ? root.get("basedOn").getAsString()
                : null;
    }

    /**
     * Reads a patch, collecting anything wrong into {@code problems}.
     *
     * <p>Tolerant by design, and the tolerance is chosen: a misspelled colour <i>value</i> should not
     * cost the author the other thirty-nine, but it must not be silently dropped either. So an
     * unreadable hex value is skipped and named, and the patch is still returned.
     *
     * <p>The one thing it refuses is a {@code colours} entry that is not a string or a number at all,
     * since there is no sensible colour to invent for it.
     *
     * @param problems a list to append human-readable messages to. Never null; pass a throwaway list if
     *     you intend to ignore them, which is at least a decision made on purpose
     */
    public static ThemePatch fromJson(JsonObject root, List<String> problems) {
        String name = root.has("name") && !root.get("name").isJsonNull()
                ? root.get("name").getAsString()
                : null;

        Map<String, Integer> colours = new LinkedHashMap<>();
        if (root.has("colours")) {
            JsonElement element = root.get("colours");
            if (!element.isJsonObject()) {
                problems.add("'colours' should be an object of token names to hex values");
            }
            else {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    String token = entry.getKey().trim();
                    JsonElement value = entry.getValue();

                    String text;
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        text = value.getAsString();
                    }
                    else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        // A bare number is accepted as 0xAARRGGBB, because that is what this codebase
                        // writes in source and a person copying a value across should not have to
                        // reformat it. Read as unsigned: `getAsLong` on 0xFF24242E would be negative.
                        text = Colour.toHex((int) value.getAsLong());
                    }
                    else {
                        problems.add("'" + token + "' is not a colour: expected a hex string like"
                                + " \"#24242E\", or a number like 4280562734");
                        continue;
                    }

                    Integer argb = Colour.fromHex(text);
                    if (argb == null) {
                        problems.add("'" + token + "' is set to \"" + text + "\", which is not a hex"
                                + " colour. Six digits means opaque (#24242E), eight carries alpha"
                                + " (#FF24242E), and the hash is optional");
                        continue;
                    }
                    if (!ThemeToken.exists(token)) {
                        problems.add("'" + token + "' is not a colour a theme can set, so it was"
                                + " ignored. The ones available are: " + ThemeToken.ids());
                        continue;
                    }
                    colours.put(token.toLowerCase(Locale.ROOT), argb);
                }
            }
        }

        // `isNumber` rather than a bare `getAsInt`, and this is a bug fix rather than defensiveness.
        //
        // `getAsLong` on a boolean throws `NumberFormatException: For input string: "true"`, which is a
        // RuntimeException escaping a method whose entire contract is "collect anything wrong into
        // problems". It escaped `ThemeFiles.reload` too -- that only guarded the JSON *parse* -- so a
        // theme file with `"motion": true` in it took the client down during startup, which is the one
        // outcome this whole reader exists to avoid. Found by a test that happened to put an
        // `appearance.json` beside the themes, where the same word means a boolean; the reader has to be
        // right about that regardless of who writes the file.
        Integer radius = null;
        if (root.has("cornerRadius") && !root.get("cornerRadius").isJsonNull()) {
            JsonElement value = root.get("cornerRadius");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                problems.add("'cornerRadius' should be a whole number of pixels, like 4, but is " + value
                        + ". Use 0 for square corners");
            }
            else {
                radius = value.getAsInt();
                if (radius < 0) {
                    problems.add("'cornerRadius' cannot be negative: " + radius + ". Use 0 for square");
                    radius = null;
                }
            }
        }

        Long motion = null;
        if (root.has("motion") && !root.get("motion").isJsonNull()) {
            JsonElement value = root.get("motion");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                // The word `true` is a real thing to find here, because the same key means a boolean in
                // `appearance.json` and a duration in a theme. Naming both readings is what turns this
                // from a five-minute confusion into a one-line fix for whoever hit it.
                problems.add("'motion' should be a duration in milliseconds, like 140, but is " + value
                        + ". Use 0 for instant -- note that `true` and `false` are not durations");
            }
            else {
                motion = value.getAsLong();
                if (motion < 0) {
                    problems.add("'motion' cannot be negative: " + motion + ". Use 0 for instant");
                    motion = null;
                }
            }
        }

        Easing easing = null;
        if (root.has("easing") && !root.get("easing").isJsonNull()) {
            String curve = root.get("easing").getAsString().trim().toUpperCase(Locale.ROOT);
            try {
                easing = Easing.valueOf(curve);
            }
            catch (IllegalArgumentException e) {
                problems.add("'" + curve + "' is not a curve. Try one of: "
                        + String.join(", ", java.util.Arrays.stream(Easing.values())
                                .map(Enum::name).toList()));
            }
        }

        // Read through the background's own reader, which is where the pattern names are known and
        // where a reserved one is told apart from a misspelled one. A null here is "no opinion" and
        // keeps the base's background -- see `CanvasBackground.fromJson`.
        CanvasBackground background = root.has("canvasBackground") && !root.get("canvasBackground").isJsonNull()
                ? CanvasBackground.fromJson(root.get("canvasBackground"), problems)
                : null;

        return new ThemePatch(name, colours, radius, motion, easing, background);
    }

    /** Every token id, with its label and its group — the list a file may set and an editor shows. */
    public static JsonArray tokenCatalogue() {
        JsonArray out = new JsonArray();
        for (ThemeToken token : ThemeToken.ALL) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", token.id());
            entry.addProperty("label", token.label());
            entry.addProperty("group", token.group().name());
            out.add(entry);
        }
        return out;
    }
}
