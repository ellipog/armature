package dev.ellipog.armature.api.data;

/**
 * Where something is in a JSON file: line, column, and the path that got there.
 *
 * <p>Columns are 1-based and count characters, not bytes. A tab counts as one column, which is
 * what a text editor's "go to line:column" agrees with.
 *
 * @param line   1-based line number
 * @param column 1-based column number
 * @param path   a JSONPath-ish locator, e.g. {@code $.groups[0].items[3].title}
 */
public record JsonLocation(int line, int column, String path) {

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
