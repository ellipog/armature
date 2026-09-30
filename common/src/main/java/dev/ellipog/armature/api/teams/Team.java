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
 * @param id        the team's id. For a solo team this is <b>the player's own UUID</b>, which is
 *                  what makes progress for a lone player addressable without storing anything
 * @param name      what the team calls itself. Empty for a solo team, which has no name to choose
 * @param owner     who created it. For a solo team, the player themselves
 * @param members   everyone in it, with their role
 * @param invites   players invited but not yet joined
 * @param createdAt game time in ticks when it was formed. Zero for a solo team
 * @param persistent whether this is a real team that is stored, or a synthesised solo one
 */
public record Team(UUID id,
                   String name,
                   UUID owner,
                   Map<UUID, TeamRole> members,
                   Set<UUID> invites,
                   long createdAt,
                   boolean persistent) {

    public Team {
        members = Map.copyOf(members);
        invites = Set.copyOf(invites);
    }

    /**
     * The team of one player, synthesised.
     *
     * <p>The id is the player's own UUID — see the class comment for why that matters.
     */
    public static Team solo(UUID player) {
        return new Team(player, "", player, Map.of(player, TeamRole.OWNER), Set.of(), 0L, false);
    }

    /** A brand new real team, with one owner and nobody else. */
    public static Team created(UUID id, String name, UUID owner, long now) {
        return new Team(id, name, owner, Map.of(owner, TeamRole.OWNER), Set.of(), now, true);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public boolean isInvited(UUID player) {
        return invites.contains(player);
    }

    public Optional<TeamRole> roleOf(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    /** Defaults to {@link TeamRole#MEMBER} for a non-member, so a permission check reads simply. */
    public TeamRole roleOrMember(UUID player) {
        return members.getOrDefault(player, TeamRole.MEMBER);
    }

    /**
     * Whether {@code actor} may remove {@code target} from this team.
     *
     * <h2>Why this is a method on the team rather than logic inside a manager</h2>
     *
     * <p>Because <b>three</b> things need the same answer and they are in two different repos. The
     * stored manager enforces it when a kick is asked for; a command has to know it before it asks, so
     * it can say why rather than reporting a bare false; and a UI has to know it while drawing, because
     * the alternative is a Remove button that does nothing when pressed.
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
        Set<UUID> nextInvites = new java.util.LinkedHashSet<>(invites);
        nextInvites.remove(player);
        return new Team(id, name, owner, next, nextInvites, createdAt, persistent);
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
                ? next.entrySet().stream()
                        .max(java.util.Comparator
                                .<Map.Entry<UUID, TeamRole>>comparingInt(entry -> entry.getValue().authority())
                                .thenComparing(entry -> entry.getKey().toString()))
                        .map(Map.Entry::getKey)
                        .orElse(owner)
                : owner;
        return new Team(id, name, nextOwner, next, invites, createdAt, persistent);
    }

    public Team withInvite(UUID player) {
        if (invites.contains(player) || members.containsKey(player)) {
            return this;
        }
        Set<UUID> next = new java.util.LinkedHashSet<>(invites);
        next.add(player);
        return new Team(id, name, owner, members, next, createdAt, persistent);
    }

    public Team withoutInvite(UUID player) {
        if (!invites.contains(player)) {
            return this;
        }
        Set<UUID> next = new java.util.LinkedHashSet<>(invites);
        next.remove(player);
        return new Team(id, name, owner, members, next, createdAt, persistent);
    }

    public Team withName(String name) {
        return new Team(id, name, owner, members, invites, createdAt, persistent);
    }
}
