package dev.ellipog.armature.api.teams;

/**
 * The numbers a team has to agree on, in one place.
 *
 * <h2>Why they are here rather than in the manager that enforces them</h2>
 *
 * <p>Because three readers need the same answer and they are in two repositories: the stored manager
 * refuses past the cap, a command says why before asking, and a panel writes {@code 3/8} in a header.
 * A constant copied into any of the three is a header that disagrees with the refusal — the class of
 * fault this codebase keeps recording, and the fix is the same every time: one expression, every
 * reader asks it.
 *
 * <p>{@link #MAX_MEMBERS} is the cap's <b>default</b> rather than its only value: a server may
 * configure its stored parties' limit in {@code config/armature/config.json}, and
 * {@code TeamSettings} owns that range, the clamp and the file. This constant is what a server that
 * has changed nothing uses, and what the documented numbers are stated against.
 *
 * <p>The limit applies to a source that enforces it. A foreign parties mod has its own limit or
 * none, and {@code TeamManager.memberLimit()} answers <b>zero</b> there, which means "unknown" to a
 * caller — a header showing a count without a cap rather than a made-up one.
 */
public final class TeamLimits {

    /**
     * How many players a stored party holds unless the server's settings say otherwise. The
     * {@code 8} in the panel's {@code Members · 3/8} on a server that changed nothing.
     */
    public static final int MAX_MEMBERS = 8;

    /** How long a party name may be, after trimming. Long enough for a sentence, short enough for a header. */
    public static final int MAX_NAME_LENGTH = 32;

    private TeamLimits() {
    }

    /**
     * Whether a name is one a party may be given.
     *
     * <p>Blank is refused — a party with no name displays as its owner, which reads as a bug — and so
     * is anything past {@link #MAX_NAME_LENGTH} or carrying a control character, which would travel
     * into a command argument, a payload line and a header at once. The rule lives here so the
     * command that reports "that name is too long" and the manager that refuses it cannot disagree
     * about what too long is.
     */
    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        return !trimmed.isEmpty()
                && trimmed.length() <= MAX_NAME_LENGTH
                && trimmed.chars().noneMatch(c -> c < 0x20);
    }

    /**
     * Whether a team of {@code members} members has no room for another.
     *
     * <p>{@code limit <= 0} means the source cannot say, and the answer is false: an unknown cap must
     * not refuse a join, because the source underneath will refuse it if it has one.
     */
    public static boolean isFull(int members, int limit) {
        return limit > 0 && members >= limit;
    }
}
