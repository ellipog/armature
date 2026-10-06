# Armature

Shared library and UI toolkit for **Fabric** and **NeoForge**, for **Minecraft 1.21.1**.

Armature is a standalone library mod. Nothing in it is specific to any one consumer, and it can be
installed on its own.

> **Status: 0.1.0 — pre-1.0, and not yet a stability promise.** The platform layer, the data and
> validation helpers, the event, network and team APIs, and the UI toolkit are all built and in use,
> but the version is below 1.0 on purpose: a break in the API needs only a minor bump, so read the
> release notes before upgrading.

## What is in it

### The API — `common/src/main/java/dev/ellipog/armature/api/`

| Package | What it gives a mod |
|---|---|
| `api/platform` | The loader seam: environment kind, game and config directories, `isModLoaded` and `modVersion`. |
| `api/registry` | `SimpleRegistry<T>` for a mod's own Java-object registries, and `Registrar` — `register(id, supplier)` in the caller's namespace, duplicates rejected. |
| `api/event` | Eight server-thread events: server starting, started and stopping, player tick, join and leave, entity death, and command registration. |
| `api/net` | Payload declaration and registration with a direction and handlers, plus `sendToPlayer` and `sendToServer`. |
| `api/data` | A strict JSON parser that records a line and column per path, presence and type checks with "did you mean" suggestions, `Problems`/`DataProblem` reporting, codec helpers, and `TypeDispatch` for type-tagged objects. |
| `api/client` | Key-mapping declaration and screen opening that name no client class until a loader supplies the backend. |
| `api/teams` | `Team`, `TeamRole`, `TeamManager`, the team events, and a `Teams.of(server)` facade. |

### The client toolkit — `common/src/main/java/dev/ellipog/armature/client/`

- **One renderer seam.** `GuiRenderer` is a game-free drawing interface: fill, text, styled runs,
  item icons, player faces, blur, scissored clips, and a `batched` region — one submission for a
  stretch of drawing that would otherwise flush per fill on the unmanaged screen path. `render/
  GuiGraphicsRenderer` is its 1.21.1 implementation, and the toolkit draws through the interface.
- **`ui.kit`** — layout (`Stack` to `Layout` to `Slot`, with measurement and hit-testing),
  `Viewport` and `ScrollView` for pan, zoom, clamped scrolling and row culling, `ScrollBar` for the
  bar itself (drag, groove paging with a hold-repeat, wheel accumulation and three drawn states), the
  text models
  (`TextField`, `TextArea`, selection, undo/redo, wrapping, codepoint-safe movement, and markdown
  through `RichText`), and the visual vocabulary (`RoundedRect`, `NineSlice`, `Outline`, `Colour`,
  `Easing`, `Tween`, `Motion`, `Hover`).
- **`ui.shape`** — a `Shape` is a span list per row, so drawing and hit-testing agree by
  construction. Eleven named shapes — rectangle, circle, hexagon, tome, diamond, star, octagon,
  pentagon, gear, heart, rounded — plus rounded, rotated and point-sampled factories.
- **Themes** — a `Theme` record of colour tokens, the corner radius, the motion duration and curve,
  and the canvas background, with sixteen built-ins and JSON patches read from a themes directory the
  mod hands `Look`. The library never chooses that directory, so a mod owns its own appearance file
  rather than sharing one with everything else on the client. `Look` is the per-mod instance a mod
  owns, and `ArmatureTheme` scopes a theme to a region while drawing.
- **Canvas backgrounds** — a theme field rather than a feature of a screen: a flat surface, a
  procedural pattern drawn from arithmetic, or a tiled or covered image. A pattern is anchored either
  to the content, so it pans and zooms with the graph under it, or to the screen, so the content
  glides over it.
- **Widgets and screens** — `ArmatureButton`, `ArmatureTextField`, `ArmatureTextArea`,
  `ArmatureSlider` and `ArmatureSwitch`, and `ArmatureScreen` with `ArmatureLive`, which rebuilds the
  screen when watched state changes. `ArmatureScreens` is the screen registry and the opener.
- **`ui.inspect`** — the machinery behind a property editor: typed fields with parse errors, row
  kinds and metrics, and section panels a mod registers per type.
- **`ui.party`** — `PartyRoster`, the model behind a party panel: members, roles, self and owner
  flags, and who may be removed.

### Teams

`Teams.of(server)` resolves a server's parties in order: a provider a mod registered explicitly,
then FTB Teams, then Open Parties and Claims, then Armature's own stored teams — world-persisted,
and the fallback when nothing else is present. FTB Teams and Open Parties and Claims are
`compileOnly` and never reach a jar or the published POM; each adapter is only class-loaded behind
an `isModLoaded` check, so a server with neither loses nothing.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.80+ |
| FTB Teams / Open Parties and Claims | optional — read as team sources when present |

Fabric API is required on Fabric only. NeoForge needs nothing beyond NeoForge itself.

## Building

```cmd
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs`. Install the plain jar
(`armature-fabric-1.21.1-0.1.0.jar`) — the `-sources` jars are not mods. The test
suite is JUnit 5, headless, and part of `gradlew build`; the kit's layout and text models are
game-free by design, which is what lets them be asserted on without a running client.

### Using Armature as a library

```cmd
gradlew :common:publishToMavenLocal
```

publishes `dev.ellipog:armature-common-1.21.1:<version>` to the local Maven repository — which is
how a consumer compiles against Armature. The optional teams mods are not in that POM.

## Deploying to a local test profile

Three tasks copy the freshly built jars straight into the Modrinth App profiles named in
`gradle.properties`:

```cmd
gradlew deployFabric     :: Fabric profile only
gradlew deployNeoForge   :: NeoForge profile only
gradlew deployAll        :: both
```

Each one deletes the previous copies from the profile before copying, so you never end up with
`armature-...jar` and `armature-... (1).jar` sitting side by side — Minecraft picks whichever it
likes, and the resulting bug hunt is never worth it.

If your profiles are named something else, edit these two lines in `gradle.properties`:

```properties
testModsDirFabric=<your Modrinth profile folder>/mods
testModsDirNeoForge=<your Modrinth profile folder>/mods
```

## Layout

| Project | What goes there |
|---|---|
| `common/` | Compiled against vanilla only. `api/` is the public surface, `impl/` is internal and free to change, `client/` is the renderer seam and the toolkit. |
| `fabric/` | Fabric entry points: platform, registrar, events, key mappings. |
| `neoforge/` | NeoForge entry points for the same. |
| `buildSrc/` | The shared Gradle logic both loaders apply — the jar naming, the `sources` jar, and the deploy tasks. A new `gradle.properties` field has to be added to its `expandProps` map to reach the metadata. |
| `docs/` | Documentation: `docs/index.md` is the front door, `docs/api/` covers the mod-facing API, and `docs/toolkit/` the client toolkit. |

`common/` cannot reference `fabric/` or `neoforge/`. That direction is enforced by the build, not
by convention.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template), with the
Forge subproject removed.

## Licence

MIT — see [LICENSE](LICENSE).
