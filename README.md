# Armature

The shared library and UI toolkit beneath the modern ecosystem, for Fabric and NeoForge.

Armature is a standalone library mod: a loader seam, a strict JSON reader, event,
network, and team APIs, plus a client toolkit — layout, scrolling, text models, shapes,
themes, widgets, and the machinery behind a property editor — all drawn through one
game-free renderer seam.

Nothing in it is specific to any one consumer, and it installs on its own. A mod can
use the toolkit without inheriting another mod's assumptions.

> **Before its first stable release.** Everything below is built and in use, but the API
> is still allowed to break. Read the release notes before upgrading rather than
> assuming the surface is frozen.

## What is in it

### The API — `common/src/main/java/dev/ellipog/armature/api/`

| Package | What it gives a mod |
|---|---|
| `api/platform` | The loader seam: environment kind, game and config directories, `isModLoaded` and `modVersion`. |
| `api/registry` | `SimpleRegistry` for a mod's own Java-object registries, and `Registrar` — register in the caller's namespace, duplicates rejected. |
| `api/event` | Server-thread events: server starting, started and stopping, player tick, join and leave, entity death, and command registration. |
| `api/net` | Payload declaration and registration with a direction and handlers, plus sending to a player and to the server. |
| `api/data` | A strict JSON parser that records a line and column per path, presence and type checks with "did you mean" suggestions, problem reporting, codec helpers, and type dispatch for type-tagged objects. |
| `api/client` | Key-mapping declaration and screen opening that name no client class until a loader supplies the backend. |
| `api/teams` | `Team`, `TeamRole`, `TeamManager`, the team events, and a `Teams.of(server)` facade. |

### The client toolkit — `common/src/main/java/dev/ellipog/armature/client/`

- **One renderer seam.** `GuiRenderer` is a game-free drawing interface: fill, text,
  styled runs, item icons, player faces, blur, scissored clips, and a `batched` region —
  one submission for a stretch of drawing that would otherwise flush per fill on the
  unmanaged screen path. The toolkit draws through the interface; the per-version
  implementation sits behind it, so a port swaps the implementation and the toolkit
  above it does not move.
- **`ui.kit`** — layout (`Stack` to `Layout` to `Slot`, with measurement and
  hit-testing), `Viewport` and `ScrollView` for pan, zoom, clamped scrolling and row
  culling, `ScrollBar` for the bar itself (drag, groove paging with hold-repeat, wheel
  accumulation, three drawn states), the text models (`TextField`, `TextArea`,
  selection, undo/redo, wrapping, codepoint-safe movement, Markdown through
  `RichText`), and the visual vocabulary (`RoundedRect`, `NineSlice`, `Outline`,
  `Colour`, `Easing`, `Tween`, `Motion`, `Hover`).
- **`ui.shape`** — a `Shape` is a span list per row, so drawing and hit-testing agree
  by construction. Named shapes — rectangle, circle, hexagon, tome, diamond, star,
  octagon, pentagon, gear, heart, rounded — plus rounded, rotated, and point-sampled
  factories.
- **Themes** — a `Theme` record of colour tokens, corner radius, motion duration and
  curve, and canvas background, with a set of built-ins and JSON patches read from a
  themes directory the mod hands `Look`. The library never chooses that directory, so
  a mod owns its own appearance file instead of sharing one with everything else on
  the client. `Look` is the per-mod instance a mod owns.
- **Canvas backgrounds** — a theme field, not a screen feature: a flat surface, a
  procedural pattern drawn from arithmetic, or a tiled or covered image. A pattern
  anchors either to the content, so it pans and zooms with the graph under it, or to
  the screen, so the content glides over it.
- **Widgets and screens** — button, text field, text area, slider, and switch, plus a
  screen base with a live variant that rebuilds when watched state changes. A screen
  registry with an opener.
- **`ui.inspect`** — the machinery behind a property editor: typed fields with parse
  errors, row kinds and metrics, and section panels a mod registers per type.
