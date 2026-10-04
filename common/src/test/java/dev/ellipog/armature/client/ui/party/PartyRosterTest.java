package dev.ellipog.armature.client.ui.party;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A team's roster, as the rows a panel draws and the buttons it places.
 *
 * <h2>What is asserted here, and why it can be</h2>
 *
 * <p>Every field of {@link PartyRoster} is a team, an id, a string or a boolean, so the questions that
 * matter are answerable without a window: <b>is the owner first, does a stranger get a Remove button,
 * is the height the rows it placed, and does a button ever sit outside the row it belongs to.</b> Those
 * are the four ways a roster can be wrong on screen, and each one has a case below.
 *
 * <p>The case that carries the most weight is {@link Permissions}: {@code canRemove} has to be the same
 * answer the server gives, because the alternative is a button that does nothing when pressed — and the
 * assertion that pins it is not "the panel thinks so" but "the panel and {@link Team#canActOn} agree",
 * checked member by member over every role pairing.
 */
@DisplayName("The party roster: rows, order, and who may remove whom")
class PartyRosterTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);
    private static final int WIDTH = 200;

    /** Three players, so a test can name them rather than build ids inline. */
    /**
     * Everybody connected, which is what most of these cases are about: who may remove whom does not
     * depend on who is online, and a case that is about authority should not be reading a marker.
     * `whoIsOnlineIsWhatTheCallerSaid` is the case that is about the marker.
     */
    private static final PartyRoster.Online EVERYBODY = id -> true;

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OFFICER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static final Map<UUID, String> NAMES = new HashMap<>();

    static {
        NAMES.put(OWNER, "Ada");
        NAMES.put(OFFICER, "Bo");
        NAMES.put(MEMBER, "Cy");
        NAMES.put(STRANGER, "Dee");
    }

    private static String nameOf(UUID id) {
        return NAMES.getOrDefault(id, id.toString());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A party of three: an owner, an officer and a member. */
    private static Team party() {
        Team team = Team.created(UUID.randomUUID(), "the crew", OWNER, 0L);
        team = team.withMember(OFFICER, TeamRole.OFFICER);
        return team.withMember(MEMBER, TeamRole.MEMBER);
    }

    /** What a player alone has. Synthesised rather than stored, so `persistent` is false. */
    private static Team solo() {
        return Team.solo(MEMBER);
    }

    // ------------------------------------------------------------------
    // Order
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the order the rows are drawn in")
    class Order {

        @Test
        @DisplayName("the owner comes first, then by rank, whatever order the members map holds")
        void ownerFirstThenByRank() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);

            assertEquals(List.of(OWNER, OFFICER, MEMBER),
                    roster.members().stream().map(PartyRoster.Member::id).toList(),
                    "owner, then officer, then member -- the map's own order is deliberately "
                            + "unspecified, so a roster that used it would list the same party "
                            + "differently between calls");
        }

        @Test
        @DisplayName("the owner is first, and the member map's order is not consulted")
        void ownerFirstRegardless() {
            // Built with the owner *last* in the map handed over, which is the case a roster reading
            // the map's own order would get wrong in the most visible way: the party's owner appearing
            // third in their own party.
            //
            // The framing here is deliberately weaker than "the map would not put them there", and the
            // reason is that `Team`'s constructor copies its members into an immutable map whose
            // iteration order is **unspecified** -- so the order this test writes is not the order the
            // roster could see even if it wanted to. That is the case *for* sorting rather than against
            // this test: there is no order to rely on in either direction, which is why the roster
            // imposes one.
            java.util.LinkedHashMap<UUID, TeamRole> handedOver = new java.util.LinkedHashMap<>();
            handedOver.put(MEMBER, TeamRole.MEMBER);
            handedOver.put(OFFICER, TeamRole.OFFICER);
            handedOver.put(OWNER, TeamRole.OWNER);

            Team team = new Team(UUID.randomUUID(), "late owner", OWNER, handedOver,
                    Map.of(), TeamPolicy.DEFAULT, 0L, true);

            PartyRoster roster = PartyRoster.of(team, OWNER, PartyRosterTest::nameOf, EVERYBODY);

            assertEquals(OWNER, roster.members().get(0).id(),
                    "the owner leads, because that is what a roster is read for -- and the map it was "
                            + "built from has no order to rely on, which is why this is imposed");
            assertEquals(TeamRole.OWNER, roster.members().get(0).role());
        }

        @Test
        @DisplayName("equal ranks are ordered by name, so two officers do not swap places")
        void equalRanksBreakOnName() {
            UUID zed = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
            NAMES.put(zed, "Zed");

            Team team = Team.created(UUID.randomUUID(), "tie", OWNER, 0L)
                    .withMember(zed, TeamRole.OFFICER)
                    .withMember(OFFICER, TeamRole.OFFICER);

            PartyRoster roster = PartyRoster.of(team, OWNER, PartyRosterTest::nameOf, EVERYBODY);

            assertEquals(List.of(OWNER, OFFICER, zed),
                    roster.members().stream().map(PartyRoster.Member::id).toList(),
                    "Bo before Zed -- by name rather than by id, because a name is something both "
                            + "members can see and an id is not");
        }

        @Test
        @DisplayName("a member's own row is marked, and the label says so")
        void selfIsMarked() {
            PartyRoster roster = PartyRoster.of(party(), OFFICER, PartyRosterTest::nameOf, EVERYBODY);

            PartyRoster.Member self = roster.members().stream()
                    .filter(PartyRoster.Member::self).findFirst().orElseThrow();

            assertEquals(OFFICER, self.id(), "the viewer's own row is theirs");
            assertEquals("Bo (you)", self.label(), "and it says so, because every caller that got a "
                    + "bare name would need the same which-one-am-I test");
            assertEquals("Ada", roster.members().get(0).label(),
                    "and everybody else's does not");
        }

        @Test
        @DisplayName("the owner flag is the owner, not the viewer")
        void ownerFlagIsTheOwners() {
            PartyRoster roster = PartyRoster.of(party(), OFFICER, PartyRosterTest::nameOf, EVERYBODY);

            assertTrue(roster.members().get(0).owner(), "the first row is the owner");
            assertFalse(roster.members().stream()
                            .filter(m -> m.id().equals(OFFICER)).findFirst().orElseThrow().owner(),
                    "and an officer viewing is not the owner, however senior they are");
        }
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("who may remove whom")
    class Permissions {

        @Test
        @DisplayName("every row's canRemove is the team's own answer, over every role pairing")
        void agreesWithTheTeam() {
            // The assertion that makes the button and the enforcement the same rule. Swept over every
            // (viewer, target) pairing of all three roles rather than checked once, because the
            // interesting failures are the equal-rank ones -- an officer able to remove another officer
            // is a rule that reads fine and is wrong.
            for (TeamRole viewerRole : TeamRole.values()) {
                for (TeamRole targetRole : TeamRole.values()) {
                    final TeamRole viewer = viewerRole;
                    final TeamRole target = targetRole;
                    Team team = Team.created(UUID.randomUUID(), "pairing", OWNER, 0L)
                            .withMember(MEMBER, viewer)
                            .withMember(OFFICER, target);

                    PartyRoster roster = PartyRoster.of(team, MEMBER, PartyRosterTest::nameOf, EVERYBODY);
                    PartyRoster.Member row = roster.members().stream()
                            .filter(m -> m.id().equals(OFFICER)).findFirst().orElseThrow();

                    assertEquals(team.canActOn(MEMBER, OFFICER), row.canRemove(),
                            () -> "a roster drawn for a " + viewer + " looking at a " + target
                                    + " disagreed with the team's own answer. The panel would draw a "
                                    + "Remove button the server refuses, or hide one it would accept");
                }
            }
        }

        @Test
        @DisplayName("an owner may remove an officer and a member, and neither may remove the owner")
        void ownerOutranksEverybody() {
            PartyRoster asOwner = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            assertEquals(2, asOwner.removableCount(), "the owner may remove both of the others");

            PartyRoster asOfficer = PartyRoster.of(party(), OFFICER, PartyRosterTest::nameOf, EVERYBODY);
            assertEquals(1, asOfficer.removableCount(),
                    "an officer may remove the member and not the owner, and not themselves");
        }

        @Test
        @DisplayName("nobody may remove themselves, so the button is absent from your own row")
        void nobodyRemovesThemselves() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);

            PartyRoster.Member ownerRow = roster.members().get(0);
            assertTrue(ownerRow.self());
            assertFalse(ownerRow.canRemove(),
                    "removing yourself is *leaving*, which is a different act -- and the owner "
                            + "leaving by kick would hand ownership to the fallback rule rather than "
                            + "to a decision");
        }

        @Test
        @DisplayName("a stranger to the party may remove nobody")
        void strangersRemoveNobody() {
            PartyRoster roster = PartyRoster.of(party(), STRANGER, PartyRosterTest::nameOf, EVERYBODY);

            assertEquals(0, roster.removableCount(),
                    "a non-member has no authority here. The role model defaults a non-member to "
                            + "MEMBER for a simple permission check, so a stranger would otherwise "
                            + "outrank nobody and still be offered nothing -- checked rather than "
                            + "assumed");
            assertFalse(roster.canDisband(), "and may not disband it either");
        }
    }

    // ------------------------------------------------------------------
    // Handing the party over, and the settings the owner reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("who may be handed the party, and what the viewer may change")
    class TransferAndSettings {

        @Test
        @DisplayName("every row's canTransfer is the team's own answer, over every role pairing")
        void transferAgreesWithTheTeam() {
            // The same sweep `canRemove` gets, for the same reason: the Transfer control the panel
            // draws and the manager's refusal have to be one rule, and the interesting failures are
            // the ones where a plausible-looking comparison says yes and the server says no.
            for (TeamRole viewerRole : TeamRole.values()) {
                for (TeamRole targetRole : TeamRole.values()) {
                    final TeamRole viewer = viewerRole;
                    final TeamRole target = targetRole;
                    Team team = Team.created(UUID.randomUUID(), "pairing", OWNER, 0L)
                            .withMember(MEMBER, viewer)
                            .withMember(OFFICER, target);

                    PartyRoster roster = PartyRoster.of(team, MEMBER, PartyRosterTest::nameOf, EVERYBODY);
                    PartyRoster.Member row = roster.members().stream()
                            .filter(m -> m.id().equals(OFFICER)).findFirst().orElseThrow();

                    assertEquals(team.canTransfer(MEMBER, OFFICER), row.canTransfer(),
                            () -> "a roster drawn for a " + viewer + " looking at a " + target
                                    + " disagreed with the team's own transfer rule");
                }
            }
        }

        @Test
        @DisplayName("the owner sees the management controls and others do not")
        void ownerOnlyControls() {
            PartyRoster asOwner = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            assertTrue(asOwner.canRename(), "the owner may rename");
            assertTrue(asOwner.canSetPolicy(), "and change the settings");
            assertTrue(asOwner.canInvite(), "and invite");

            PartyRoster asOfficer = PartyRoster.of(party(), OFFICER, PartyRosterTest::nameOf, EVERYBODY);
            assertFalse(asOfficer.canRename(), "an officer cannot rename the party");
            assertFalse(asOfficer.canSetPolicy(), "nor change its settings");
            assertTrue(asOfficer.canInvite(), "but an officer may always invite -- the member switch "
                    + "is about members, not a replacement for the rank table");

            PartyRoster asMember = PartyRoster.of(party(), MEMBER, PartyRosterTest::nameOf, EVERYBODY);
            assertFalse(asMember.canRename());
            assertFalse(asMember.canSetPolicy());
            assertTrue(asMember.canInvite(), "and a member may too, on the default policy");
        }

        @Test
        @DisplayName("a member may invite only while the policy says so")
        void memberInvitationsFollowThePolicy() {
            Team closed = party().withPolicy(TeamPolicy.DEFAULT.withMembersCanInvite(false));

            PartyRoster roster = PartyRoster.of(closed, MEMBER, PartyRosterTest::nameOf, EVERYBODY);
            assertFalse(roster.canInvite(),
                    "with the switch off an ordinary member gets no Invite row -- the same answer "
                            + "StoredTeamManager.invite gives");
            assertFalse(roster.membersCanInvite(), "and the switch itself reads what the team carries");
        }

        @Test
        @DisplayName("a solo roster offers no management and reports the default policy")
        void soloOffersNothing() {
            PartyRoster roster = PartyRoster.of(solo(), MEMBER, PartyRosterTest::nameOf, EVERYBODY);

            assertFalse(roster.canInvite(), "there is no party to invite anybody to");
            assertFalse(roster.canRename());
            assertFalse(roster.canSetPolicy());
            assertTrue(roster.membersCanInvite(),
                    "the policy a solo team carries is the default -- not a null a switch would "
                            + "have to special-case");
            assertFalse(roster.openJoin(), "and a solo team is not a public party");
        }
    }

    // ------------------------------------------------------------------
    // A party of one, and no party at all
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("solo, and a party of one")
    class SoloAndAlone {

        @Test
        @DisplayName("a solo player has one row, and is a party of one only in size")
        void aSoloPlayerIsNotAParty() {
            PartyRoster roster = PartyRoster.of(solo(), MEMBER, PartyRosterTest::nameOf, EVERYBODY);

            assertEquals(1, roster.memberCount());
            assertFalse(roster.isReal(),
                    "a synthesised team is not a party, and a panel that inferred one from the row "
                            + "count would show a newly created party of one as no party at all -- "
                            + "which is the state a player is in for ten seconds after creating one");
            assertFalse(roster.canLeave(), "there is nothing to leave");
            assertFalse(roster.canDisband(), "and nothing to disband");
            assertTrue(roster.members().get(0).self(), "the one row is you");
        }

        @Test
        @DisplayName("a party of one is a real party, and may be left and disbanded")
        void aPartyOfOneIsAParty() {
            Team alone = Team.created(UUID.randomUUID(), "just me", OWNER, 0L);
            PartyRoster roster = PartyRoster.of(alone, OWNER, PartyRosterTest::nameOf, EVERYBODY);

            assertEquals(1, roster.memberCount(), "the same size as a solo team");
            assertTrue(roster.isReal(),
                    "and a completely different thing, which is the distinction isReal exists for");
            assertTrue(roster.canLeave(), "there is a party to leave");
            assertTrue(roster.canDisband(), "and the owner can dissolve it rather than being stuck "
                    + "with a party nobody else is in");
        }
    }

    // ------------------------------------------------------------------
    // Laying out
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the layout")
    class Laying {

        @Test
        @DisplayName("the height is the rows placed, not a second sum that agrees")
        void heightIsTheRows() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            Layout layout = roster.stack(WIDTH, MEASURE);

            assertEquals(3, layout.slots().size(), "one slot per member, and nothing else");

            int last = layout.slots().get(layout.slots().size() - 1).bottom();
            assertEquals(last, layout.height(),
                    "the height is the bottom edge of the lowest row, which is what the kit computes "
                            + "and what a scrollbar's range is derived from");

            // The arithmetic, written out, so a change to either metric has to fail something.
            int expected = PartyRoster.ROW_HEIGHT * 3 + PartyRoster.ROW_GAP * 2;
            assertEquals(expected, layout.height(),
                    "three rows of 18 and two gaps of 2 -- and the gap is before each row after the "
                            + "first, so the list does not end with one that the height would not count");
        }

        @Test
        @DisplayName("every row is found by its key, and is inside the column")
        void rowsArePlacedAndFindable() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            Layout layout = roster.stack(WIDTH, MEASURE);

            for (PartyRoster.Member member : roster.members()) {
                Slot slot = layout.slot(member.key());
                assertNotNull(slot, () -> "no slot for " + member.name());
                assertTrue(slot.x() >= 0 && slot.right() <= WIDTH,
                        () -> member.name() + "'s row left the column: " + slot);
                assertTrue(slot.height() == PartyRoster.ROW_HEIGHT,
                        () -> member.name() + "'s row is not a row: " + slot);
            }
        }

        @Test
        @DisplayName("rows do not overlap each other")
        void rowsDoNotOverlap() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            Layout layout = roster.stack(WIDTH, MEASURE);
            List<Slot> slots = layout.slots();

            for (int i = 1; i < slots.size(); i++) {
                // The loop counter copied to a final local, because the message lambda is deferred and
                // `i` moves on. Java refuses to capture it, which is the compiler doing its job: a
                // lambda that named whichever row the loop had reached by the time it ran would report
                // the wrong pair, and only on failure -- so the message would be wrong exactly when it
                // was being read.
                final int at = i;
                assertTrue(slots.get(at).y() >= slots.get(at - 1).bottom(),
                        () -> "row " + at + " starts above the one before it: " + slots.get(at - 1)
                                + " then " + slots.get(at));
            }
        }

        @Test
        @DisplayName("the room taken off the right is reserved on every row, not only the ones with a button")
        void theButtonColumnIsReservedOnEveryRow() {
            // The property that keeps a name from running under the next row's controls. Every row
            // gives up the whole action strip -- Transfer, the gap, Remove and both insets -- including
            // the rows that carry no control, so two rows never have different text widths for a reason
            // nothing on screen explains.
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            Layout layout = roster.stack(WIDTH, MEASURE);

            int widest = layout.slots().stream().mapToInt(Slot::width).max().orElseThrow();
            int narrowest = layout.slots().stream().mapToInt(Slot::width).min().orElseThrow();

            assertEquals(widest, narrowest, "every row is the same width");
            assertEquals(WIDTH - PartyRoster.actionStrip(), widest,
                    "and that width is the column less the button's own room");
        }

        @Test
        @DisplayName("a Remove button is inside its row, and never outside the column")
        void removeButtonIsInsideItsRow() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            Layout layout = roster.stack(WIDTH, MEASURE);

            int checked = 0;
            for (PartyRoster.Member member : roster.members()) {
                Slot row = layout.slot(member.key());
                Slot remove = PartyRoster.removeSlot(member, row);

                if (remove == null) {
                    assertFalse(member.canRemove(), "a null button for a member who may be removed");
                    continue;
                }
                checked++;

                // Copied to finals because the lambdas below are deferred and `row`/`remove` are
                // reassigned each time round the loop. Java refuses to capture a local that moves, and
                // it is right to: a deferred lambda reading a variable that has moved on is a bug
                // wearing a compile error, which is the better of the two ways to find it.
                final Slot theRow = row;
                final Slot theButton = remove;

                assertTrue(theButton.x() >= theRow.x() && theButton.right() <= theRow.right(),
                        () -> "a Remove button left its row: row " + theRow + ", button " + theButton);
                assertTrue(theButton.y() >= theRow.y() && theButton.bottom() <= theRow.bottom(),
                        () -> "a Remove button left its row vertically: row " + theRow
                                + ", button " + theButton);
                assertEquals(theButton.x() + theButton.width() + PartyRoster.REMOVE_INSET, theRow.right(),
                        "and it is inset from the row's right edge by exactly the constant the row "
                                + "reserved -- one expression, not two that agree");
            }

            assertEquals(roster.removableCount(), checked,
                    "every member marked removable got a button and no one else did");

            assertNull(PartyRoster.removeSlot(
                            roster.members().stream().filter(m -> !m.canRemove()).findFirst().orElseThrow(),
                            layout.slots().get(0)),
                    "and asking for one for a member you may not remove is null rather than a slot "
                            + "at some position nobody checked the permission for");
        }

        @Test
        @DisplayName("a button key names the member it removes, and an unrelated key names nobody")
        void removeKeysRoundTrip() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);

            for (PartyRoster.Member member : roster.members()) {
                assertEquals(member.id(), PartyRoster.removeTarget(member.removeKey()),
                        () -> "a Remove key must name its own member: " + member.removeKey());
            }

            assertNull(PartyRoster.removeTarget("nonsense"));
            assertNull(PartyRoster.removeTarget(null));
            assertNull(PartyRoster.removeTarget(PartyRoster.REMOVE_PREFIX + "not-a-uuid"));
            assertNull(PartyRoster.removeTarget(roster.members().get(0).key()),
                    "a member row's own key is not a Remove key -- the two prefixes cannot be confused");
        }

        @Test
        @DisplayName("the row keys and the button keys are all distinct")
        void keysAreDistinct() {
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);
            Layout layout = roster.stack(WIDTH, MEASURE);

            List<Object> rosterKeys = layout.slots().stream().map(Slot::key).toList();
            assertEquals(rosterKeys.size(), rosterKeys.stream().distinct().count(),
                    "a stack places one slot per key, and a duplicate would be a row drawn twice");

            List<String> removeKeys = roster.members().stream()
                    .filter(PartyRoster.Member::canRemove)
                    .map(PartyRoster.Member::removeKey)
                    .toList();
            assertEquals(removeKeys.size(), removeKeys.stream().distinct().count());
            assertTrue(rosterKeys.stream().noneMatch(removeKeys::contains),
                    "and no Remove button shares a key with a row");
        }

        @Test
        @DisplayName("a zero-width column is empty rows rather than an exception")
        void zeroWidthIsSafe() {
            // A window can be dragged smaller than anything sensible. A negative-width slot would place
            // a rectangle that reads correctly everywhere it is used, which is the shape of bug that
            // survives to a screenshot.
            PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY);

            for (int width : new int[] {0, -1, 1, 8}) {
                Layout layout = roster.stack(width, MEASURE);
                for (Slot slot : layout.slots()) {
                    assertTrue(slot.width() >= 0, () -> "a negative width at column width " + width);
                }
            }
        }
    }

    @Test
    @DisplayName("a roster says what it is, which is what a log line and a failure want")
    void toStringIsUseful() {
        assertEquals("PartyRoster(3 member(s), viewer may remove 2)",
                PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, EVERYBODY).toString());
        assertEquals("PartyRoster(1 member(s), solo, viewer may remove 0)",
                PartyRoster.of(solo(), MEMBER, PartyRosterTest::nameOf, EVERYBODY).toString());
    }

    @Test
    void whoIsOnlineIsWhatTheCallerSaidRatherThanAGuess() {
        PartyRoster roster = PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf,
                id -> id.equals(OFFICER));

        PartyRoster.Member officer = roster.members().stream()
                .filter(member -> member.id().equals(OFFICER)).findFirst().orElseThrow();
        PartyRoster.Member owner = roster.members().stream()
                .filter(member -> member.id().equals(OWNER)).findFirst().orElseThrow();

        assertTrue(officer.online(),
                "the member the caller named as connected is drawn as connected");
        assertFalse(owner.online(),
                "and the owner is not, because the caller did not name them -- the roster draws what it "
                        + "is told, rather than assuming the person reading it is online");

        assertTrue(PartyRoster.of(party(), OWNER, PartyRosterTest::nameOf, PartyRoster.Online.NOBODY)
                        .members().stream().noneMatch(PartyRoster.Member::online),
                "and a caller with no answer says nobody rather than guessing");
    }

}
