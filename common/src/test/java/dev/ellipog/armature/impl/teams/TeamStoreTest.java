package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamInvite;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stored teams' own invariants — the ones that outlive a server session.
 *
 * <h2>Why a store can be built without a server here</h2>
 *
 * <p>{@code TeamStore.of(server)} needs an overworld and a data storage; the constructor does not.
 * {@code put} and {@code remove} call {@code setDirty()}, and in 1.21.1 {@code SavedData.setDirty()}
 * does one thing — assigns a boolean — so a store that will never be written can still be exercised
 * through its real methods. Checked in the decompiled source rather than assumed, because the whole
 * value of these tests depends on that being true.
 *
 * <h2>What the retired set is for, and what it is not</h2>
 *
 * <p>Disbanding a team records its id, so that a later team cannot quietly inherit whatever was keyed
 * by it — a fresh party starting with a previous party's progress. Ids are random UUIDs, so a
 * collision is not a thing that happens; the value is not in the check but in the <b>persistence</b> of
 * the record, which is what these tests are about. Writing a test around the collision itself would
 * mean injecting an id source to prove something that cannot occur, so what is asserted is the part
 * that can actually be wrong: a retirement that survives a save and a load.
 */
class TeamStoreTest {

    @Test
    @DisplayName("a retired id stays retired across a save and a load")
    void aRetiredIdStaysRetired() {
        TeamStore store = new TeamStore();
        Team team = Team.created(UUID.randomUUID(), "the crew", UUID.randomUUID(), 42L);

        store.put(team);
        assertEquals(1, store.size(), "the team is stored");
        assertTrue(store.byId(team.id()).isPresent());
        assertFalse(store.isRetired(team.id()), "and nothing has retired it yet");

        store.remove(team.id());
        assertEquals(0, store.size(), "disbanding takes it out");
        assertTrue(store.byId(team.id()).isEmpty(), "so its id resolves to nothing");
        assertTrue(store.isRetired(team.id()), "and the id is recorded as spent");

        // The round trip, which is the whole point: a record held only in memory would let the
        // retirement evaporate at the next world save, and the failure would be invisible until a
        // player's new party inherited finished progress from an old one.
        CompoundTag saved = store.save(new CompoundTag(), null);
        TeamStore reloaded = TeamStore.load(saved, null);

        assertTrue(reloaded.isRetired(team.id()), "the retirement survived the save and the load");
        assertTrue(reloaded.byId(team.id()).isEmpty(), "and the team it belonged to did not come back");
        assertEquals(0, reloaded.size());

        // And an id that was never retired is not swept up by the same block -- a test that only
        // asserted the positive would pass on a loader that retired everything it read.
        assertFalse(reloaded.isRetired(UUID.randomUUID()), "retirement is per id, not a blanket");
    }

    @Test
    @DisplayName("a stored team survives the round trip, members, roles and all")
    void aStoredTeamSurvivesASave() {
        TeamStore store = new TeamStore();
        UUID owner = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        UUID invited = UUID.randomUUID();

        Team team = Team.created(UUID.randomUUID(), "the crew", owner, 128L)
                .withMember(officer, TeamRole.OFFICER)
                .withInvite(invited, owner, 200L);
        store.put(team);

        Team reloaded = TeamStore.load(store.save(new CompoundTag(), null), null).byId(team.id())
                .orElseThrow(() -> new AssertionError("the team should have come back from its own save"));

        assertEquals(team, reloaded,
                "a Team is a record, so one assertion covers the name, the owner, the roles, the " +
                        "invites and the timestamp -- and a field dropped from save() or from load() " +
                        "fails it rather than being noticed a month later");
        assertEquals(TeamRole.OFFICER, reloaded.roleOf(officer).orElseThrow(),
                "and the officer is still an officer, which is the field most easily folded into " +
                        "MEMBER by a mapping that only round-trips two roles");
        assertTrue(reloaded.isInvited(invited),
                "an invitation is not membership, and losing it on save is silent -- the player simply " +
                        "never gets told they were asked");
    }