- **`ui.party`** — the model behind a party panel: members, roles, self and owner
  flags, and who may be removed.

The layout engine, text models, and shapes are arithmetic over rectangles and strings,
so they are asserted in milliseconds without a client, a font, or a window.

### Teams

`Teams.of(server)` resolves a server's parties in order: a provider a mod registered
explicitly, then FTB Teams, then Open Parties and Claims, then Armature's own stored
teams — world-persisted, and the fallback when nothing else is present. The two
third-party integrations are compile-only and never reach a jar or the published POM.
Each adapter loads only behind an `isModLoaded` check, so a server with neither loses
nothing.

## Installing

Drop the jar in `mods/`, on either loader. Fabric also needs Fabric API — NeoForge
needs nothing beyond NeoForge itself.

See the Modrinth page for the supported Minecraft version and the required loader
versions. The FTB Teams and Open Parties and Claims integrations are optional: when
either is present it is read as a team source, and when neither is, Armature's stored
teams carry on as before.

## Using it as a library

A mod compiles against Armature's published artifact rather than its source tree — a
repository line and a coordinate, no clone and nothing to publish first. See the
releases page for the current coordinate, in this shape:

```groovy
repositories {
    maven { url = 'https://maven.ellipog.dev' }
}

dependencies {
    // The API to compile against. compileOnly: at runtime Armature is a separate
    // mod jar sitting beside yours in the mods folder, never bundled into it.
    compileOnly 'dev.ellipog:armature-common-<minecraft-line>:<release>'
}
```

The coordinate carries the Minecraft line, so artifacts for different Minecraft lines
are different artifacts and can never be confused. The loader-specific siblings live
on the same repository — they are the mod jars, for a dev run's runtime classpath.
The optional team mods appear in neither POM, so a consumer never inherits a
dependency on a mod it may not run.

To compile against an unreleased Armature instead — a change on its main branch before
any release:

```cmd
gradlew publishToMavenLocal
```

That places the artifact in the local Maven repository, where a sibling checkout
finds it first. `gradlew publishToLocalRepo` writes the same thing to `build/repo`
as a Maven tree, which is what the release workflow uploads. Every tagged release
publishes automatically, and a published release is permanent — a fix is a new
release, never a re-upload.

## Building

```cmd
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs` — install the plain jars,
not the `-sources` jars. The test suite is headless and runs as part of the build.
The kit's layout and text models are game-free by design, which is what lets them be
asserted on without a running client; the client tests draw through a recording
renderer rather than a running game.

Three tasks copy freshly built jars into the Modrinth App profiles named in
`gradle.properties`:

```cmd
gradlew deployFabric     :: Fabric profile only
gradlew deployNeoForge   :: NeoForge profile only
gradlew deployAll        :: both
```

Each deletes the previous copies first, so stale duplicates never sit side by side.
If your profiles are named differently, edit the two `testModsDir` lines in
`gradle.properties`.

## Layout

| Path | What goes there |
|---|---|
| `common/` | Compiled against vanilla only. `api/` is the public surface, `impl/` is internal and free to change, `client/` is the renderer seam and the toolkit. |
| `fabric/` | Fabric entry points: platform, registrar, events, key mappings. |
| `neoforge/` | NeoForge entry points for the same. |
| `buildSrc/` | Shared Gradle logic both loaders apply — jar naming, the `sources` jar, and the deploy tasks. A new `gradle.properties` field has to be added to its `expandProps` map to reach the metadata. |
| `docs/` | `docs/index.md` is the front door, `docs/api/` covers the mod-facing API, `docs/toolkit/` the client toolkit. |

`common/` cannot reference `fabric/` or `neoforge/`. That direction is enforced by
the build, not by convention.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template),
with the Forge subproject removed.

## Links

- Manual: `docs/index.md`, mirrored at [ellipog.dev](https://ellipog.dev/docs/armature/)
- Community: see the Discord link in `gradle.properties`

## Licence

MIT — see [LICENSE](LICENSE).
