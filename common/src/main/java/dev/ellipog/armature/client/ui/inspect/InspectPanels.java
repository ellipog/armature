package dev.ellipog.armature.client.ui.inspect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A registry of per-type panels, and the one fallback for a type nobody registered.
 *
 * <h2>Why a registry, and why the fallback is part of it</h2>
 *
 * <p>Because a property panel over <i>other people's</i> types is a property panel over a set it cannot
 * enumerate: an addon can arrive with a kind this build has never heard of, and the panel still has to
 * answer. The answers are two, and both are the registry's -- a registered type gets the panel written
 * for it, and an unregistered one gets the fallback, whose whole job is to keep the type <b>visible and
 * editable as what it actually is</b> rather than quietly rendering nothing, rendering a guess, or
 * dropping it on the way out. A fallback that showed a summary of fields it did not understand would be
 * a summary that lies by omission; showing the value itself cannot.
 *
 * <p>The fallback is supplied rather than built in: this class is the mechanism, and what "showing the
 * value itself" means is the caller's format. A caller with no fallback registered is a caller that
 * cannot describe an unknown type at all, and gets rows that say so rather than rows that pretend.
 *
 * @param <V> the value a panel describes -- the same object the caller's rows are read from
 */
public final class InspectPanels<V> {

    /** Builds one type's rows. {@code value} is never null; the type is the key it was registered under. */
    @FunctionalInterface
    public interface Panel<V> {
        List<InspectRow> rows(String type, V value);
    }

    private final Map<String, Panel<V>> panels = new LinkedHashMap<>();
    private BiFunction<String, V, List<InspectRow>> fallback;

    /** Registers the panel for one type. Re-registering a type replaces its panel, deliberately. */
    public InspectPanels<V> register(String type, Panel<V> panel) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(panel, "panel");
        panels.put(type, panel);
        return this;
    }

    /**
     * What an unregistered type is shown as. Null means "never" -- an unknown type then gets the rows
     * that say nothing could be built, which is still an answer.
     */
    public InspectPanels<V> fallback(BiFunction<String, V, List<InspectRow>> fallback) {
        this.fallback = fallback;
        return this;
    }

    /** Whether a panel was written for this type. The fallback is not a panel, and does not count. */
    public boolean known(String type) {
        return type != null && panels.containsKey(type);
    }

    /**
     * The rows for one value of one type: the type's own panel, or the fallback, or the rows that say
     * there is nothing. Never null, never empty of an answer.
     */
    public List<InspectRow> rowsFor(String type, V value) {
        Objects.requireNonNull(value, "value");
        Panel<V> panel = type == null ? null : panels.get(type);
        if (panel != null) {
            return List.copyOf(panel.rows(type, value));
        }
        if (fallback != null) {
            List<InspectRow> rows = fallback.apply(type, value);
            return rows == null ? List.of() : List.copyOf(rows);
        }
        String name = type == null ? "this value" : "\"" + type + "\"";
        return List.of(InspectRow.warning("inspect:unknown",
                "No panel for " + name + " -- the value is shown as it is stored"));
    }
}
