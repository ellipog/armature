# Armature

Shared library and UI toolkit for **Fabric** and **NeoForge**, for **Minecraft 1.21.1**.

Armature is a standalone library mod. It is designed to be a hard dependency of
[Tasked](https://github.com/ellipog/tasked), a questing mod — but it does not require Tasked, and
it can be installed on its own. Nothing in here names its first consumer.

> **Status: 0.1.0 — the API is real and the toolkit is in use.** The platform layer, the data and
> validation helpers, the event, network and team APIs, and the first half of the UI toolkit are
> built. 0.x is deliberate: the API is free to move until Tasked v1 ships, and it freezes at 1.0.0
> the moment it does.

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
  item icons, player faces, blur, and scissored clips. `render/GuiGraphicsRenderer` is its 1.21.1
  implementation, and the toolkit draws through the interface.
- **`ui.kit`** — layout (`Stack` to `Layout` to `Slot`, with measurement and hit-testing),
  `Viewport` and `ScrollView` for pan, zoom, clamped scrolling and row culling, the text models
  (`TextField`, `TextArea`, selection, undo/redo, wrapping, codepoint-safe movement, and markdown
  through `RichText`), and the visual vocabulary (`RoundedRect`, `NineSlice`, `Outline`, `Colour`,
  `Easing`, `Tween`, `Motion`, `Hover`).
- **`ui.shape`** — a `Shape` is a span list per row, so drawing and hit-testing agree by
  construction. Ten named shapes — rectangle, circle, hexagon, tome, diamond, octagon, pentagon,
  gear, heart, rounded — plus rounded, rotated and point-sampled factories.
- **Themes** — a `Theme` record of colour tokens, sixteen built-ins, JSON patches loaded from
  `config/armature/themes`, and `Look`, the per-mod instance a mod owns. `ArmatureTheme` scopes a
  theme to a region while drawing.
- **Widgets and screens** — `ArmatureButton`, `ArmatureTextField`, `ArmatureTextArea`, and
  `ArmatureScreen` with `ArmatureLive`, which rebuilds the screen when watched state changes.
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
(`armature-fabric-1.21.1-0.1.0.jar`) — the `-sources` and `-javadoc` jars are not mods. The test
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
testModsDirFabric=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked Fabric/mods
testModsDirNeoForge=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked NeoForge/mods
```

## Layout

| Project | What goes there |
|---|---|
| `common/` | Compiled against vanilla only. `api/` is the public surface, `impl/` is internal and free to change, `client/` is the renderer seam and the toolkit. |
| `fabric/` | Fabric entry points: platform, registrar, events, key mappings. |
| `neoforge/` | NeoForge entry points for the same. |
| `docs/` | Documentation. |

`common/` cannot reference `fabric/` or `neoforge/`. That direction is enforced by the build, not
by convention.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template), with the
Forge subproject removed.

## Licence

MIT — see [LICENSE](LICENSE).
