package dev.ellipog.armature.api.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The field checks a validator is made of, each one reporting into a {@link Problems}.
 *
 * <p>These exist so that validating a format reads like the format's own documentation:
 * "required string here, optional list of ids there, no fields other than these". Written out by
 * hand per format, that is where the interesting part of a validator lives; the mechanics of
 * "is this a string and if not what is it" are not, so they are here instead.
 *
 * <p>Every method returns something usable on failure — {@code null}, an empty Optional, the
 * fallback — so a caller can keep walking a broken file and report everything wrong with it
 * rather than unwinding at the first problem.
 */
public final class Checks {

    private Checks() {
    }

    // ------------------------------------------------------------------
    // Running a codec, which is the one thing here that is not about shape
    // ------------------------------------------------------------------

    /**
     * Runs a codec over an already-parsed value, <b>never letting it throw</b>.
     *
     * <h2>Why a boundary is needed at all</h2>
     *
     * <p>DFU reports through {@link DataResult}, so a codec is not <i>supposed</i> to throw — but the
     * functions inside one are caller-supplied, and DFU does not catch what they throw.
     * {@code Decoder.map} (what {@code xmap} builds on) contains no exception table at all, so an
     * exception raised in an {@code xmap} or {@code comapFlatMap} function leaves the codec as a raw
     * throwable rather than a {@code DataResult} error.
     *
     * <p>That is a mistake which has already happened here once, in a built-in codec, and its cost was
     * not local: a loader that handles {@code DataResult} errors and has no try/catch lets the
     * exception out of the whole load, so <b>one malformed value in one file took down every file</b>.
     * A hand-written codec that forgets is an easy mistake to repeat — and this library's whole point
     * is that other mods register their own, so the codec that throws may be one nobody here wrote.
     *
     * <p>So the code that turns a codec's outcome into a message is the right place to contain it: one
     * boundary, and every caller of this method gets the guarantee rather than each of them having to
     * remember. The exception is not swallowed — it becomes the error's message, so it reaches the
     * author and the log exactly as a decode failure would.
     *
     * <p><b>{@code RuntimeException}, not {@code Throwable}.</b> An {@code Error} is a broken JVM rather
     * than a broken file, and containing one would be pretending the process is healthy. The input is
     * also bounded before it arrives: {@link JsonDocument} refuses a document nested more than 128
     * levels deep, so codec recursion over a parsed document cannot exhaust the stack.
     *
     * @param codec the codec to run. Its own functions may throw; this method will not.
     * @param input an already-parsed value, normally a {@link JsonDocument}'s root
     */
    public static <T> DataResult<T> parse(Codec<T> codec, JsonElement input) {
        return parse(codec, input, JsonOps.INSTANCE);
    }

    /**
     * The same boundary over caller-supplied ops: registry-backed codecs (data components,
     * holders, tags) decode against registries, which plain JSON ops cannot see. Callers
     * with a registry context pass it here; callers without one use {@link #parse(Codec,
     * JsonElement)} and accept that registry-backed fields cannot be judged.
     *
     * @param codec the codec to run. Its own functions may throw; this method will not.
     * @param input an already-parsed value, normally a {@link JsonDocument}'s root
     * @param ops   the ops to decode with, usually registry ops from the server
     */
    public static <T> DataResult<T> parse(Codec<T> codec, JsonElement input, DynamicOps<JsonElement> ops) {
        try {
            return codec.parse(ops, input);
        }
        catch (RuntimeException e) {
            return DataResult.error(() -> "this value made the codec throw rather than report a problem: "
                    + e + "\n    (a codec's own functions must return a DataResult error instead of"
                    + " throwing - this is a bug in whichever codec reads this field, not in the file)");
        }
    }

    // ------------------------------------------------------------------
    // Presence and shape
    // ------------------------------------------------------------------

    public static boolean exists(JsonDocument document, String path) {
        return document.has(path);
    }

