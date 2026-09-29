package dev.ellipog.armature.api.data;

/**
 * A JSON file that could not be parsed at all, with the position of the problem.
 *
 * <p>Separate from {@link DataProblem} because it is a different kind of failure: nothing was
 * parsed, so there is no tree to keep validating. A caller catches this, reports it, and moves
 * on to the next file rather than abandoning the whole load.
 *
 * <p>The message is built for a human to read in a log: it includes the offending line and a
 * caret under the exact column. That costs a couple of lines of code and saves the reader from
 * counting characters.
 */
public final class JsonParseException extends Exception {

    private final JsonLocation location;
    private final String detail;

    public JsonParseException(String detail, JsonLocation location, String offendingLineText) {
        super(location.path() + " (" + location + "): " + detail + "\n"
                + "    " + offendingLineText + "\n"
                + "    " + " ".repeat(Math.max(0, location.column() - 1)) + "^");
        this.location = location;
        this.detail = detail;
    }

    public JsonLocation location() {
        return location;
    }

    /** The message without the source excerpt — for one-line summaries. */
    public String detail() {
        return detail;
    }
}