    @Test
    @DisplayName("a team with no id or no owner is dropped rather than loaded broken")
    void anUnrepairableTeamIsDropped() {
        TeamStore store = new TeamStore();
        store.put(Team.created(UUID.randomUUID(), "the crew", UUID.randomUUID(), 0L));

        CompoundTag saved = store.save(new CompoundTag(), null);

        // Empty the id, which is what a truncated or hand-edited file looks like. The loader's job is
        // to survive it: a team nobody can join or leave is worse than a team that is gone, and a
        // warning naming the entry is better than a NullPointerException from somewhere else.
        CompoundTag entry = saved.getList("teams", Tag.TAG_COMPOUND).getCompound(0);
        entry.putString("id", "");

        TeamStore reloaded = TeamStore.load(saved, null);

        assertEquals(0, reloaded.size(), "the unreadable team was dropped");
        assertNotNull(reloaded, "and the store itself loaded cleanly");
    }

    @Test
    @DisplayName("the policy and an invitation's sender and time survive the round trip")
    void policyAndInvitationMetadataSurvive() {
        TeamStore store = new TeamStore();
        UUID owner = UUID.randomUUID();
        UUID invited = UUID.randomUUID();

        // A policy that differs from the default in *both* fields, so a loader that read one and
        // defaulted the other cannot pass -- and deliberately the non-default value of each, so a
        // `getBoolean` that ignored the stored bytes would answer wrong rather than coincidentally right.
        Team team = Team.created(UUID.randomUUID(), "open crew", owner, 500L)
                .withPolicy(new TeamPolicy(true, false))
                .withInvite(invited, owner, 733L);
        store.put(team);

        Team reloaded = TeamStore.load(store.save(new CompoundTag(), null), null).byId(team.id())
                .orElseThrow(() -> new AssertionError("the team should have come back"));

        assertEquals(new TeamPolicy(true, false), reloaded.policy(),
                "both switches are read back as stored, not as defaults");
        assertEquals(new TeamInvite(owner, 733L), reloaded.inviteOf(invited).orElseThrow(),
                "and an invitation keeps who sent it and when -- the two facts a panel row shows and "
                        + "a bare id could not");
    }

    @Test
    @DisplayName("a version 1 file loads with owner-sent invitations, no time, and the default policy")
    void aVersionOneFileMigrates() {
        // Built by hand rather than through save(), because save() no longer writes version 1 -- and a
        // migration test that used the current writer would be testing that the writer agrees with
        // itself. This is the shape the old format actually had: bare invite ids and no policy keys.
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID invited = UUID.randomUUID();

        CompoundTag entry = new CompoundTag();
        entry.putString("id", id.toString());
        entry.putString("name", "the old crew");
        entry.putString("owner", owner.toString());
        entry.putLong("createdAt", 7L);

        ListTag members = new ListTag();
        CompoundTag member = new CompoundTag();
        member.putString("player", owner.toString());
        member.putString("role", "OWNER");
        members.add(member);
        entry.put("members", members);

        ListTag invites = new ListTag();
        CompoundTag invite = new CompoundTag();
        invite.putString("player", invited.toString());
        invites.add(invite);
        entry.put("invites", invites);

        ListTag teams = new ListTag();
        teams.add(entry);

        CompoundTag versionOne = new CompoundTag();
        versionOne.putInt("version", 1);
        versionOne.put("teams", teams);

        Team loaded = TeamStore.load(versionOne, null).byId(id)
                .orElseThrow(() -> new AssertionError("a version 1 team must still load"));

        assertEquals(owner, loaded.owner());
        assertEquals(TeamRole.OWNER, loaded.roleOf(owner).orElseThrow());
        TeamInvite migrated = loaded.inviteOf(invited)
                .orElseThrow(() -> new AssertionError("the version 1 invitation must survive"));
        assertEquals(owner, migrated.inviter(),
                "with no sender stored, the owner stands in -- see TeamInvite on why an approximate "
                        + "sender beats no sender");
        assertEquals(0L, migrated.at(), "and no time stored is zero, not a made-up one");
        assertFalse(migrated.hasTime());
        assertEquals(TeamPolicy.DEFAULT, loaded.policy(),
                "a party from before the switches existed loads on the documented default: invite-only, "
                        + "with member invitations on -- and never null, which would be a policy nobody "
                        + "can read off a team");
    }
}
