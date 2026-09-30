package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.nbt.CompoundTag;
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
        // player's new party inherited a finished questline from an old one.
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
                .withInvite(invited);
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
}
