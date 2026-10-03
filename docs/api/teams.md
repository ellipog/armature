# Teams

Parties — who is together, who leads, who may invite — are a thing several mods each reimplement, and
a server's players end up divided across them. Armature's answer is one API over whichever source the
server actually has, so a consumer asks a question and does not care who answers.

```java
TeamManager teams = Teams.of(server);
Team team = teams.teamOf(player.getUUID());   // Team.solo when nobody is in a party
```

## `Team`, `TeamRole`

`Team` is a record: an id, a name, an owner, the members with their roles, and the outstanding
invites. It answers the questions a party panel asks — `isMember`, `isInvited`, `roleOf`,
`roleOrMember`, `size()`, `memberIds()`, `displayName(...)` — and `canActOn(actor, target)` puts the
permission rule in one place, so a kick button and a kick command cannot disagree about who may kick
whom. `Team.solo(player)` is the one-member answer for a player in no party.

`TeamRole` is `OWNER` (3), `OFFICER` (2), `MEMBER` (1), with `authority()`, `outranks` and `isAtLeast`
for comparisons — an owner may do anything, an officer may invite and remove ordinary members, a
member may leave.

The record's `withMember`/`withoutMember`/`withInvite`/`withName` return a changed copy, which is what
lets a manager be backed by a file, an event, or a test without any of them mutating a shared object.

## `TeamManager`

The interface a caller holds: `teams()`, `byId(id)`, `teamOf(player)`, `realTeamOf(player)` (empty
rather than a solo team when the player is in none), `invitesFor(player)`, `allTeams()`, `teamCount()`.

The mutating calls — `create`, `invite`, `acceptInvite`, `leave`, `kick`, `disband` — are on the same
interface, but a manager that cannot change anything throws from them rather than silently doing
nothing. That is what makes read-only sources safe to hold: the calls exist, and they say what they
are. A manager that *can* change is a `MutableTeamManager`, and asking is how a caller checks before
offering a control.

## Where the parties come from

`Teams.of(server)` resolves in order, and remembers its answer for that server:

1. **A provider a mod registered explicitly** — the escape hatch, and the reason FTB Teams' classes
   are never loaded to ask whether FTB Teams is installed.
2. **FTB Teams**, if it is loaded and its manager is up and it holds at least one party.
3. **Open Parties and Claims**, under the same three conditions.
4. **Armature's own stored teams** — world-persisted, and the fallback that makes every server work,
   including the ones with neither optional mod installed.

"Holds at least one party" is not a detail. FTB Teams gives every player a permanent solo team on
first join, so resolving to it just because it is loaded would mean every player reads as being in a
party of one — and on a server where nobody has formed one, that is worse than the fallback.

FTB Teams and Open Parties and Claims are `compileOnly`: they are never in a jar and never in the
published POM, and each adapter's class is loaded only behind an `isModLoaded` check. A server with
neither loses nothing.

> [!TIP]
> Which source answered is not a mystery to guess at: the resolver logs it at boot, and a consumer can
> print `teams.name()` — Tasked's `/tasked party` does, because "are these the parties I think they
> are" is the question a server operator actually has.

## Events

`TeamEvents` publishes `TEAM_CREATED`, `MEMBER_JOINED`, `MEMBER_LEFT` and `TEAM_DISBANDED`, each a
one-method listener over a small event object. A source that can announce a change is often the only
way to find out one happened, which is why the events are part of the API rather than an extra.
