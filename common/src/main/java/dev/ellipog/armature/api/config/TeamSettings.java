package dev.ellipog.armature.api.config;

import dev.ellipog.armature.api.teams.TeamLimits;
import dev.ellipog.armature.api.teams.TeamPolicy;

/**
 * The teams settings a server owner can change.
 *
 * <h2>What each one decides, and what it does not</h2>
 *
 * <ul>
 *   <li>{@link #maxMembers()} — how many players a stored party holds. Enforced where it always was:
 *       the invitation is refused past the cap, an answer to an old invitation is refused when the
 *       party filled in the meantime, and a public join is refused when there is no room. It applies
 *       to every stored party immediately, including ones that already exist.</li>
 *   <li>{@link #newPartyMembersCanInvite()} and {@link #newPartyOpenJoin()} — what a <b>newly
 *       created</b> party's {@link TeamPolicy} starts as. They are stamped on at creation and never
 *       touch an existing party: a switch a party has already set is the party's, not the server's.</li>
 * </ul>
 *
 * <h2>It applies to Armature's own parties, and only those</h2>
 *
 * <p>A server whose parties come from another mod has that mod's limits and its own defaults; this
 * record is not consulted for them. That is the same containment the rest of the teams API keeps —
 * see {@code TeamProviders}.
 *
 * @param maxMembers              how many members a stored party may hold, {@value #MIN_MAX_MEMBERS}
 *                                to {@value #MAX_MAX_MEMBERS}
 * @param newPartyMembersCanInvite whether a new party lets ordinary members invite
 * @param newPartyOpenJoin         whether a new party is joinable without an invitation
 */
public record TeamSettings(int maxMembers, boolean newPartyMembersCanInvite, boolean newPartyOpenJoin) {

    /** The smallest a party may be configured to be. One is not a party; see {@code Team.solo}. */
    public static final int MIN_MAX_MEMBERS = 2;

    /** The largest, because a roster is a list a person reads and the panel draws one row each. */
    public static final int MAX_MAX_MEMBERS = 64;

    /**
     * What a server with no config file uses.
     *
     * <p>Member invitations on and the party closed — the same defaults the panel's two switches show,
     * and the same policy a version 1 team store migrates to. {@link TeamLimits#MAX_MEMBERS} is the
     * cap, so the documented numbers and the shipped behaviour are one thing.
     */
    public static final TeamSettings DEFAULT =
            new TeamSettings(TeamLimits.MAX_MEMBERS, true, false);

    /** The policy a newly created party is stamped with. */
    public TeamPolicy defaultPolicy() {
        return new TeamPolicy(newPartyOpenJoin, newPartyMembersCanInvite);
    }

    /**
     * {@code wanted} brought inside {@link #MIN_MAX_MEMBERS} to {@link #MAX_MAX_MEMBERS}.
     *
     * <p>Here rather than in the file reader, because it is a statement about what a valid setting
     * is: a config screen, a command and a hand-edited file all ask the same question, and a bound
     * copied into any of them is a header that disagrees with the refusal — the class of fault this
     * codebase keeps recording, fixed the same way every time.
     *
     * <p>It clamps rather than refuses. A server owner who writes {@code 200} wants a party larger
     * than anybody has ever needed, and handing them 64 with a log line is more useful than refusing
     * the whole file over one number; see {@code ArmatureConfig}, which says so out loud when it
     * happens.
     */
    public static int clampMaxMembers(int wanted) {
        return Math.max(MIN_MAX_MEMBERS, Math.min(MAX_MAX_MEMBERS, wanted));
    }
}
