package dev.ellipog.armature.api.teams;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * A group of players who share state — whatever the caller keys by a team. Game progress is the
 * obvious case; nothing here knows or cares which.
 *
 * <p>Immutable: every field is copied on construction, and the store hands out a fresh instance
 * after each change. That is what makes it safe to hold one for the duration of a tick without
 * wondering whether somebody joined halfway through.
 *
 * <h2>A player on their own is still in a team</h2>
 *
 * <p>{@link #solo} exists so that "playing alone" and "playing in a party" are the same code path.
 * Without it every lookup has two branches — the team's progress or the player's — and every
 * feature has to be written twice, then tested twice, then wrong once. A solo team is synthesised
 * on demand rather than stored, because it is derived entirely from the player's id: nothing about
 * it can change, so there is nothing to persist.
 *
 * <p>{@link #persistent} is how a caller tells the two apart when it matters — a solo team must not
 * appear in a team list, and there is nothing to disband.
 *
 * <h2>Invitations carry who and when</h2>
 *
 * <p>{@link #invites} is a map rather than a set because an invitation row shows its sender and its
 * age, and a set of ids can say neither. A source that cannot know the sender stores the owner and a
 * zero time — see {@link TeamInvite}, including why zero is the honest value rather than a made-up
 * one.
 *
 * <h2>The two rules live here</h2>
 *
 * <p>{@link #canActOn}, {@link #canInvite} and {@link #canTransfer} are on the record for the same
 * reason: three readers need each answer — the manager that enforces it, the command that wants to
 * say why, and the panel deciding whether to draw a control — and they are in two repositories. A
 * rule restated by any of them is a button the server refuses, so every reader asks the team.
 *
 * @param id        the team's id. For a solo team this is <b>the player's own UUID</b>, which is
 *                  what makes progress for a lone player addressable without storing anything
 * @param name      what the team calls itself. Empty for a solo team, which has no name to choose
 * @param owner     who created it. For a solo team, the player themselves
 * @param members   everyone in it, with their role
 * @param invites   players invited but not yet joined, each with who invited them and when
 * @param policy    what members may do beyond their role. {@link TeamPolicy#DEFAULT} for a solo
 *                  team, a source that cannot say, and a stored team from before the field existed
 * @param createdAt game time in ticks when it was formed. Zero for a solo team
 * @param persistent whether this is a real team that is stored, or a synthesised solo one
 */
public record Team(UUID id,
                   String name,
                   UUID owner,
                   Map<UUID, TeamRole> members,
                   Map<UUID, TeamInvite> invites,
                   TeamPolicy policy,
                   long createdAt,
                   boolean persistent) {

    public Team {
        members = Map.copyOf(members);
        invites = Map.copyOf(invites);
        // Null-tolerant rather than non-null: adapters and old call sites read a team out of
        // somebody else's model, where "no policy" is the common case, and a null check at every
        // call site would be a second place to get the default wrong.
        policy = policy == null ? TeamPolicy.DEFAULT : policy;
    }

    /**
     * The team of one player, synthesised.
     *
     * <p>The id is the player's own UUID — see the class comment for why that matters.
     */
    public static Team solo(UUID player) {
        return new Team(player, "", player, Map.of(player, TeamRole.OWNER), Map.of(),
                TeamPolicy.DEFAULT, 0L, false);
    }

    /** A brand new real team, with one owner and nobody else. */
    public static Team created(UUID id, String name, UUID owner, long now) {
        return new Team(id, name, owner, Map.of(owner, TeamRole.OWNER), Map.of(),
                TeamPolicy.DEFAULT, now, true);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public boolean isInvited(UUID player) {
        return invites.containsKey(player);
    }

    /** Whether this player owns the team. The question {@link #canActOn} and the panel both start from. */
    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    public Optional<TeamRole> roleOf(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    /** Defaults to {@link TeamRole#MEMBER} for a non-member, so a permission check reads simply. */
    public TeamRole roleOrMember(UUID player) {
        return members.getOrDefault(player, TeamRole.MEMBER);
    }

    /** The invitation this player holds, if any. Carries the sender and the time. */
    public Optional<TeamInvite> inviteOf(UUID player) {
        return Optional.ofNullable(invites.get(player));
    }

    /** Everyone invited but not yet joined. */
    public Set<UUID> invitedIds() {
        return invites.keySet();
    }

    /**
     * Whether {@code actor} may remove {@code target} from this team.
     *
     * <h2>Why this is a method on the team rather than logic inside a manager</h2>
     *
     * <p>Because <b>three</b> things need the same answer and they are in two different repos. The
     * stored manager enforces it when a kick is asked for; a command has to know it before it asks,
     * so it can say why rather than reporting a bare false; and a UI has to know it while drawing,
     * because the alternative is a Remove button that does nothing when pressed.
     *
     * <p>Until this existed the rule was written once, in {@code StoredTeamManager.kick}, and every
     * other reader would have had to restate it. That is the shape of fault this codebase has recorded
     * more than once — two expressions that agree on the day they are written — and the symptom here
     * would be specific and bad: a button drawn for everybody, and a server that refuses some of them.
     *
     * <p>So it is here, on the record that holds both roles, and every caller asks.
     *
     * <h2>The four conditions, each doing something</h2>
     *
     * <ul>
     *   <li><b>Not yourself.</b> Removing yourself is {@link TeamManager#leave}, which is a different
     *       act with different consequences — a leave keeps your own progress, and there is no reason
     *       for a kick to be able to do it. Without this, an owner could kick themselves and leave
     *       ownership to be resolved by the fallback in {@link #withoutMember}, which is a rule about
     *       a team somebody left rather than one about a team its owner abandoned.</li>
     *   <li><b>Actor is a member.</b> {@code roleOrMember} answers MEMBER for a non-member, which is
     *       the right default for a permission check and means a stranger would otherwise pass the
     *       authority test against a member of equal rank. A non-member has no authority here at all,
     *       and the check has to be explicit because the default is deliberately forgiving.</li>
     *   <li><b>Target is a member.</b> Nothing to remove, and a false here is a better answer than a
     *       manager resolving "who is this" twice.</li>
     *   <li><b>Authority.</b> Strictly higher, so an officer cannot remove another officer. That is
     *       the role model's whole content — see {@link TeamRole} on why there are three and not more.</li>
     * </ul>
     */
    public boolean canActOn(UUID actor, UUID target) {
        if (actor == null || target == null || actor.equals(target)) {
            return false;
        }
        if (!isMember(actor) || !isMember(target)) {
            return false;
        }
        return roleOrMember(actor).outranks(roleOrMember(target));
    }

    /**
     * Whether {@code actor} may send an invitation.
     *
     * <p>A member, and either ranked at least {@link TeamRole#OFFICER} or covered by
     * {@link TeamPolicy#membersCanInvite}. Officers are included unconditionally so that the policy
     * switch reads as what it says — "allow <i>members</i> to invite" — rather than as a second
     * rank table that happens to agree with the first today.
     *
     * <p>Three readers again: {@code StoredTeamManager.invite}, the command that must explain a
     * refusal, and the panel that decides whether to offer an Invite button at all. The rule being
     * here is what keeps them one answer.
     */
    public boolean canInvite(UUID actor) {
        TeamRole role = members.get(actor);
        if (role == null) {
            return false;
        }
        return role.isAtLeast(TeamRole.OFFICER) || policy.membersCanInvite();
    }

    /**
     * Whether {@code actor} may hand ownership to {@code target}.
     *
     * <p>The owner, handing it to a different member. Both halves are load-bearing: a non-owner
     * transferring is a coup, and a transfer to oneself is not a transfer. The panel draws the
     * control from this answer and {@code StoredTeamManager.transferOwnership} enforces it, so the
     * two cannot disagree about when the flow is offered.
     */
    public boolean canTransfer(UUID actor, UUID target) {
        if (actor == null || target == null || actor.equals(target)) {
            return false;
        }
        return isOwner(actor) && isMember(target);
    }

    public Set<UUID> memberIds() {
        return members.keySet();
    }

    public int size() {
        return members.size();
    }

    /** What to show for this team. {@code unresolved} stands in for a player who is not known yet — a team of one is named after its member. */
    public String displayName(Function<UUID, String> unresolved) {
        if (!name.isEmpty()) {
            return name;
        }
        if (members.isEmpty()) {
            return unresolved.apply(id);
        }
        return unresolved.apply(members.keySet().iterator().next());
    }

    /** A copy with one more member. Returns this team unchanged if they are already in it. */
    public Team withMember(UUID player, TeamRole role) {
        if (members.containsKey(player)) {
            return this;
        }
        Map<UUID, TeamRole> next = new java.util.LinkedHashMap<>(members);
        next.put(player, role);
        Map<UUID, TeamInvite> nextInvites = new java.util.LinkedHashMap<>(invites);
        nextInvites.remove(player);
        return new Team(id, name, owner, next, nextInvites, policy, createdAt, persistent);
    }

    /** A copy without {@code player}, and without them as owner if they were. */
    public Team withoutMember(UUID player) {
        if (!members.containsKey(player)) {
            return this;
        }
        Map<UUID, TeamRole> next = new java.util.LinkedHashMap<>(members);
        next.remove(player);
        UUID nextOwner = owner.equals(player)
                // Ownership passes to the most senior remaining member, so a team does not become
                // unbootable when its creator leaves. Highest authority wins; a tie is broken by
                // UUID, which is arbitrary but stable -- and stability is what matters, since an
                // arbitrary but stable choice is reproducible when someone reports a bug.
                //
                // This is the fallback, not the intended path: the panel's owner-leave flow picks a
                // successor and calls transferOwnership before leaving. A group that leaves through
                // a command without picking one still gets a usable team rather than an ownerless
                // one, which is what this is for.
                ? next.entrySet().stream()
                        .max(java.util.Comparator
                                .<Map.Entry<UUID, TeamRole>>comparingInt(entry -> entry.getValue().authority())
                                .thenComparing(entry -> entry.getKey().toString()))
                        .map(Map.Entry::getKey)
                        .orElse(owner)
                : owner;
        return new Team(id, name, nextOwner, next, invites, policy, createdAt, persistent);
    }

    /**
     * A copy with an invitation for {@code player}, sent by {@code inviter} at {@code at}.
     *
     * <p>Returns this team unchanged if they are already a member or already invited: a second
     * invitation would only replace the sender and time of one already pending, and the first is the
     * one the invitee has been looking at.
     */
    public Team withInvite(UUID player, UUID inviter, long at) {
        if (invites.containsKey(player) || members.containsKey(player)) {
            return this;
        }
        Map<UUID, TeamInvite> next = new java.util.LinkedHashMap<>(invites);
        next.put(player, new TeamInvite(inviter, at));
        return new Team(id, name, owner, members, next, policy, createdAt, persistent);
    }

    public Team withoutInvite(UUID player) {
        if (!invites.containsKey(player)) {
            return this;
        }
        Map<UUID, TeamInvite> next = new java.util.LinkedHashMap<>(invites);
        next.remove(player);
        return new Team(id, name, owner, members, next, policy, createdAt, persistent);
    }

    public Team withName(String name) {
        return new Team(id, name, owner, members, invites, policy, createdAt, persistent);
    }

    public Team withPolicy(TeamPolicy policy) {
        return new Team(id, name, owner, members, invites, policy, createdAt, persistent);
    }

    /**
     * A copy with {@code newOwner} owning it and the previous owner demoted to {@code previousRole}.
     *
     * <p>A parameter for the demotion rather than a constant, because the two flows that transfer
     * want different things: the panel's successor picker transfers to somebody who is staying and
     * the old owner then leaves, while a transfer made without leaving should keep the old owner
     * useful. The caller decides; this method refuses to guess.
     *
     * <p>Returns this team unchanged when the target is not a member or already owns it, so a
     * caller cannot produce a team whose owner is not in it.
     */
    public Team withOwner(UUID newOwner, TeamRole previousRole) {
        if (!members.containsKey(newOwner) || newOwner.equals(owner)) {
            return this;
        }
        Map<UUID, TeamRole> next = new java.util.LinkedHashMap<>(members);
        next.put(newOwner, TeamRole.OWNER);
        next.put(owner, previousRole);
        return new Team(id, name, newOwner, next, invites, policy, createdAt, persistent);
    }
}
