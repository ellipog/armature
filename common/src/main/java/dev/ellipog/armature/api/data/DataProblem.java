package dev.ellipog.armature.api.data;

/**
 * One complaint about a data file: where it is, and what is wrong with it.
 *
 * <p>{@link Severity#ERROR} means the file will not be loaded. {@link Severity#WARNING} means it
 * loaded, and something about it is worth telling the author — a field that will be ignored, a
 * position that stacks two entries on top of each other. The distinction matters because a
 * warning must never stop a working data set from loading.
 */
public record DataProblem(String file, int line, int column, String path, Severity severity, String message)
        implements Comparable<DataProblem> {

    public enum Severity {
        ERROR("error"),
        WARNING("warning");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One line, in the format a compiler uses and an editor can jump to, plus the second line a
     * misspelled field name produces — see {@code Checks.rejectUnknown} for the producer:
     *
     * <pre>{@code
     * entries/01.json:14:9: error: unknown field "titl" - did you mean "title"?
     *     valid fields here: description, icon, id, title
     * }</pre>
     */
    public String render() {
        return file + ":" + line + ":" + column + ": " + severity.label() + ": " + message;
    }

    public String renderWithPath() {
        return render() + "\n    at " + path;
    }

    @Override
    public int compareTo(DataProblem other) {
        int byFile = file.compareTo(other.file);
        if (byFile != 0) {
            return byFile;
        }
        int byLine = Integer.compare(line, other.line);
        if (byLine != 0) {
            return byLine;
        }
        int byColumn = Integer.compare(column, other.column);
        if (byColumn != 0) {
            return byColumn;
        }
        return message.compareTo(other.message);
    }
}
