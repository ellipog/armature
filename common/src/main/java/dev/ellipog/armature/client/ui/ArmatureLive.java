package dev.ellipog.armature.client.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * The data a screen watches: named counters, registered once by the mod that owns them.
 *
 * <h2>The problem this is the whole of</h2>
 *
 * <p>A screen is built once and its widgets are placed once, while the things it describes — a party's
 * members, a quest's progress, whether a reward is waiting — change underneath it. So each panel grew
 * its own way of noticing: a revision field, a comparison in the frame loop, a rebuild. That is fine
 * for one panel and it is the same code written again for the second, and the third one is the one
 * somebody forgets — which arrives as <i>"I have to close and reopen it for the change to show"</i>,
 * weeks later, on a panel nobody was thinking about.
 *
 * <p>So the noticing moves here, and a mod says what changes rather than what to do about it:
 *
 * <pre>{@code
 * ArmatureLive.watch("party", partyCache::revision);
 * ArmatureLive.watch("progress", progressCache::revision);
 * }</pre>
 *
 * <p>{@link ArmatureScreen} then rebuilds itself when any of them moves, so a panel written by
 * extending that class updates for every source in this list — including the ones registered after it
 * was written, and including a source belonging to a mod the screen's own author has never heard of.
 *
 * <h2>What a source has to be</h2>
 *
 * <p>A counter that changes when the data does, and <b>never decreases</b>. Only equality is ever asked
 * of it, so the absolute value means nothing; what matters is that two different states never share a
 * value. A counter incremented on every arriving message is the shape that works — see the caches in
 * either mod for why moving on the message rather than on the contents is the right reading.
 *
 * <p>The same name registered twice replaces what that name reads. That is deliberate: a mod that
 * registers from both loaders' client setup paths would otherwise watch the same cache twice, and the
 * second registration is far more likely to be a correction than a second source.
 */
public final class ArmatureLive {

    private record Source(String name, LongSupplier revision) {
    }

    /**
     * Copy-on-write rather than a map: registration happens a handful of times while a client starts,
     * reading happens once per frame for every open screen, and a reader that could see a half-built
     * registry would be a screen that misses a change.
     */
    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private ArmatureLive() {
    }

    /** Registers one thing a screen should watch. See the class note for what a source has to be. */
    public static void watch(String name, LongSupplier revision) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(revision, "revision");
        SOURCES.removeIf(source -> source.name().equals(name));
        SOURCES.add(new Source(name, revision));
    }

    /**
     * Every watched value, by name, as it stands now.
     *
     * <p>Called once per frame per open screen, so it is a walk over a handful of suppliers and no
     * allocation beyond the map itself — which is the price of not asking every screen to remember a
     * list of its own.
     */
    public static Map<String, Long> revisions() {
        Map<String, Long> now = new LinkedHashMap<>();
        for (Source source : SOURCES) {
            now.put(source.name(), source.revision().getAsLong());
        }
        return Map.copyOf(now);
    }

    /** The names being watched, for a log line that has to say what a screen is looking at. */
    public static List<String> watched() {
        return SOURCES.stream().map(Source::name).toList();
    }
}
