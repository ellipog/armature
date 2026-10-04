package dev.ellipog.armature.client.ui.party;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * A team's roster, as rows a caller can draw and buttons a caller can place.
 *
 * <h2>Why this is Armature's and not a consumer's</h2>
 *
 * <p>The rule used elsewhere in this project is that a <i>particular</i> screen's composition belongs
 * to that screen — {@code SidebarLayout} is about chapters and {@code OverlayLayout} is about entries,
 * so both live in the mod that has chapters and entries. This is the case where the same reasoning
 * points the other way, and it is worth stating because the test is a test rather than a habit.
 *
 * <p>What those two compose is an entry tree and an entry's parts. What this composes is <b>a team</b>:
 * a member, their role, whether they are you, and whether you may remove them. Every one of those
 * facts is {@link Team}'s and {@link TeamRole}'s, which are Armature's — and any mod with a party can
 * want exactly this list. A roster that composed something Armature does not own — "how many logs has
 * this member gathered" — would belong in the mod that owns the logs, which is the argument
 * {@code PartyMode} is made with in the other direction.
 *
 * <h2>The one thing it is for, beyond drawing names</h2>
 *
 * <p>{@link Member#canRemove} is {@link Team#canActOn} — the same call the server makes when a kick is
 * asked for. So the button and the enforcement cannot disagree, because there is one rule and two
 * readers. A panel that decided for itself would draw a Remove button for somebody the server would
 * refuse, and the symptom is a control that does nothing: worse than a missing one, because the player
 * concludes the mod is broken rather than that they lack the rank.
 *
 * <h2>Game-free, so it can be asserted on</h2>
 *
 * <p>Every field is a {@link Team}, a {@link UUID}, a string or a boolean, and {@link #rows()} returns
 * values. So {@code PartyRosterTest} can ask the questions that matter — is the owner first, does a
 * stranger get a Remove button, is the list's height the rows it placed — without a window, a font or
 * a client. That is the same argument the whole kit is extracted on.
 *
 * <h2>Ordering, and why it is not the map's</h2>
 *
 * <p>Owner first, then by authority, then by name. {@code Team.members()} is an immutable map whose
 * iteration order is deliberately unspecified, so a roster drawn straight from it would list the same
 * party differently between calls — and the eye reads a shifting list as a list that can be trusted
 * less, even when every row is right. The tie-break on name rather than id is for the reader: two
 * members of equal rank are ordered by something they can both see.
 */
public final class PartyRoster {

    /** The height of one member's row. The same as a control elsewhere, so a list lines up with one. */
    public static final int ROW_HEIGHT = 18;

    /** Between two rows. Two, as in the sidebar: a list's spacing is not a stack of buttons'. */
    public static final int ROW_GAP = 2;

    /** How wide the Remove button is. Its label is a word, so it is not a square. */
    public static final int REMOVE_WIDTH = 46;

    /**
     * How wide the Transfer button is.
     *
     * <h2>Why the two buttons share a strip rather than each having one</h2>
     *
     * <p>Both are owner controls on the same row and both act on the same member, so they belong side
     * by side in one reserved strip: two strips would take twice the width from the name, and the
     * column is the narrower half of the panel. The strip is reserved on <b>every</b> row — see
     * {@link #composition} — so a member's name has the same room whether or not a control happens to
     * be drawn on their row.
     */
    public static final int TRANSFER_WIDTH = 48;

    /** Kept between a row's edges and the button inside it. */
    public static final int REMOVE_INSET = 2;

    /** What a member row's key starts with. Prefixed so it cannot collide with a caller's own rows. */
    public static final String MEMBER_PREFIX = "party:member:";

    /** What a Remove button's key starts with. */
    public static final String REMOVE_PREFIX = "party:remove:";

    /** What a Transfer button's key starts with. */
    public static final String TRANSFER_PREFIX = "party:transfer:";

    /**
     * One member, and everything a row needs to know about them.
     *
     * @param id        who they are
     * @param name      what to call them. Resolved by the caller, because a name is a display concern
     *                  and this class has no way to look one up — see {@link #of}
     * @param role      their role
     * @param self      whether this is the viewer's own row
     * @param owner     whether this is the party's owner
     * @param canRemove whether <b>the viewer</b> may remove them. See the class note: this is the same
     *                  answer the server will give
     * @param canTransfer whether <b>the viewer</b> may hand ownership to them. {@link Team#canTransfer},
     *                    like {@code canRemove} — the panel's Transfer control and the manager's
     *                    refusal ask the same rule
     */
    public record Member(UUID id, String name, TeamRole role, boolean self, boolean owner,
                         boolean canRemove, boolean canTransfer, boolean online) {

        /** What places this row, and what a click on it is routed by. */
        public String key() {
            return MEMBER_PREFIX + id;
        }

        /** What places this member's Remove button, and what a click on it is routed by. */
        public String removeKey() {
            return REMOVE_PREFIX + id;
        }

        /** What places this member's Transfer button, and what a click on it is routed by. */
        public String transferKey() {
            return TRANSFER_PREFIX + id;
        }

        /** The role, spelling the way the command spells it. */
        public String roleLabel() {
            return role.name().toLowerCase(Locale.ROOT);
        }

        /**
         * What the row says.
         *
         * <p>Marked with "(you)" rather than coloured or bolded, because the caller decides how a row
         * looks and this decides what it says — the same split as {@code SidebarLayout.Row.label()},
         * which puts the chevron in the label and the fill in the caller's hands. Every caller that
         * got a bare name would otherwise need the same "which one am I" test.
         */
        public String label() {
            return self ? name + " (you)" : name;
        }
    }

    /**
     * Who is connected, as a roster has to know it.
     *
     * <h2>Why this is an interface rather than a set of ids</h2>
     *
     * <p>Because the two sides answer it from different things and neither should build the other's
     * collection: a server has a player list it can ask directly, and a client has a snapshot that
     * arrived with the answer already in it. One method covers both, and a caller with no answer writes
     * {@code id -> false} rather than passing null.
     */
    @FunctionalInterface
    public interface Online {

        /** Nobody, for a caller that cannot know — a rehearsal, a preview, or a test. */
        Online NOBODY = id -> false;

        boolean isOnline(UUID player);
    }

    private final Team team;
    private final UUID viewer;
    private final List<Member> members;
    private final boolean real;
    private final int memberLimit;

    private PartyRoster(Team team, UUID viewer, List<Member> members, boolean real, int memberLimit) {
        this.team = team;
        this.viewer = viewer;
        this.members = List.copyOf(members);
        this.real = real;
        this.memberLimit = memberLimit;
    }

    /**
     * The roster of a team, as seen by one viewer.
     *
     * <h2>Why the names are a function and not looked up here</h2>
     *
     * <p>Because a member's name lives somewhere this class cannot reach. On a server it is the player
     * list; on a client it is whatever the tab list or a payload carried; in a test it is a map. Taking
     * a {@code Function<UUID, String>} is what lets all three work and keeps this file free of the game
     * — and the alternative, an overload that took a {@code MinecraftServer}, would put a game class in
     * the toolkit for the sake of one convenience.
     *
     * <p>The function must not return null. A missing name is the caller's to fall back for, because
     * only the caller knows what a sensible fallback is — a client that has not been told a player's
     * name yet wants something different from a server reading a profile.
     *
     * @param team   the team. A solo team is accepted and yields one row; see {@link #isReal}
     * @param viewer whose eyes this is. Determines {@code self}, {@code canRemove} and {@link #canDisband}
     * @param names  how to spell a member's name
     * @param online who is connected right now. <b>A parameter rather than something read off the
     *     team</b>, because a team is a stored fact about who belongs to it and says nothing about who
     *     is online — that is a fact about a server, and only the caller has one. A caller that has no
     *     answer passes {@link Online#NOBODY}, which draws every row as offline rather than guessing.
     */
    public static PartyRoster of(Team team, UUID viewer, Function<UUID, String> names, Online online) {
        return build(team, viewer, names, online, 0);
    }

    /**
     * The same roster, with the server's stated member limit.
     *
     * <p>A private second entry point rather than a parameter on {@link #of}: a server reading a real
     * {@code Team} has no limit to hand over — the limit is a property of the team <i>manager</i> —
     * and the one caller that has both is {@link #fromParts}, whose snapshot carried the answer. A
     * public overload would invite a caller to invent a limit.
     */
    private static PartyRoster build(Team team, UUID viewer, Function<UUID, String> names,
                                     Online online, int memberLimit) {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(online, "online");

        // Sorted for the reason in the class note, and by mapping to a comparable tuple rather than by
        // an int comparator: `Comparator.comparingInt(...).reversed()` on authority alone leaves the
        // equal-rank case in the source's order, which is the unspecified order this exists to escape.
        List<Map.Entry<UUID, TeamRole>> ordered = new ArrayList<>(team.members().entrySet());
        ordered.sort(Comparator
                .comparing((Map.Entry<UUID, TeamRole> entry) -> !entry.getKey().equals(team.owner()))
                .thenComparing(entry -> -entry.getValue().authority())
                .thenComparing(entry -> names.apply(entry.getKey())));

        List<Member> rows = new ArrayList<>(ordered.size());
        for (Map.Entry<UUID, TeamRole> entry : ordered) {
            UUID id = entry.getKey();
            rows.add(new Member(
                    id,
                    names.apply(id),
                    entry.getValue(),
                    id.equals(viewer),
                    id.equals(team.owner()),
                    team.canActOn(viewer, id),
                    team.canTransfer(viewer, id),
                    online.isOnline(id)));
        }

        return new PartyRoster(team, viewer, rows, team.persistent(), memberLimit);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The members, in the order they are drawn. */
    public List<Member> members() {
        return members;
    }

    /**
     * Whether this is a party other than the player being alone.
     *
     * <p>Carried through from {@code Team.persistent} rather than re-derived from the member count,
     * and the distinction is not academic: a <b>newly created party of one</b> has the same size as a
     * solo team and is a completely different thing. A panel that inferred "I am alone" from
     * {@code size() == 1} would show a party as no party at all, which is the state a player is in for
     * the ten seconds after they create one.
     */
    public boolean isReal() {
        return real;
    }

    /** How many members there are. */
    public int memberCount() {
        return members.size();
    }

    /**
     * Whether the viewer may leave.
     *
     * <p>False for a solo player, and that is the honest answer rather than a disabled button: there is
     * no party to leave. The manager's own {@code leave} returns false for the same case.
     */
    public boolean canLeave() {
        return real;
    }

    /**
     * Whether the viewer may disband the party.
     *
     * <p>Owner only — the same answer the stored manager gives, where a disband by anybody else returns
     * false. Not gated on the party having more than one member: an owner alone in a party may still
     * want to dissolve it, and the alternative would be a party that cannot be got rid of.
     */
    public boolean canDisband() {
        return real && team.owner().equals(viewer);
    }

    /**
     * Whether the viewer may invite under the party's policy. {@link Team#canInvite}, asked here so
     * the panel's Invite row and the server's refusal are one rule.
     *
     * <p>False for a solo team, where there is no party to invite anybody to.
     */
    public boolean canInvite() {
        return real && team.canInvite(viewer);
    }

    /** Whether the viewer may rename the party. Owner only, like {@link #canDisband}. */
    public boolean canRename() {
        return real && team.isOwner(viewer);
    }

    /** Whether the viewer may change the party's settings. Owner only. */
    public boolean canSetPolicy() {
        return real && team.isOwner(viewer);
    }

    /**
     * Whether an ordinary member may invite, as the party's policy currently says.
     *
     * <p>The switch's state rather than the permission: the panel draws the toggle from this and the
     * Invite row from {@link #canInvite}, and neither answers the other's question.
     */
    public boolean membersCanInvite() {
        return team.policy().membersCanInvite();
    }

    /** Whether the party is open for anyone to join without an invitation. */
    public boolean openJoin() {
        return team.policy().openJoin();
    }

    /**
     * How many members the party may hold, or <b>zero</b> when the server did not say.
     *
     * <p>Zero is the honest answer for a source with its own limit — a foreign parties mod — and for a
     * roster built from a live {@code Team}, which has no limit to read. The header draws a count
     * without the cap then, rather than inventing one; see {@link #hasMemberLimit()}.
     */
    public int memberLimit() {
        return memberLimit;
    }

    /** Whether a limit is known, so a header may write {@code 3/8} rather than {@code 3 members}. */
    public boolean hasMemberLimit() {
        return memberLimit > 0;
    }

    /** How many rows carry a Remove button. Diagnostics, and the count a test asserts against. */
    public int removableCount() {
        return (int) members.stream().filter(Member::canRemove).count();
    }

    // ------------------------------------------------------------------
    // Laying out
    // ------------------------------------------------------------------

    /**
     * The member rows as a stack, one row each.
     *
     * <p>No heading and no actions: this is the roster, and a caller that wants a title above it or
     * buttons below it adds them to this stack — which is what the screen does, because the title is a
     * translation string and the actions are a screen's business rather than a team's.
     *
     * <p>The gap goes <b>before</b> each row after the first, so the list does not end with one. A
     * trailing gap places no slot, so {@code Layout.height()} does not count it — which is the note
     * {@code OverlayLayout} records at length, and the reason spacing is done from the front here.
     */
    public Stack composition() {
        Stack stack = Stack.stack();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                stack.gap(ROW_GAP);
            }
            // The right inset reserves the Remove button's column on *every* row, including ones that
            // have no button. That is deliberate: without it, a member's name would run under the next
            // row's button, and the two rows would have different text widths for a reason nothing on
            // screen explains.
            stack.row(members.get(i).key(), ROW_HEIGHT,
                    new Insets(0, 0, actionStrip(), 0));
        }
        return stack;
    }

    /**
     * The rows placed in a column of {@code width}, and the height they come to.
     *
     * <p>A built {@link Layout} rather than a {@link Stack}, so that the height and the positions are
     * one computation — the property the kit exists for, and here it is what makes a scroll range
     * honest. The screen draws from this and asks it where each row went; nothing recomputes a y.
     */
    public Layout stack(int width, Measure measure) {
        return composition().build(Math.max(0, width), measure);
    }

    /**
     * The room every member row reserves for its owner controls: Transfer, then Remove.
     *
     * <p>One function, because the inset the rows are built with and the x the buttons are placed at
     * have to be the same arithmetic — and they are read by two different methods.
     */
    public static int actionStrip() {
        return TRANSFER_WIDTH + ROW_GAP + REMOVE_WIDTH + REMOVE_INSET * 2;
    }

    /**
     * Where a member's Transfer button sits, given the row it belongs to.
     *
     * <p>Left of the Remove button, inside the same strip. Null for a member the viewer may not hand
     * the party to, exactly as {@link #removeSlot} is null for one they may not remove: a caller
     * iterating the roster skips the nulls and cannot place a button whose permission it forgot.
     */
    public static Slot transferSlot(Member member, Slot row) {
        Objects.requireNonNull(member, "member");
        if (row == null || !member.canTransfer()) {
            return null;
        }
        int width = Math.min(TRANSFER_WIDTH, Math.max(0, row.width()));
        return new Slot(member.transferKey(),
                row.right() - REMOVE_INSET - REMOVE_WIDTH - ROW_GAP - width,
                row.y() + REMOVE_INSET,
                width,
                Math.max(0, row.height() - REMOVE_INSET * 2));
    }

    /**
     * Where a member's Remove button sits, given the row it belongs to.
     *
     * <p>A static function of the row's own {@link Slot}, so the button cannot be placed from a second
     * derivation of where the row is. The caller looks the row up by {@link Member#key()} and hands it
     * here; between them there is exactly one expression for "where does this row start".
     *
     * <p>Returns null for a member the viewer may not remove, and null is the useful answer rather than
     * an empty slot: a caller iterating the members and asking for each button can skip the nulls, and
     * cannot accidentally place a button whose permission it forgot to check.
     */
    public static Slot removeSlot(Member member, Slot row) {
        Objects.requireNonNull(member, "member");
        if (row == null || !member.canRemove()) {
            return null;
        }
        int width = Math.min(REMOVE_WIDTH, Math.max(0, row.width()));
        return new Slot(member.removeKey(),
                row.right() - width - REMOVE_INSET,
                row.y() + REMOVE_INSET,
                width,
                Math.max(0, row.height() - REMOVE_INSET * 2));
    }

    /**
     * The roster a set of members describes, with no {@link Team} involved.
     *
     * <h2>Why this exists beside {@link #of}, which takes a team</h2>
     *
     * <p>Because a <b>client</b> has no team. A party is server state, so what reaches a client is the
     * membership it was sent — ids, names and roles — and {@code PartyRoster.of} has nothing to take.
     * This is the same roster built from the same three facts, so both sides of a wire draw the same
     * panel, and the rules about order and authority are stated once.
     *
     * <p>The consequence worth naming: every derived answer here is <b>recomputed</b> rather than
     * carried. {@code canRemove} is not on the wire, because a boolean that travels can disagree with
     * the roles it was computed from — and the disagreement would be a Remove button the server
     * refuses, which is the exact fault {@link Team#canActOn} exists to prevent.
     *
     * @param teamId   the party's id
     * @param teamName its name
     * @param owner    who owns it
     * @param roles    every member and their rank. Order is not read; see the class note
     * @param viewer   whose eyes this is
     * @param names    how to spell a member's name
     * @param policy   the party's policy as the wire carried it. Null reads as the default, so a
     *                 server that predates the field leaves a switch in its documented initial state
     *                 rather than in an unknown one
     * @param memberLimit how many members the party may hold, or zero when the server did not say
     */
    public static PartyRoster fromParts(UUID teamId, String teamName, UUID owner,
                                        Map<UUID, TeamRole> roles, UUID viewer,
                                        Function<UUID, String> names, Online online,
                                        TeamPolicy policy, int memberLimit) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(names, "names");

        // A team rebuilt from the parts, so that `canActOn` -- the one copy of the rule -- is what
        // decides `canRemove` here too. Hand-rolling the comparison would be a second implementation of
        // authority on the client, which is precisely the drift this arrangement avoids.
        Team rebuilt = new Team(teamId, teamName == null ? "" : teamName, owner, roles,
                Map.of(), policy == null ? TeamPolicy.DEFAULT : policy, 0L, true);
        return build(rebuilt, viewer, names, online, memberLimit);
    }

    /**
     * The member a Remove button key names, or null.
     *
     * <p>Here rather than at the call site so the prefix is spelled once. A screen that stripped it
     * itself would be the second place that knows how a key is built, and the failure would be a click
     * that routes to a member whose id contains the prefix — which is unreachable today and would
     * become reachable the first time somebody changed the prefix.
     */
    public static UUID removeTarget(String key) {
        if (key == null || !key.startsWith(REMOVE_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(key.substring(REMOVE_PREFIX.length()));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "PartyRoster(" + memberCount() + " member(s)"
                + (real ? "" : ", solo") + ", viewer may remove " + removableCount() + ")";
    }
}
