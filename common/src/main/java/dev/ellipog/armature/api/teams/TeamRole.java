package dev.ellipog.armature.api.teams;

/**
 * What a member is allowed to do in a team.
 *
 * <p>Three roles, mapped onto a number so that comparisons read as what they are: "can X act on Y"
 * is an authority comparison rather than a set of pairwise cases. Adding a role later — a
 * read-only guest, say — means adding a constant and nothing else.
 *
 * <p>Deliberately coarse. A permission matrix is the wrong shape for a mod like this: a team here
 * exists so that four friends share saved progress, and the only real question is who may remove
 * somebody else.
 */
public enum TeamRole {

    /** Created it. May do anything, including disbanding. There is exactly one per team. */
    OWNER(3),

    /** May invite and remove ordinary members, and nothing else structural. */
    OFFICER(2),

    /** Along for the ride. May leave. */
    MEMBER(1);

    private final int authority;

    TeamRole(int authority) {
        this.authority = authority;
    }

    /** Higher is more powerful. For comparisons, not for display. */
    public int authority() {
        return authority;
    }

    /** Whether this role may act on somebody holding {@code other}. */
    public boolean outranks(TeamRole other) {
        return authority > other.authority;
    }

    /** Whether this role is at least as powerful as {@code other}. */
    public boolean isAtLeast(TeamRole other) {
        return authority >= other.authority;
    }
}
