# Property panels

A property editor over *other people's* types is a panel over a set it cannot enumerate: an addon
arrives with a kind this build has never heard of, and the panel still has to show something. The
`ui.inspect` package is that machinery — rows, typed fields, layout metrics and a registry with a
fallback — so a mod supplies only what a field of its own type means.

## `InspectRow`: what a panel is made of

A panel is a list of rows, and a row knows what it *is* rather than only what it says:

```java
List<InspectRow> rows = List.of(
        InspectRow.heading("counts", "Counts"),
        InspectRow.field("count", "Count", "8"),
        InspectRow.toggle("consume", "Consume items"),
        InspectRow.action("pick", "Pick an item…"),
        InspectRow.warning("missing", "This item does not exist in this build"));
```

The kinds are `FIELD` (label, editable value in the strip at its right), `VALUE` (read-only), `TOGGLE`
(two-state control), `ACTION` (the whole row is a control), `HEADING`, `ENTRY` (a list entry's name
with its own controls), `WARNING` (a section name in the voice that says something is wrong), and
`RAW` (a value too long or too strange for a field, editable as a whole). `hasStrip()`,
`isControl()` and `isHeading()` are the questions a renderer asks rather than switching on the kind
itself.

## `InspectField<T>`: how a value becomes text and back

```java
public interface InspectField<T> {
    String label();
    String format(T value);
    Result<T> parse(String text);   // Result.ok(value) or Result.bad("why not")
}
```

This is where the honest failures live. A field that accepts `"twenty"` for a count and commits it as
zero is not a drawing fault or an arithmetic fault, and no screenshot shows it — so `parse` answers
with a value *or a reason*, and the reason is drawn beside the row. The package ships the ordinary
ones — text, integers with bounds, and so on — and a mod writes one for a type only it understands.

## `InspectLayout`: metrics, and two shapes of row

Layout is not the panel's business, so the metrics and the stack live here:

| | |
|---|---|
| `ROW_HEIGHT` 18, `HEADING_HEIGHT` 15, `ROW_GAP` 1, `SECTION_GAP` 7 | The side-by-side metrics. |
| `STACKED_LABEL_HEIGHT` 11, `STACKED_CONTROL_HEIGHT` 18, gaps 4 / 9 | The stacked metrics. |

`Mode.SIDE_BY_SIDE` puts the label left and the control in the strip at its right; `Mode.STACKED`
puts the label on its own band and the control across the row beneath it. Which one fits is a property
of the panel rather than of a row — a narrow panel stacks — so the mode is the caller's, and
`stack(rows[, mode])` and `build(rows, width, measure[, mode])` take it. A side-by-side row with a
control reserves the strip with `stripRoom()`.

## `InspectPanels<V>`: one registry, and the fallback that is part of it

```java
InspectPanels<MyValue> panels = new InspectPanels<MyValue>()
        .register("mymod:counted", (type, value) -> rowsForCounted(value))
        .fallback((type, value) -> List.of(InspectRow.warning("unknown", "No panel for " + type)));

List<InspectRow> rows = panels.rowsFor(type, value);   // never null
```

`register(type, panel)` is per type and re-registering replaces, deliberately. `fallback(...)` is what
an unregistered type is shown as, and it is part of the registry rather than an error path: "this
build cannot edit that" is still an answer a reader can act on. `known(type)` says whether a panel was
written — the fallback is not a panel, and does not count.

## The party roster

`ui.party` is the other model a panel draws. `PartyRoster.of(team, viewer, names, online)` turns a
[[armature:teams|Team]] into rows that already know what the viewer may do:

```java
PartyRoster roster = PartyRoster.of(team, viewerId, nameLookup, onlineLookup);
for (PartyRoster.Member m : roster.members()) {
    // m.key() and m.removeKey() place the row and its Remove button; m.label() says
    // the name, with "(you)" appended when it is the viewer.
}
```

A `Member` carries the id, name, role, and four facts a panel would otherwise re-derive — `self`,
`owner`, `canRemove` (the same rule the server will apply) and `online`. One of those is worth
calling out: `isReal()` distinguishes "a party of one you just created" from "you are alone", which a
panel inferring it from `size() == 1` would get wrong for the ten seconds after the party is made.