    /** The element at {@code path}, if it is a string. Reports nothing if absent — that is the caller's decision. */
    public static Optional<String> optionalString(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return Optional.empty();
        }
        if (!isString(element)) {
            problems.error(document, path, "expected a string, found " + kindOf(element));
            return Optional.empty();
        }
        return Optional.of(element.getAsString());
    }

    public static Optional<String> string(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            problems.error(document, path, "missing required field " + nameOf(path));
            return Optional.empty();
        }
        return optionalString(document, path, problems);
    }

    /**
     * The field name at the end of a path — {@code title} for {@code $.chapterGroups[0].title}.
     *
     * <p>Exists so a "missing required field" message can say <i>which</i> field. Without it the
     * message is technically true and practically useless: at a line and column pointing at an
     * object with eight fields in it, "a required field is missing" leaves the reader to work out
     * which one by elimination. The name is right there in the path and costs nothing to print.
     */
    public static String nameOf(String path) {
        int cut = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
        if (cut < 0) {
            return path;
        }
        return path.substring(cut + 1).replace("]", "");
    }

    public static OptionalInt optionalInt(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return OptionalInt.empty();
        }
        OptionalInt parsed = asInt(element);
        if (parsed.isEmpty()) {
            problems.error(document, path, "expected a whole number, found " + describe(element));
            return OptionalInt.empty();
        }
        return parsed;
    }

    public static OptionalInt integer(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            problems.error(document, path, "missing required field " + nameOf(path));
            return OptionalInt.empty();
        }
        return optionalInt(document, path, problems);
    }

    public static Optional<Boolean> optionalBool(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return Optional.empty();
        }
        if (!isBoolean(element)) {
            problems.error(document, path, "expected true or false, found " + kindOf(element));
            return Optional.empty();
        }
        return Optional.of(element.getAsBoolean());
    }

    /** An object that must be present. Returns null and reports once if it is not. */
    public static JsonObject object(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            problems.error(document, path, "missing required object");
            return null;
        }
        if (!element.isJsonObject()) {
            problems.error(document, path, "expected an object, found " + kindOf(element));
            return null;
        }
        return element.getAsJsonObject();
    }

    public static Optional<JsonObject> optionalObject(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonObject()) {
            problems.error(document, path, "expected an object, found " + kindOf(element));
            return Optional.empty();
        }
        return Optional.of(element.getAsJsonObject());
    }

    /**
     * An array that must be present.
     *
     * <p>An empty array is fine — a group with no entries is a legitimate thing to be writing
     * while authoring.
     */
    public static JsonArray array(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            problems.error(document, path, "missing required list " + nameOf(path));
            return null;
        }
        if (!element.isJsonArray()) {
            problems.error(document, path, "expected a list, found " + kindOf(element));
            return null;
        }
        return element.getAsJsonArray();
    }

    public static Optional<JsonArray> optionalArray(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonArray()) {
            problems.error(document, path, "expected a list, found " + kindOf(element));
            return Optional.empty();
        }
        return Optional.of(element.getAsJsonArray());
    }

    /** A list of strings. Reports the index of any element that is not one. */
    public static List<String> stringList(JsonDocument document, String path, Problems problems) {
        JsonArray array = array(document, path, problems);
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String elementPath = path + "[" + i + "]";
            if (!isString(element)) {
                problems.error(document, elementPath, "expected a string, found " + kindOf(element));
                continue;
            }
            out.add(element.getAsString());
        }
        return out;
    }

    public static List<String> optionalStringList(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            return List.of();
        }
        return stringList(document, path, problems);
    }

    // ------------------------------------------------------------------
    // Identifiers
    // ------------------------------------------------------------------

    /**
     * A required identifier: lowercase letters, digits and underscores.
     *
     * <p>Strict on purpose. These ids appear in player progress files and in other entries'
     * {@code dependsOn}, so an id with a space or a capital in it is a problem that surfaces much
     * later and far from where it was written.
     */
    public static Optional<String> id(JsonDocument document, String path, Problems problems) {
        Optional<String> value = string(document, path, problems);
        if (value.isEmpty()) {
            return value;
        }
        String id = value.get();
        if (id.isEmpty()) {
            problems.error(document, path, "an id may not be empty");
            return Optional.empty();
        }
        if (id.length() > 64) {
            problems.error(document, path, "an id may be at most 64 characters, this one is " + id.length());
            return Optional.empty();
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                problems.error(document, path, "an id may only contain lowercase letters, digits and underscores; "
                        + "'" + id + "' contains '" + c + "'");
                return Optional.empty();
            }
        }
        return value;
    }

    public static List<String> idList(JsonDocument document, String path, Problems problems) {
        List<String> raw = optionalStringList(document, path, problems);
        List<String> ids = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            String candidate = raw.get(i);
            String elementPath = path + "[" + i + "]";
            if (candidate.isEmpty() || candidate.length() > 64) {
                problems.error(document, elementPath, "an id must be between 1 and 64 characters");
                continue;
            }
            boolean ok = true;
            for (int j = 0; j < candidate.length(); j++) {
                char c = candidate.charAt(j);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                problems.error(document, elementPath, "'" + candidate + "' is not a valid id; only lowercase letters, "
                        + "digits and underscores are allowed");
                continue;
            }
            ids.add(candidate);
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // The check that saves the most time
    // ------------------------------------------------------------------

    /**
     * Reports every field that is not in {@code allowed}.
     *
     * <p>This is the single most valuable check in the whole validator, because of how the
     * alternative behaves. Minecraft's codecs <b>silently ignore fields they do not know</b>. So a
     * typo — {@code "titl"} for {@code "title"}, {@code "dependson"} for {@code "dependsOn"} —
     * produces an entry with a missing title and no dependency, no error, and nothing in any log to
     * say which field was wrong. The author is left comparing two files character by character.
     *
     * <p>A common hand-editing tool has exactly this behaviour, and it is responsible for a lot of
     * the frustration with hand-editing data files.
     *
     * <p>Where a field is close to one that is allowed, the suggestion is included. That is a
     * twenty-line edit distance calculation, and it turns "unknown field" into the answer.
     */
    public static void rejectUnknown(JsonDocument document, String path, Set<String> allowed, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null || !element.isJsonObject()) {
            return;
        }
        for (String key : element.getAsJsonObject().keySet()) {
            if (allowed.contains(key)) {
                continue;
            }
            String fieldPath = path + "." + key;

            // A comment attempt gets its own message. JSON has no comments, so `//` and `#` are
            // simply unknown fields -- and "unknown field //" would leave the author wondering what
            // it should have been called. Naming the actual problem is one line and saves a search.
            if (key.equals("//") || key.startsWith("#") || key.equals("_comment") || key.equals("comment")) {
                problems.error(document, fieldPath,
                        "\"" + key + "\" looks like a comment, but JSON has none. Delete this field."
                                + "\n    (a file whose NAME starts with _ is ignored entirely, which is"
                                + " how a whole file is kept out of the way)");
                continue;
            }

            Optional<String> suggestion = nearest(key, allowed);
            String message = "unknown field \"" + key + "\""
                    + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                    + "\n    valid fields here: " + String.join(", ", allowed.stream().sorted().toList());
            problems.error(document, fieldPath, message);
        }
    }

    /** The allowed name closest to {@code name}, if any is within two edits. */
    private static Optional<String> nearest(String name, Set<String> allowed) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : allowed) {
            int distance = editDistance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Levenshtein, two rows. Only ever called on short field names, and never in a hot path. */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ------------------------------------------------------------------
    // Descriptions of what was found, for error messages
    // ------------------------------------------------------------------

    /** "a string", "a list", "a whole number" — for messages that say what was expected instead. */
    public static String kindOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            return "an object";
        }
        if (element.isJsonArray()) {
            return "a list";
        }
        if (isString(element)) {
            return "a string";
        }
        if (isBoolean(element)) {
            return "true or false";
        }
        if (isWholeNumber(element)) {
            return "a whole number";
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return "a decimal number";
        }
        return "something unrecognised";
    }

    /** A value quoted, for messages about a string that is present but wrong. */
    public static String describe(JsonElement element) {
        if (element != null && isString(element)) {
            return "\"" + element.getAsString() + "\"";
        }
        return kindOf(element);
    }

    private static boolean isString(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        // Gson reports a primitive as a string if it was quoted, which is what we want: the JSON
        // `"21"` is a string, and a field expecting a number should reject it rather than coerce.
        return primitive.isString();
    }

    private static boolean isBoolean(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isWholeNumber(JsonElement element) {
        OptionalInt value = asInt(element);
        return value.isPresent();
    }

    private static OptionalInt asInt(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return OptionalInt.empty();
        }
        try {
            double asDouble = element.getAsDouble();
            if (asDouble != Math.floor(asDouble) || Double.isInfinite(asDouble)) {
                return OptionalInt.empty();
            }
            if (asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
                return OptionalInt.empty();
            }
            return OptionalInt.of((int) asDouble);
        }
        catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
