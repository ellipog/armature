package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The stored teams of one world.
 *
 * <h2>It is the {@link SavedData}, not a wrapper around one</h2>
 *
 * <p>An earlier version had this hold the teams and a nested {@code SavedData} class wrap it for
 * Minecraft's benefit. That is one layer of indirection that earned nothing: every call from
 * {@link TeamManager} had to go through the wrapper to reach the same map, and the wrapper's only
 * job was to implement {@code save}. Extending {@code SavedData} directly removes it.
 *
 * <h2>Why UUIDs are written as strings</h2>
 *
 * <p>{@code CompoundTag} has {@code putUUID} and {@code getUUID}, and they are the right call in
 * most code. Here they are not, for two reasons. A UUID in NBT is stored as an int array, so a file
 * someone opens to debug is unreadable — a string is at least greppable, and team data is exactly
 * the sort of thing you end up grepping. And {@code getUUID} returns null for an absent key rather
 * than failing loudly, so a missing field becomes a NullPointerException somewhere else entirely.
 * Strings are slower, duller, and never do that.
 *
 * <h2>Retired ids</h2>
 *
 * <p>A disbanded team's id is kept, so it is never reused. Without that, a new team inheriting an
 * old id would inherit anything keyed by it — a fresh party starting with whatever a previous party
 * had already done. Ids are random UUIDs so a collision is essentially impossible, but the check
 * costs a few bytes.
 */
public final class TeamStore extends SavedData {

    private static final String DATA_NAME = "armature_teams";

    private final Map<UUID, Team> teams = new LinkedHashMap<>();

    /** Teams that were disbanded, so an id is never reused with old progress attached. */
    private final Set<UUID> retired = new LinkedHashSet<>();

    /** The store for this server. On the overworld, so teams are global rather than per-dimension. */
    public static TeamStore of(MinecraftServer server) {
        // Three arguments, not two. SavedData.Factory in 1.21.1 takes the constructor, the loader
        // and a nullable DataFixTypes -- verified with javap against the actual artefact rather
        // than guessed (.utils/find_savedata.py), because the compiler error for the two-argument
        // form does not say what is missing.
        //
        // The null is deliberate: DataFixTypes is the vanilla datafixer's migration table, and this
        // is our own format with its own version key, so there is nothing for it to migrate.
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TeamStore::new, TeamStore::load, null), DATA_NAME);
    }

    public Optional<Team> byId(UUID id) {
        return Optional.ofNullable(teams.get(id));
    }

    public Team put(Team team) {
        teams.put(team.id(), team);
        setDirty();
        return team;
    }

    public void remove(UUID id) {
        teams.remove(id);
        retired.add(id);
        setDirty();
    }

    public boolean isRetired(UUID id) {
        return retired.contains(id);
    }

    public Collection<Team> all() {
        return List.copyOf(teams.values());
    }

    public int size() {
        return teams.size();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);

        ListTag list = new ListTag();
        for (Team team : teams.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", team.id().toString());
            entry.putString("name", team.name());
            entry.putString("owner", team.owner().toString());
            entry.putLong("createdAt", team.createdAt());

            ListTag members = new ListTag();
            team.members().forEach((player, role) -> {
                CompoundTag member = new CompoundTag();
                member.putString("player", player.toString());
                member.putString("role", role.name());
                members.add(member);
            });
            entry.put("members", members);

            ListTag invites = new ListTag();
            for (UUID invited : team.invites()) {
                CompoundTag invite = new CompoundTag();
                invite.putString("player", invited.toString());
                invites.add(invite);
            }
            entry.put("invites", invites);

            list.add(entry);
        }
        tag.put("teams", list);

        ListTag retiredIds = new ListTag();
        for (UUID id : retired) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id.toString());
            retiredIds.add(entry);
        }
        tag.put("retired", retiredIds);

        return tag;
    }

    /**
     * Reads the store back.
     *
     * <p>Private, because the only caller is the factory above — Minecraft's own loading path. A
     * public loader would invite a second way to build a store, which is a second way for it to be
     * wrong.
     */
    private static TeamStore load(CompoundTag tag, HolderLookup.Provider registries) {
        TeamStore store = new TeamStore();

        ListTag list = tag.getList("teams", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);

            UUID id = readUuid(entry, "id");
            UUID owner = readUuid(entry, "owner");
            if (id == null || owner == null) {
                // A team with no id or no owner cannot be repaired, and keeping it would produce
                // one nobody can join or leave. Dropped, and said so.
                dev.ellipog.armature.Constants.LOG.warn(
                        "armature: skipping a stored team with no id or owner (entry {})", i);
                continue;
            }

            Map<UUID, TeamRole> members = new LinkedHashMap<>();
            ListTag memberList = entry.getList("members", Tag.TAG_COMPOUND);
            for (int m = 0; m < memberList.size(); m++) {
                CompoundTag memberTag = memberList.getCompound(m);
                UUID player = readUuid(memberTag, "player");
                if (player == null) {
                    continue;
                }
                members.put(player, readRole(memberTag.getString("role")));
            }

            // An owner who is not in the member list is a corrupt file. Putting them back is the
            // only reading that leaves a usable team, since an ownerless one cannot be managed.
            if (!members.containsKey(owner)) {
                members.put(owner, TeamRole.OWNER);
            }

            Set<UUID> invites = new LinkedHashSet<>();
            ListTag inviteList = entry.getList("invites", Tag.TAG_COMPOUND);
            for (int n = 0; n < inviteList.size(); n++) {
                UUID invited = readUuid(inviteList.getCompound(n), "player");
                if (invited != null) {
                    invites.add(invited);
                }
            }

            store.teams.put(id, new Team(id, entry.getString("name"), owner,
                    members, invites, entry.getLong("createdAt"), true));
        }

        ListTag retiredList = tag.getList("retired", Tag.TAG_COMPOUND);
        for (int i = 0; i < retiredList.size(); i++) {
            UUID id = readUuid(retiredList.getCompound(i), "id");
            if (id != null) {
                store.retired.add(id);
            }
        }

        return store;
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        String raw = tag.getString(key);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            dev.ellipog.armature.Constants.LOG.warn("armature: '{}' is not a valid uuid in stored team data", raw);
            return null;
        }
    }

    private static TeamRole readRole(String raw) {
        try {
            return TeamRole.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            // A role that no longer exists means somebody removed a constant. Demoting to MEMBER is
            // the safe direction: it grants nothing new.
            dev.ellipog.armature.Constants.LOG.warn("armature: unknown team role '{}', treating as MEMBER", raw);
            return TeamRole.MEMBER;
        }
    }
}
