package dev.ellipog.armature.api.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed JSON file that remembers where every value came from.
 *
 * <h2>Why this exists rather than just using Gson</h2>
 *
 * <p>Because a configuration format is a user interface, and the only real feature of a user
 * interface is telling you what you did wrong and where. Neither of the two obvious routes gives
 * that:
 *
 * <ul>
 *   <li><b>Gson's tree parser</b> throws with line and column for a <i>syntax</i> error, but the
 *       resulting {@code JsonElement} has thrown the positions away — so every later complaint
 *       ("this field is missing") can name the file and nothing more precise.</li>
 *   <li><b>Minecraft's codecs</b> are the right way to turn JSON into objects, but their errors
 *       carry no position at all. You get {@code Missing field "title"} and a file name.</li>
 * </ul>
 *
 * <p>So this parses the text itself, recording the position of every value it produces, keyed by
 * path. A validator can then say {@code stone_age.json:14:9: expected a string, found an array}
 * and mean it. The tree it hands on is ordinary Gson, so codecs consume it unchanged.
 *
 * <p>One parser, not two: <b>the tree this returns is the tree the codec decodes.</b> There is no
 * second interpretation of the same file that could disagree with this one.
 *
 * <p>Deliberately strict, matching RFC 8259 exactly: no comments, no trailing commas, no unquoted
 * keys, no {@code NaN}, no leading zeros, no unescaped control characters. Every one of those is
 * something a config format is better off rejecting outright, with a precise error, than
 * accepting by accident. Depth is capped, so a pathological file cannot exhaust the stack.
 */
public final class JsonDocument {

    /** Deeper than any hand-written config, shallow enough that recursion cannot overflow. */
    private static final int MAX_DEPTH = 128;

    private final String name;
    private final String source;
    private final JsonElement root;
    private final Map<String, JsonLocation> locations;
    private final String[] lines;

    private JsonDocument(String name, String source, JsonElement root, Map<String, JsonLocation> locations) {
        this.name = name;
        this.source = source;
        this.root = root;
        this.locations = locations;
        this.lines = source.split("\n", -1);
    }

    /**
     * @param name   what to call this file in messages — a path, usually relative to the game
     *               directory, because an absolute path in a log line is mostly noise
     * @param source the file's text
     * @throws JsonParseException if it is not valid JSON, with the position of the problem
     */
    public static JsonDocument parse(String name, String source) throws JsonParseException {
        return new Parser(name, source).parse();
    }

    /** What to call this file in messages. */
    public String name() {
        return name;
    }

    /** The whole file, as a Gson tree that also happens to be position-aware through this object. */
    public JsonElement root() {
        return root;
    }

    /** The exact text a line came from, for rendering an error. Empty for a line out of range. */
    public String lineText(int line) {
        if (line < 1 || line > lines.length) {
            return "";
        }
        // A tab would misalign the caret under it, so expand as we go. Cheap, and the alignment
        // is the whole point of printing the line.
        return lines[line - 1].replace("\t", "    ");
    }

    public Optional<JsonElement> get(String path) {
        return navigate(path);
    }

    public boolean has(String path) {
        return navigate(path).isPresent();
    }

    /**
     * Where a path is, if it was recorded.
     *
     * <p>Absent for a path that does not exist — and for a path that exists only inside a subtree
     * the parser never reached, which cannot happen, since the whole file is parsed.
     */
    public Optional<JsonLocation> location(String path) {
        return Optional.ofNullable(locations.get(path));
    }

    /**
     * The nearest recorded position at or above {@code path}.
     *
     * <p>For a complaint about something that is <i>missing</i>, there is no position for the
     * thing itself — so this walks up to the enclosing object, which is where the reader needs to
     * look anyway. Falls back to the root, so there is always an answer.
     */
    public JsonLocation nearestLocation(String path) {
        JsonLocation exact = locations.get(path);
        if (exact != null) {
            return exact;
        }
        String candidate = path;
        while (true) {
            int cut = Math.max(candidate.lastIndexOf('.'), candidate.lastIndexOf('['));
            if (cut <= 0) {
                break;
            }
            candidate = candidate.substring(0, cut);
            JsonLocation found = locations.get(candidate);
            if (found != null) {
                return new JsonLocation(found.line(), found.column(), path);
            }
        }
        JsonLocation rootLocation = locations.get("$");
        return rootLocation != null ? new JsonLocation(rootLocation.line(), rootLocation.column(), path)
                                    : new JsonLocation(1, 1, path);
    }

