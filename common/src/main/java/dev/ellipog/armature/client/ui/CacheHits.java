package dev.ellipog.armature.client.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether the toolkit's caches are earning their keep: hits and misses per cache, drained once a second.
 *
 * <h2>The question this exists to answer, and why nothing could answer it</h2>
 *
 * <p>This codebase has five memo tables in the frame's path — {@code Plans.SHAPES} and
 * {@code ArmatureTheme.SURFACES} (a shape's rectangles), {@code Shapes.Tables} (a shape's rows),
 * {@code CachedMeasure} (a string's width) and {@code LineArt.FILLS}/{@code ARROWS} (a route's ink) — and
 * every one of them was added on an argument rather than on a number. The arguments are good ones: the work
 * they avoid is real, it is per node per frame, and each has a test proving it is <i>correct</i>.
 *
 * <p>What none of them had is a way to tell <b>whether it is being consulted</b>. A cache with a complete
 * key and a stable identity hits; one whose key includes something recomputed per call misses every time
 * while still producing correct answers — and the two are indistinguishable from the outside, because a miss
 * and a hit return the same value. That is not hypothetical here: it is exactly the fault
 * {@code Shapes.cached} had, where {@code inner()} returned a fresh wrapper per call so the plan tables were
 * built and then unreachable, and nothing anywhere could see it. A picture cannot show it either — the pixels
 * are right.
 *
 * <p>So the ratio is counted. A hit rate near one says the key is doing its job; a rate near zero says the
 * cache is a cost with no benefit, which is worth knowing before anything is built on top of it.
 *
 * <h2>Drained, not read</h2>
 *
 * <p>The numbers are a <b>rate over a window</b>, so they are consumed by whoever reports them — the same
 * contract as {@code IconPlan.drain} and {@code LineArt.drainWalked}. A cumulative total could not answer
 * "is this hitting <i>now</i>", which is the only form of the question a frame's cache can be asked.
 *
 * <h2>Off, and what it costs when off</h2>
 *
 * <p>One static-boolean branch per call, checked <b>before</b> anything is looked up or allocated. The call
 * sites are on the frame path, so that matters: an instrument that cost a map lookup per cache probe would
 * be measuring itself. With the counter off, a probe is one branch and the cache's own lookup, which is what
 * the probe was going to do anyway.
 *
 * <h2>The tally, and the thread it is written on</h2>
 *
 * <p>A plain map, because every caller is the client's render thread: these are frame-path caches, and there
 * is no version of "the frame was drawn somewhere else" that would not also be a bug worth a crash. The
 * per-call boxing a {@code Map.merge} would make is avoided as well — the value is an {@code int[2]}, so a
 * probe is a lookup and an add.
 */
public final class CacheHits {

    /** The counter's own switch. Off unless a dev mode or an operator asks, like every instrument here. */
    private static volatile boolean on;

    /**
     * Hits and misses since the last drain, by the cache's own name.
     *
     * <p>Insertion-ordered, so a report reads in the order the caches were first asked in a second rather
     * than in whatever order a hash happens to produce. Two caches that alternate are a line that shuffles
     * frame to frame, which is a line nobody can compare against the last one.
     */
    private static final Map<String, int[]> TALLY = new LinkedHashMap<>();

    private CacheHits() {
    }

    /** Whether anything is being counted. */
    public static boolean on() {
        return on;
    }

    /**
     * Turns the counter on or off.
     *
     * <p>Not persisted: this is a diagnostic for a question about a running client, and a switch that
     * outlived the session would be a permanent cost for a temporary question.
     */
    public static void set(boolean value) {
        on = value;
        if (!value) {
            TALLY.clear();
        }
    }

    /**
     * Records one probe of a cache.
     *
     * <p>One method taking the answer rather than a {@code hit()} and a {@code miss()}, so a call site
     * cannot report one and forget the other — a cache that counted only its hits would read as a perfect
     * cache, which is the failure mode this instrument exists to make impossible.
     *
     * @param cache the cache's own name, a constant at the call site rather than a formatted string — a name
     *              built per probe would allocate on the path the probe is measuring
     * @param hit   whether the probe was answered from the cache
     */
    public static void asked(String cache, boolean hit) {
        if (!on) {
            return;
        }
        TALLY.computeIfAbsent(cache, name -> new int[2])[hit ? 0 : 1]++;
    }

    /**
     * The tally since the last drain, as {@code name -> {hits, misses}}, and a reset.
     *
     * <p>Drained rather than read, because the answer is a rate over a window and the window is the
     * reporter's. A copy rather than the live map, for the reason the drain implies: the live tally is
     * cleared by the next drain, so a caller handed the map itself would watch its subject vanish.
     */
    public static Map<String, int[]> drain() {
        Map<String, int[]> taken = new LinkedHashMap<>();
        TALLY.forEach((cache, counts) -> taken.put(cache, new int[] {counts[0], counts[1]}));
        TALLY.clear();
        return taken;
    }

    /**
     * The tally as one line: {@code name hits/misses, …}, with the hit rate for each.
     *
     * <p>A percentage rather than a bare ratio, because the decision the number is for is "is this worth
     * keeping" and `97%` answers it at a glance where `1940/2000` does not. The counts are kept beside it so
     * a rate over three probes is not read as a rate over three thousand.
     *
     * <p>Empty for an empty tally, so a caller can decide not to log at all rather than log a blank line.
     */
    public static String describe(Map<String, int[]> tally) {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, int[]> each : tally.entrySet()) {
            int hits = each.getValue()[0];
            int misses = each.getValue()[1];
            int total = hits + misses;
            if (total == 0) {
                continue;
            }
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(each.getKey())
                    .append(' ').append(hits).append('/').append(misses)
                    .append(" (").append(Math.round(100.0 * hits / total)).append("%)");
        }
        return line.toString();
    }

    /** Forgets the tally. For a test, so one test's probes cannot report in another's. */
    public static void reset() {
        TALLY.clear();
    }

    /** What the tally holds right now, by cache — for a test. See {@link #drain} for why it is a copy. */
    public static Map<String, int[]> tally() {
        Map<String, int[]> snapshot = new LinkedHashMap<>();
        TALLY.forEach((cache, counts) -> snapshot.put(cache, new int[] {counts[0], counts[1]}));
        return snapshot;
    }
}
