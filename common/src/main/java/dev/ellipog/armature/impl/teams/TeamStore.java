package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamInvite;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
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
 * {@link StoredTeamManager} had to go through the wrapper to reach the same map, and the wrapper's only
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

    /**
     * The store's serialised form. A plain tag codec over {@link #save} and {@link #load} rather
     * than a field-by-field codec: the format is versioned by hand (see the version key in
     * {@link #save}), with a version 1 reader that a generated codec could not express, and the
     * {@code registries} neither side takes mean there is nothing a fancier codec would use.
     */
    public static final com.mojang.serialization.Codec<TeamStore> CODEC =
            CompoundTag.CODEC.xmap(TeamStore::load, store -> store.save(new CompoundTag()));

    private static final SavedDataType<TeamStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("armature", "teams"),
            TeamStore::new,
            CODEC,
            // Null, deliberately: DataFixTypes is vanilla's migration table, and this is our own
            // format with its own version key, so there is nothing for it to migrate. Same null the
            // 1.21.1 `SavedData.Factory` took, for the same reason.
            null);

    /** The 1.21.1 file name, flat under the data folder. Only used once, to move it (see below). */
    private static final String LEGACY_FILE = "armature_teams.dat";

    private final Map<UUID, Team> teams = new LinkedHashMap<>();

    /** Teams that were disbanded, so an id is never reused with old progress attached. */
    private final Set<UUID> retired = new LinkedHashSet<>();

    /** The store for this server. On the overworld, so teams are global rather than per-dimension. */
    public static TeamStore of(MinecraftServer server) {
        moveLegacyStoreIfPresent(server);
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Moves a 1.21.1 store file into the namespaced place 26.x reads from, once.
     *
     * <p>The old storage wrote {@code data/armature_teams.dat}; the new one resolves the type id to
     * {@code data/armature/teams.dat}. Without this, a server upgrading versions would start with no
     * teams — parties gone, and anything keyed by them orphaned — while the old file sat beside the
     * new one looking intact. A move rather than a copy, so there is never a moment with two live
     * files; if it throws, the legacy file is untouched and the next boot tries again, and the
     * failure is logged rather than left as a silent empty store.
     *
     * <p>The namespace-as-subdirectory layout is read off the version's own path resolution rather
     * than assumed, but a world upgrade has not run through it yet: the first 1.21.1-to-26.3 server
     * move should confirm a team survives it (see the port card's in-game list).
     */
    private static void moveLegacyStoreIfPresent(MinecraftServer server) {
        Path data = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Path legacy = data.resolve(LEGACY_FILE);
        Path current = data.resolve("armature").resolve("teams.dat");
        try {
            if (Files.isRegularFile(legacy)
                    && !Files.isRegularFile(current)) {
                Files.createDirectories(current.getParent());
                Files.move(legacy, current);
                dev.ellipog.armature.Constants.LOG.info(
                        "moved stored teams from {} to {} (1.21.1 to 26.x storage layout)",
                        legacy.getFileName(), current);
            }
        }
        catch (IOException e) {
            dev.ellipog.armature.Constants.LOG.warn(
                    "could not move stored teams from {} (leaving it for the next boot): {}",
                    legacy, e.toString());
        }
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

    /**
     * The teams as a live unmodifiable view, for the manager's own per-lookup scans.
     *
     * <p>Package-private: the copy in {@link #all()} stays the public answer, but the manager's
     * {@code realTeamOf} and {@code invitesFor} walk this instead of copying the map on every
     * call. Safe because every entry point runs on the server thread with no callbacks inside
     * the scan — see {@code TeamManager}'s threading note — so the view cannot be mutated
     * underneath its own walk.
     */
    Collection<Team> live() {
        return Collections.unmodifiableCollection(teams.values());
    }

    public int size() {
        return teams.size();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Writes the store. Called by {@link #CODEC}, not overridden from anywhere: 26.x storage
     * serialises through the type's codec, and this is the encoder half.
     */
    public CompoundTag save(CompoundTag tag) {
        // Version 2 added invitations with a sender and a time, and the party policy. A version 1
        // file loads as owner-sent invitations with no time and the default policy -- see load.
        tag.putInt("version", 2);

        ListTag list = new ListTag();
        for (Team team : teams.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", team.id().toString());
            entry.putString("name", team.name());
            entry.putString("owner", team.owner().toString());
            entry.putLong("createdAt", team.createdAt());
            entry.putBoolean("openJoin", team.policy().openJoin());
            entry.putBoolean("membersCanInvite", team.policy().membersCanInvite());

            ListTag members = new ListTag();
            team.members().forEach((player, role) -> {
                CompoundTag member = new CompoundTag();
                member.putString("player", player.toString());
                member.putString("role", role.name());
                members.add(member);
            });
            entry.put("members", members);

            ListTag invites = new ListTag();
            team.invites().forEach((invited, invite) -> {
                CompoundTag stored = new CompoundTag();
                stored.putString("player", invited.toString());
                stored.putString("inviter", invite.inviter().toString());
                stored.putLong("at", invite.at());
                invites.add(stored);
            });
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
     * <p>Package-private rather than private, and that is the whole of the reason it is not
     * {@code public}: Minecraft's own loading path is the only production caller, but a test needs a
     * second one. A test can build a store, save it and read it back with no server and no world —
     * which is the only way to assert the properties that outlive a session, and the only way to
     * check that a disbanded team's id is still recorded as spent after a round trip. {@code public}
     * would invite a second <i>production</i> way to build a store, which is a second way for it to
     * be wrong; package-private invites exactly one caller, from the test in this package.
     */
    static TeamStore load(CompoundTag tag) {
        TeamStore store = new TeamStore();

        ListTag list = tag.getListOrEmpty("teams");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);

            UUID id = readUuid(entry, "id");
            UUID owner = readUuid(entry, "owner");
            if (id == null || owner == null) {
                // A team with no id or no owner cannot be repaired, and keeping it would produce
                // one nobody can join or leave. Dropped, and said so.
                dev.ellipog.armature.Constants.LOG.warn(
                        "skipping a stored team with no id or owner (entry {})", i);
                continue;
            }

            Map<UUID, TeamRole> members = new LinkedHashMap<>();
            ListTag memberList = entry.getListOrEmpty("members");
            for (int m = 0; m < memberList.size(); m++) {
                CompoundTag memberTag = memberList.getCompoundOrEmpty(m);
                UUID player = readUuid(memberTag, "player");
                if (player == null) {
                    continue;
                }
                members.put(player, readRole(memberTag.getStringOr("role", "")));
            }

            // An owner who is not in the member list is a corrupt file. Putting them back is the
            // only reading that leaves a usable team, since an ownerless one cannot be managed.
            if (!members.containsKey(owner)) {
                members.put(owner, TeamRole.OWNER);
            }

            Map<UUID, TeamInvite> invites = new LinkedHashMap<>();
            ListTag inviteList = entry.getListOrEmpty("invites");
            for (int n = 0; n < inviteList.size(); n++) {
                CompoundTag inviteTag = inviteList.getCompoundOrEmpty(n);
                UUID invited = readUuid(inviteTag, "player");
                if (invited == null) {
                    continue;
                }
                // Version 1 stored a bare id: no sender and no time. The owner and zero are the two
                // honest substitutes -- see TeamInvite on why zero rather than a made-up time.
                UUID inviter = readUuid(inviteTag, "inviter");
                invites.put(invited, new TeamInvite(inviter == null ? owner : inviter,
                        inviteTag.getLongOr("at", 0L)));
            }

            // Absent on a version 1 file, whose parties predate both switches. The default is what
            // such a party behaved as, and it is what a solo team gets -- see TeamPolicy.
            TeamPolicy policy = entry.contains("openJoin") || entry.contains("membersCanInvite")
                    ? new TeamPolicy(entry.getBooleanOr("openJoin", false),
                            entry.getBooleanOr("membersCanInvite", false))
                    : TeamPolicy.DEFAULT;

            store.teams.put(id, new Team(id, entry.getStringOr("name", ""), owner,
                    members, invites, policy, entry.getLongOr("createdAt", 0L), true));
        }

        ListTag retiredList = tag.getListOrEmpty("retired");
        for (int i = 0; i < retiredList.size(); i++) {
            UUID id = readUuid(retiredList.getCompoundOrEmpty(i), "id");
            if (id != null) {
                store.retired.add(id);
            }
        }

        return store;
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        String raw = tag.getStringOr(key, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            dev.ellipog.armature.Constants.LOG.warn("'{}' is not a valid uuid in stored team data", raw);
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
            dev.ellipog.armature.Constants.LOG.warn("unknown team role '{}', treating as MEMBER", raw);
            return TeamRole.MEMBER;
        }
    }
}