    /** How many positions were recorded — a rough measure of the file's size. Diagnostics. */
    public int locationCount() {
        return locations.size();
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private Optional<JsonElement> navigate(String path) {
        if (!path.startsWith("$")) {
            return Optional.empty();
        }
        JsonElement current = root;
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                if (!current.isJsonObject()) {
                    return Optional.empty();
                }
                JsonElement next = current.getAsJsonObject().get(path.substring(i + 1, end));
                if (next == null) {
                    return Optional.empty();
                }
                current = next;
                i = end;
            } else if (c == '[') {
                int end = path.indexOf(']', i);
                if (end < 0 || !current.isJsonArray()) {
                    return Optional.empty();
                }
                int index;
                try {
                    index = Integer.parseInt(path.substring(i + 1, end));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                JsonArray array = current.getAsJsonArray();
                if (index < 0 || index >= array.size()) {
                    return Optional.empty();
                }
                current = array.get(index);
                i = end + 1;
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    // ------------------------------------------------------------------
    // The parser
    // ------------------------------------------------------------------

    /**
     * A recursive-descent JSON reader that tracks line and column as it goes.
     *
     * <p>Tracking position directly, character by character, is the only way to be exact. Wrapping
     * the input in a counting {@code Reader} does not work: a parser fills a large buffer before
     * it starts tokenising, so the counter runs far ahead of the parse position and reports the
     * wrong line. Here, position advances only as this class consumes characters, so what it
     * reports is always where it actually is.
     */
    private static final class Parser {

        private final String name;
        private final String src;
        private final Map<String, JsonLocation> locations = new LinkedHashMap<>();
        private final Deque<String> path = new ArrayDeque<>();
        private int pos;
        private int line = 1;
        private int column = 1;
        private int depth;

        Parser(String name, String src) {
            this.name = name;
            this.src = src;
            this.path.push("$");
        }

        JsonDocument parse() throws JsonParseException {
            skipWhitespace();
            if (atEnd()) {
                throw fail("the file is empty");
            }
            JsonElement root = parseValue();
            skipWhitespace();
            if (!atEnd()) {
                throw fail("unexpected text after the end of the document");
            }
            return new JsonDocument(name, src, root, locations);
        }

        // ---------------- values ----------------

        private JsonElement parseValue() throws JsonParseException {
            if (++depth > MAX_DEPTH) {
                throw fail("nested more than " + MAX_DEPTH + " levels deep");
            }
            try {
                record(currentPath());
                char c = peek();
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> new JsonPrimitive(parseString());
                    case 't' -> parseKeyword("true", new JsonPrimitive(true));
                    case 'f' -> parseKeyword("false", new JsonPrimitive(false));
                    case 'n' -> parseKeyword("null", JsonNull.INSTANCE);
                    default -> {
                        if (c == '-' || (c >= '0' && c <= '9')) {
                            yield parseNumber();
                        }
                        throw fail("expected a value, found " + describe(c));
                    }
                };
            }
            finally {
                depth--;
            }
        }

        private JsonObject parseObject() throws JsonParseException {
            expect('{');
            JsonObject object = new JsonObject();
            skipWhitespace();
            if (peekIs('}')) {
                advance();
                return object;
            }
            while (true) {
                skipWhitespace();
                if (!peekIs('"')) {
                    throw fail(peekIs('}')
                            ? "trailing comma before '}' is not allowed"
                            : "expected a field name in quotes, found " + describe(peek()));
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                String parent = currentPath();
                path.push(parent + "." + key);
                JsonElement value;
                try {
                    value = parseValue();
                }
                finally {
                    path.pop();
                }
                object.add(key, value);

                skipWhitespace();
                if (peekIs(',')) {
                    advance();
                    continue;
                }
                if (peekIs('}')) {
                    advance();
                    return object;
                }
                throw fail("expected ',' or '}' after a field value, found " + describe(peek()));
            }
        }

        private JsonArray parseArray() throws JsonParseException {
            expect('[');
            JsonArray array = new JsonArray();
            skipWhitespace();
            if (peekIs(']')) {
                advance();
                return array;
            }
            int index = 0;
            while (true) {
                skipWhitespace();
                String parent = currentPath();
                path.push(parent + "[" + index + "]");
                try {
                    array.add(parseValue());
                }
                finally {
                    path.pop();
                }
                index++;

                skipWhitespace();
                if (peekIs(',')) {
                    advance();
                    continue;
                }
                if (peekIs(']')) {
                    advance();
                    return array;
                }
                throw fail("expected ',' or ']' after an array element, found " + describe(peek()));
            }
        }

        private String parseString() throws JsonParseException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw fail("unterminated string");
                }
                char c = advance();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\n') {
                    // Rewind past the newline so the caret lands on the line that contains the
                    // problem, not the one after it.
                    stepBack(c);
                    throw fail("unterminated string — a line break inside a string must be written \\n");
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw fail("raw control character in a string; escape it as \\u%04x".formatted((int) c));
                    }
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw fail("unterminated escape sequence");
                }
                char escape = advance();
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(parseUnicodeEscape());
                    default -> throw fail("unknown escape \\" + escape
                            + " — valid are \\\" \\\\ \\/ \\b \\f \\n \\r \\t \\uXXXX");
                }
            }
        }

        private char parseUnicodeEscape() throws JsonParseException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                if (atEnd()) {
                    throw fail("incomplete \\u escape; four hex digits are required");
                }
                char c = advance();
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    throw fail("'" + c + "' is not a hex digit; \\u escapes take four of them");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private JsonPrimitive parseNumber() throws JsonParseException {
            int start = pos;
            if (peekIs('-')) {
                advance();
            }
            if (atEnd() || !isDigit(peek())) {
                throw fail("expected a digit");
            }
            if (peekIs('0') && pos + 1 < src.length() && isDigit(src.charAt(pos + 1))) {
                throw fail("a number may not have a leading zero");
            }
            while (!atEnd() && isDigit(peek())) {
                advance();
            }

            boolean integral = true;
            if (!atEnd() && peek() == '.') {
                integral = false;
                advance();
                if (atEnd() || !isDigit(peek())) {
                    throw fail("expected a digit after the decimal point");
                }
                while (!atEnd() && isDigit(peek())) {
                    advance();
                }
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                integral = false;
                advance();
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    advance();
                }
                if (atEnd() || !isDigit(peek())) {
                    throw fail("expected a digit in the exponent");
                }
                while (!atEnd() && isDigit(peek())) {
                    advance();
                }
            }

            String raw = src.substring(start, pos);
            if (integral) {
                try {
                    return new JsonPrimitive(Long.parseLong(raw));
                }
                catch (NumberFormatException e) {
                    // Too big for a long. Falls through to a double, which loses precision but is
                    // better than refusing the file — and the validator can complain about the
                    // range if a particular field cares.
                    return new JsonPrimitive(Double.parseDouble(raw));
                }
            }
            return new JsonPrimitive(Double.parseDouble(raw));
        }

        private JsonElement parseKeyword(String word, JsonElement value) throws JsonParseException {
            for (int i = 0; i < word.length(); i++) {
                if (atEnd() || peek() != word.charAt(i)) {
                    throw fail("expected '" + word + "'");
                }
                advance();
            }
            return value;
        }

        // ---------------- positions ----------------

        private String currentPath() {
            // Each entry pushed onto the deque is already a COMPLETE path, not a segment, so the head
            // is the current path and there is nothing to join.
            //
            // This concatenated the whole deque at first, which was wrong in a way that looked like it
            // worked: joining "$", "$.chapterGroups" and "$.chapterGroups[0]" gives
            // "$$.chapterGroups$.chapterGroups[0]", so every recorded key but the root matched nothing.
            // Every reported problem then fell back to the root position and said line 1, column 1 --
            // plausible enough to read past, and useless.
            return path.peek();
        }

        private void record(String atPath) {
            locations.putIfAbsent(atPath, new JsonLocation(line, column, atPath));
        }

        // ---------------- characters ----------------

        private boolean atEnd() {
            return pos >= src.length();
        }

        private char peek() throws JsonParseException {
            if (atEnd()) {
                throw fail("unexpected end of file");
            }
            return src.charAt(pos);
        }

        private boolean peekIs(char c) {
            return !atEnd() && src.charAt(pos) == c;
        }

        private char advance() {
            char c = src.charAt(pos++);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            return c;
        }

        /** Undo one {@link #advance()}, for the case where the caret should land before it. */
        private void stepBack(char c) {
            pos--;
            if (c == '\n') {
                line--;
                // The column on the previous line is not knowable from here, but the caret is
                // only ever used for a one-character pointer, so the end of that line is right.
                column = 1;
            } else {
                column--;
            }
        }

        private void expect(char c) throws JsonParseException {
            skipWhitespace();
            if (atEnd() || src.charAt(pos) != c) {
                throw fail("expected '" + c + "'");
            }
            advance();
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    advance();
                } else {
                    break;
                }
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static String describe(char c) {
            if (c < 0x20) {
                return "a control character";
            }
            return "'" + c + "'";
        }

        private JsonParseException fail(String detail) {
            String text = "";
            int currentLine = 1;
            int lineStart = 0;
            for (int i = 0; i < src.length() && i <= pos; i++) {
                if (src.charAt(i) == '\n') {
                    currentLine++;
                    lineStart = i + 1;
                }
            }
            int lineEnd = src.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = src.length();
            }
            text = src.substring(lineStart, lineEnd).replace("\t", "    ");
            return new JsonParseException(detail, new JsonLocation(currentLine, column, currentPath()), text);
        }
    }
}
