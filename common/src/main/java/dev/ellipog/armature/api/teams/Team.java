package dev.ellipog.armature.api.teams;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * A group of players who share state — in Tasked's case, quest progress.
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

    /** A team of one that is stored, as opposed to the synthesised kind. */
    public boolean isSolo() {
        return members.size() <= 1;
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
