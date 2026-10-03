package dev.ellipog.armature.api.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything wrong with a set of data files, collected rather than thrown.
 *
 * <p>Collecting is the point. Stopping at the first error means an author fixes one typo per
 * reload, which on a data set of a hundred entries is an afternoon of reloading. Walking the whole
 * file and reporting every problem at once turns that into one pass.
 *
 * <p>Problems sort by file, then line, then column, so the output order matches the order a reader
 * scrolls through the files.
 */
public final class Problems {

    private final List<DataProblem> problems = new ArrayList<>();

    public void add(String file, JsonLocation location, DataProblem.Severity severity, String message) {
        problems.add(new DataProblem(file, location.line(), location.column(), location.path(), severity, message));
    }

    /** Adds a problem at {@code path}, or at the nearest enclosing position if that path is absent. */
    public void add(JsonDocument document, String path, DataProblem.Severity severity, String message) {
        add(document.name(), document.nearestLocation(path), severity, message);
    }

    public void error(JsonDocument document, String path, String message) {
        add(document, path, DataProblem.Severity.ERROR, message);
    }

    public void warn(JsonDocument document, String path, String message) {
        add(document, path, DataProblem.Severity.WARNING, message);
    }

    public void addAll(List<DataProblem> more) {
        problems.addAll(more);
    }

    /** Just the problems reported against one file, for callers that handle files separately. */
    public List<DataProblem> forFile(String file) {
        return problems.stream().filter(p -> p.file().equals(file)).toList();
    }

    /**
     * Whether one file has a fatal problem.
     *
     * <p>This is what lets a loader skip decoding a broken file — and therefore report one message
     * per mistake instead of a validator complaint followed by a codec complaint about the same
     * thing.
     */
    public boolean hasErrorsIn(String file) {
        return problems.stream()
                .anyMatch(p -> p.file().equals(file) && p.severity() == DataProblem.Severity.ERROR);
    }

    public boolean isEmpty() {
        return problems.isEmpty();
    }

    /** Whether anything fatal was reported. Warnings do not count — they must never block a load. */
    public boolean hasErrors() {
        return problems.stream().anyMatch(p -> p.severity() == DataProblem.Severity.ERROR);
    }

    public int errorCount() {
        return (int) problems.stream().filter(p -> p.severity() == DataProblem.Severity.ERROR).count();
    }

    public int warningCount() {
        return (int) problems.stream().filter(p -> p.severity() == DataProblem.Severity.WARNING).count();
    }

    public List<DataProblem> all() {
        List<DataProblem> sorted = new ArrayList<>(problems);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }
}
