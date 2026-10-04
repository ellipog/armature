package dev.ellipog.armature.api.teams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a team carries: who may invite, who may be handed the party, and what the copies do.
 *
 * <h2>Why these are asserted on the record rather than through a manager</h2>
 *
 * <p>Because the record is where the rules live, and the whole reason they live there is that three
 * readers ask each one — the manager enforcing it, the command explaining a refusal, and the panel
 * deciding whether to draw a control — and those readers are in two repositories. A manager test can
 * only reach one of the three; a test on the record asserts the rule itself, and the manager's
 * enforcement is the same call.
 *
 * <p>{@link Team#canActOn}'s existing coverage lives in {@code PartyRosterTest}, through the one
 * client reader. The rules added with the party panel — {@link Team#canInvite} and
 * {@link Team#canTransfer} — are asserted here directly, including the cases a roster row cannot
 * show: a stranger, a policy that moved, and a target who is not a member.
 */
class TeamTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OFFICER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID INVITED = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** A party of three, with one of each rank and the default policy. */
    private static Team crew() {
        return Team.created(UUID.randomUUID(), "the crew", OWNER, 100L)
                .withMember(OFFICER, TeamRole.OFFICER)
                .withMember(MEMBER, TeamRole.MEMBER);
    }

    @Nested
    @DisplayName("who may invite")
    class WhoMayInvite {

        @Test
        @DisplayName("the owner and an officer always may")
        void ownerAndOfficerAlwaysMay() {
            Team tight = crew().withPolicy(TeamPolicy.DEFAULT.withMembersCanInvite(false));

            assertTrue(tight.canInvite(OWNER), "the owner may always invite");
            assertTrue(tight.canInvite(OFFICER),
                    "and an officer may, independent of the member switch -- the switch says "
                            + "'allow *members* to invite' rather than replacing the rank table");
        }

        @Test
        @DisplayName("a member may when the policy says so, and not when it does not")
        void aMemberFollowsThePolicy() {
            assertTrue(crew().canInvite(MEMBER),
                    "TeamPolicy.DEFAULT has member invitations on, which is the panel's default too");
            assertFalse(crew().withPolicy(TeamPolicy.DEFAULT.withMembersCanInvite(false)).canInvite(MEMBER),
                    "with the switch off, an ordinary member may not");
        }

        @Test
        @DisplayName("somebody who is not in the party never may, whatever the policy says")
        void aStrangerNeverMay() {
            // The open-join switch is a way in, not a permission: a stranger can join a public party,
            // and until they do they still have no say in it. Asserted because `roleOrMember` answers
            // MEMBER for a non-member by design, and a check written on that alone would let a
            // stranger through whenever member invitations are on.
            Team open = crew().withPolicy(TeamPolicy.OPEN);

            assertFalse(open.canInvite(STRANGER));
        }
    }

    @Nested
    @DisplayName("who may hand the party over")
    class WhoMayTransfer {

        @Test
        @DisplayName("the owner may hand it to another member, and to nobody else")
        void ownerToMemberOnly() {
            Team team = crew();

            assertTrue(team.canTransfer(OWNER, OFFICER));
            assertTrue(team.canTransfer(OWNER, MEMBER));
            assertFalse(team.canTransfer(OFFICER, OWNER), "an officer cannot take the party");
            assertFalse(team.canTransfer(MEMBER, OFFICER), "nor a member");
            assertFalse(team.canTransfer(OWNER, STRANGER),
                    "and the target must be a member, so a transfer cannot produce an owner outside "
                            + "the member list -- which is the state withOwner refuses to build");
        }

        @Test
        @DisplayName("transferring to oneself is refused")
        void notToOneself() {
            assertFalse(crew().canTransfer(OWNER, OWNER),
                    "a transfer to yourself is not a transfer; the owner keeps ownership either way, "
                            + "but a control offering it would be a button that does nothing");
        }
    }

    @Nested
    @DisplayName("invitations carry who and when")
    class Invitations {

        @Test
        @DisplayName("withInvite records the sender and the time")
        void withInviteRecordsTheMetadata() {
            Team team = crew().withInvite(INVITED, OFFICER, 42L);

            assertTrue(team.isInvited(INVITED));
            TeamInvite invite = team.inviteOf(INVITED).orElseThrow();
            assertEquals(OFFICER, invite.inviter(), "who asked");
            assertEquals(42L, invite.at(), "and when, in game time");
            assertTrue(invite.hasTime());
            assertEquals(3, team.size(), "and an invitation is not a membership");
        }

        @Test
        @DisplayName("a time of zero means the source could not say")
        void zeroTimeIsTheUnknown() {
            Team team = crew().withInvite(INVITED, OWNER, 0L);

            TeamInvite invite = team.inviteOf(INVITED).orElseThrow();
            assertFalse(invite.hasTime(),
                    "zero is not 'sent at the start of time' -- see TeamInvite: a foreign source "
                            + "publishes no timestamp, and a made-up one would read as authoritative");
        }

        @Test
        @DisplayName("inviting twice keeps the first invitation, and a member cannot be invited")
        void invitationsAreIdempotentAndNeverMembership() {
            Team once = crew().withInvite(INVITED, OWNER, 10L);
            Team twice = once.withInvite(INVITED, OFFICER, 99L);

            assertSame(once, twice,
                    "a second invitation would only replace the sender and time of one already "
                            + "pending, and the first is the one the invitee has been looking at");

            assertSame(once, once.withInvite(MEMBER, OWNER, 10L),
                    "and somebody already in the party is not invited to it");
        }

        @Test
        @DisplayName("withdrawing an invitation leaves everything else alone")
        void withdrawing() {
            Team team = crew().withInvite(INVITED, OWNER, 10L);
            Team after = team.withoutInvite(INVITED);

            assertFalse(after.isInvited(INVITED));
            assertEquals(team.name(), after.name());
            assertEquals(team.members(), after.members());
            assertEquals(team.policy(), after.policy());
            assertSame(after, after.withoutInvite(INVITED), "and withdrawing twice is a no-op");
        }
    }

    @Nested
    @DisplayName("the copies")
    class Copies {

        @Test
        @DisplayName("withOwner promotes the target, demotes the actor, and keeps every other field")
        void withOwnerSwapsTheRoles() {
            Team team = crew().withPolicy(TeamPolicy.OPEN).withInvite(INVITED, OWNER, 7L);
            Team handed = team.withOwner(OFFICER, TeamRole.MEMBER);

            assertEquals(OFFICER, handed.owner(), "the target owns it now");
            assertEquals(TeamRole.OWNER, handed.roleOf(OFFICER).orElseThrow());
            assertEquals(TeamRole.MEMBER, handed.roleOf(OWNER).orElseThrow(),
                    "and the previous owner is demoted to the role the caller named -- MEMBER for the "
                            + "successor flow, where they are about to leave and the demotion is not "
                            + "observable at all");
            assertEquals(team.name(), handed.name());
            assertEquals(team.policy(), handed.policy());
            assertEquals(team.invites(), handed.invites(),
                    "an ownership change is not a reason to forget who is invited");
            assertEquals(team.createdAt(), handed.createdAt());
        }

        @Test
        @DisplayName("withOwner refuses a non-member or the current owner, rather than building a broken team")
        void withOwnerRefusesTheImpossible() {
            Team team = crew();

            assertSame(team, team.withOwner(STRANGER, TeamRole.MEMBER),
                    "an owner who is not in the member list is a team nobody can manage");
            assertSame(team, team.withOwner(OWNER, TeamRole.MEMBER),
                    "and transferring to yourself changes nothing");
        }

        @Test
        @DisplayName("a new or solo team starts on the default policy")
        void defaultPolicy() {
            assertEquals(TeamPolicy.DEFAULT, Team.solo(OWNER).policy());
            assertEquals(TeamPolicy.DEFAULT, crew().policy(),
                    "which is invite-only with member invitations on -- see TeamPolicy on why that "
                            + "is the panel spec's default and a version 1 file's migration target");
            assertTrue(TeamPolicy.DEFAULT.membersCanInvite(), "the switch's documented default");
            assertFalse(TeamPolicy.DEFAULT.openJoin(), "and the party is not public by default");
        }
    }
}
