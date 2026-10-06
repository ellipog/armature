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

The mutating calls — `create`, `invite(team, player)`, `acceptInvite(player)`, `leave`, `kick`,
`disband` — are on the same interface, but a manager that cannot change anything throws from them
rather than silently doing nothing. That is what makes read-only sources safe to hold: the calls
exist, and they say what they are. A manager that *can* change is a `MutableTeamManager`, and asking
is how a caller checks before offering a control.

## Optional operations and capabilities

Beyond the structural six there are operations a source may or may not be able to offer, and they are
asked about by name rather than by "can this source write at all":

```java
if (teams.supports(TeamFeature.RENAME)) { /* offer the pencil */ }
```

`TeamFeature` names them: `RENAME`, `TRANSFER`, `POLICY`, `OPEN_JOIN`, `INVITE_DECLINE`,
`INVITE_CANCEL`. The stored manager supports all of them; Open Parties and Claims writes membership
but has no party rename, transfer or policy, and FTB Teams is read-only here. A panel hides the
control rather than drawing a button whose only possible outcome is the refusal message.

- `invite(actor, team, player)` is the actor-aware form: the permission is `Team.canInvite`, which
  `TeamPolicy.membersCanInvite` feeds, and enforcing it needs to know who is asking.
- `acceptInvite(player, team)` accepts one specific invitation. A player can hold several, and a
  panel's Accept button sits on one row and must mean *that* one.
- `rename`, `transferOwnership`, `setPolicy`, `joinPublic`, `declineInvite` and `cancelInvite` each
  refuse with a named reason rather than throwing on a source that cannot.
- `memberLimit()` answers the configured cap for the stored manager — eight unless the server's
  settings say otherwise — and **zero** for a source that cannot say, so a header writes `3/8` rather
  than inventing a cap.

`Team` carries the data those operations need: `invites` is a map of `TeamInvite(inviter, at)` rather
than a set of ids, so an invitation row can show who asked and how long ago (a foreign source stores
the owner and zero — see `TeamInvite` on why zero is the honest unknown), and `policy` holds the two
switches. `TeamPolicy.DEFAULT` is invite-only with member invitations **on**, which is what version 1
files migrate to and what a solo team carries. `Team.limits`' rules — `canActOn`, `canInvite`,
`canTransfer` — live on the record so the manager enforcing them, the command explaining a refusal and
the panel deciding whether to draw a control are one answer.

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
> print `teams.name()` — a party command does, because "are these the parties I think they are" is the
> question a server operator actually has.

## The server's settings

`config/armature/config.json` is written on first start with the defaults below, and read on every
start after that:

```json
{"teams":{"maxMembers":8,"newPartyMembersCanInvite":true,"newPartyOpenJoin":false}}
```

- **`maxMembers`** — how many players a stored party may hold, `2` to `64`. It is enforced where the
  cap always was: an invitation past it is refused, an answer to an old invitation is refused when the
  party filled in the meantime, and a public join is refused when there is no room. It applies to
  parties that already exist from the moment the server reads the file.
- **`newPartyMembersCanInvite`** and **`newPartyOpenJoin`** — what a **newly created** party's
  `TeamPolicy` starts as. They are stamped on at creation and never touch an existing party: a switch
  a party has already set is the party's, not the server's.

A value outside the range is clamped, a value of the wrong type takes its default, and a key this
build does not know is ignored — each with a line in the log naming the key and what was used
instead. Nothing in the file is fatal: a server with a typo in it keeps its parties on the defaults,
and a file that could not be parsed is reported and left exactly as it was found. The file is read at
startup. Armature never re-reads it on its own: a second `install` — a consumer's own reload
command — takes effect at the next check rather than at the next server.

It applies to Armature's own stored parties, and only those. A server whose parties come from FTB
Teams or Open Parties and Claims gets that mod's limits and its own defaults, and this file is not
consulted for them. The effective number travels to a client with the party snapshot, so a panel's
`3/8` header and the server's refusal are one number rather than two expressions that agree today.

## Events

`TeamEvents` publishes `TEAM_CREATED`, `MEMBER_JOINED`, `MEMBER_LEFT` and `TEAM_DISBANDED`, each a
one-method listener over a small event object. A source that can announce a change is often the only
way to find out one happened, which is why the events are part of the API rather than an extra.
