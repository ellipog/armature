package dev.ellipog.armature.api.teams;

/**
 * What a party's members may do beyond what their role says.
 *
 * <h2>Two switches, deliberately not more</h2>
 *
 * <p>A permission matrix is the wrong shape for a party four friends share — {@link TeamRole}
 * already says so, and this record does not reopen the question. These are the two decisions a party
 * actually makes after forming:
 *
 * <ul>
 *   <li><b>{@code openJoin}</b> — anyone on the server may join from their own screen without an
 *       invitation. Off means invite-only, which is what every party was before this field
 *       existed.</li>
 *   <li><b>{@code membersCanInvite}</b> — an ordinary {@link TeamRole#MEMBER} may send invitations.
 *       Off means the owner and officers only.</li>
 * </ul>
 *
 * <h2>What the default is, and why</h2>
 *
 * <p>{@link #DEFAULT} is invite-only with member invitations <b>on</b>: the panel's two toggles
 * start in the state its spec describes ("Allow members to invite — defaults to On"), and a party
 * that says nothing behaves that way. A stored party from before this record existed loads with the
 * default — see {@code TeamStore}, whose format version 1 has no policy to read.
 *
 * <p>{@link #DEFAULT} is also what an adapter reports for a foreign party: FTB Teams and Open
 * Parties and Claims keep their own rules, and this record is not consulted for them. A caller that
 * wants to know whether a source can be <i>asked</i> to change any of this asks
 * {@code TeamManager.supports(TeamFeature.POLICY)}.
 *
 * @param openJoin         whether a player may join without an invitation
 * @param membersCanInvite whether an ordinary member may invite
 */
public record TeamPolicy(boolean openJoin, boolean membersCanInvite) {

    /** Invite-only, members may invite. See the class note for why this is the default. */
    public static final TeamPolicy DEFAULT = new TeamPolicy(false, true);

    /** Open to join, members may invite. What the panel's two toggles both on means. */
    public static final TeamPolicy OPEN = new TeamPolicy(true, true);

    public TeamPolicy withOpenJoin(boolean value) {
        return new TeamPolicy(value, membersCanInvite);
    }

    public TeamPolicy withMembersCanInvite(boolean value) {
        return new TeamPolicy(openJoin, value);
    }
}
