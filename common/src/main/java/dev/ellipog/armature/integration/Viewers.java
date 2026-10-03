package dev.ellipog.armature.integration;

import dev.ellipog.armature.Constants;
import dev.ellipog.armature.api.ArmatureApi;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which recipe viewer, if more than one is installed, gets to draw Tasked's pages.
 *
 * <h2>One registers, and it is the first one in this list</h2>
 *
 * <p>A player can run two viewers at once, and two sets of the same quest entries is worse than one
 * set in the lesser viewer. So the rule is priority, not suppression: EMI carries the full
 * integration, JEI and REI carry the shared fallback, and each adapter asks
 * {@link #mayInstall} before it registers anything. The loser registers nothing at all -- not a
 * category with its recipes filtered out, which is the same duplicate with extra steps.
 *
 * <p>The order is the enum's declaration order, which is why the enum is declared in the order it is
 * and why the comment on it says so. Adding a viewer means adding it in the right place, and that is
 * a decision a reviewer can see in one line.
 *
 * <h2>Why {@code choose} takes a predicate</h2>
 *
 * <p>The same reason {@code TeamProviders.choose} takes its candidates: the decision is the feature,
 * and it cannot be tested by installing three mods. {@link #chosen()} is the two-line runtime path
 * over the same call, so the tested thing and the used thing are one function.
 */
public final class Viewers {

    /**
     * The viewers, in priority order. EMI is first-class: its API renders arbitrary pages, so a quest
     * can be a real page there. JEI and REI take the shared fallback -- the same item lookup as plain
     * rows -- which is a decision rather than a ranking, and is why there are only two tiers.
     */
    public enum Viewer {
        EMI("emi"),
        JEI("jei"),
        REI("roughlyenoughitems");

        private final String modId;

        Viewer(String modId) {
            this.modId = modId;
        }

        /** The mod id the loader is asked about. A constant on purpose: callers probe with it. */
        public String modId() {
            return modId;
        }
    }

    private Viewers() {
    }

    /** The first viewer in priority order the predicate accepts, if any. */
    public static Optional<Viewer> choose(Predicate<String> loaded) {
        for (Viewer viewer : Viewer.values()) {
            if (loaded.test(viewer.modId())) {
                return Optional.of(viewer);
            }
        }
        return Optional.empty();
    }

    /** The viewer that owns the integration on this client. Empty when none is installed. */
    public static Optional<Viewer> chosen() {
        return choose(Viewers::loaded);
    }

    /** Whether this viewer is the one that registered. Every adapter's first line is this. */
    public static boolean mayInstall(Viewer viewer) {
        return chosen().filter(chosen -> chosen == viewer).isPresent();
    }

    /**
     * The platform ask, with the absent-platform case read as "nothing is loaded".
     *
     * <p>{@code ArmatureApi.platform()} throws when nothing has installed it, and a test JVM is
     * exactly that. It is also the honest answer before a loader has reached Armature: nothing can be
     * loaded if the loader is not there yet.
     */
    private static boolean loaded(String modId) {
        try {
            return ArmatureApi.platform().isModLoaded(modId);
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("armature: cannot ask whether '{}' is loaded ({}); assuming not",
                    modId, e.toString());
            return false;
        }
    }
}
