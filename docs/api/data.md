# Reading JSON

A mod that reads its own files by hand makes the same three mistakes, in the same order. It ignores a
field it does not recognise, so a typo produces a silently blank value. It reports a failure with a
file name and not a line, so the author compares two files character by character. And it crashes on
a malformed file at the wrong moment, in a stack trace that names the parser rather than the field.

The `api/data` package is that whole job done once: a parser that remembers **where** everything was,
presence and type checks that write into a shared report, and a problem shape that reads like a
compiler's.

## `JsonDocument`

```java
JsonDocument document = JsonDocument.parse(fileName, source);   // throws JsonParseException
Optional<JsonElement> title = document.get("$.title");
JsonLocation where = document.nearestLocation("$.tasks[2].count");
```

`parse` records a line and column for every path as it goes. The methods after that are the questions
a reader asks: `get(path)`, `has(path)`, `location(path)` (exact), `nearestLocation(path)` (where it
*would* have been, for a missing field), `lineText(line)` (the source line, for showing it), and
`locationCount()`. `name()` and `root()` name the file and hand back the parsed tree; nesting past
128 deep is refused rather than recursed into. **A path is rooted at `$`** — `$.title`, `$.tasks[2].count` — and one that is not is
not a path this reader knows: it answers empty rather than guessing, so a path written without the root
reads as a field that is simply missing.

## `Checks`

The checking that every format repeats, so it is written once — each call records a problem rather
than throwing, and returns the value when it was there:

```java
var problems = new Problems();
Optional<String> id = Checks.string(document, "$.id", problems);
OptionalInt count = Checks.optionalInt(document, "$.count", problems);
List<String> tags = Checks.stringList(document, "$.tags", problems);
```

`string`/`optionalString`, `integer`/`optionalInt`, `optionalBool`, `object`/`optionalObject`,
`array`/`optionalArray`, `stringList`/`optionalStringList`, `id`/`idList` (lowercase letters, digits
and underscores, at most 64 characters), `exists`, and `nameOf` — the last takes the
field name off the end of a path, which is how a "missing required field" message can say *which* field.
`rejectUnknown` reports every field outside the allowed set, with a did-you-mean where one is close;
`kindOf` and `describe` say what a value is, for the message. `parse(codec, input)` runs a codec
inside the same report instead of throwing — with an overload taking the caller's own ops — and
`clampedInt`, `clampedDouble`, `wrappedInt` and `jsonObject` are the small codecs a format reuses.
`JsonWrite.atomically(path, text)` is the write half: through a temp file and an atomic move, so a
crash cannot leave half a file. The path syntax is the same one throughout: `$.tasks[2].count`.

## `Problems` and `DataProblem`

`Problems` is the report: `error(document, path, message)`, `warn(...)`, `add(...)`, plus `forFile`,
`hasErrorsIn`, `all()`, and error/warning counts. A file is decoded only if `hasErrorsIn` its name is
false — one mistake, one message.

A `DataProblem` is a record — `file`, `line`, `column`, `path`, `severity`, `message` — and its
`render()` is the line every reader of these docs will see in a log:

```
entries/01.json:14:9: error: unknown field "titl" - did you mean "title"?
    valid fields here: description, icon, id, title
```

Problems sort by file, then line, then column, so a log reads in source order. The `path` field is the
JSON path the fault sits at, and it is carried rather than rendered: `render()` is the one line a
caller gets, because every reader of it — a log, a panel, a toast — shows one line per problem, and a
second line reading `at $.tasks[2]` became a card of its own in the one of those that has no room for
a continuation.

## Codec helpers

`Codecs.enumByName(MyEnum.class)` is a codec for an enum spelled by its name in the file — the name
lowercased on both sides, so a file may write `QUAD_OUT` or `quad_out` and mean the same constant, with
no second vocabulary to keep in step.

## Type-tagged objects

A format where one field chooses the rest — a list of tasks, where `"type": "some_mod:item"` decides
which fields mean anything — wants the type's fields **flat at the object's own level**, and a good
error when the type is unknown:

```java
Codec<MyTask> codec = TypeDispatch.codec(
        "task",                 // the kind, for the error message
        "type",                 // the field that chooses
        MyTask::type,           // how to read it back
        () -> MY_SPECS);        // Supplier<Collection<TypeSpec<MyTask>>>
```

An unknown type fails with the known ones listed. A `MapCodec` per spec is what keeps `count` at the
task's own level rather than nested under a `task` object, and `TypeSpec` pairs an id with its codec —
plus the type's field names, which is the set `rejectUnknown` checks a file against. The overload
taking an unknown-type fallback is the additive-compatibility story: one addon's type in a file must
not cost the author everything else in it. This is the shape a mod's task and reward types are built with.
