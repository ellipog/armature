# Armature

Shared library and UI toolkit for **Fabric** and **NeoForge**, for **Minecraft 1.21.1**.

Armature is a standalone library mod. It is designed to be a hard dependency of
[Tasked](https://github.com/ellipog/tasked), a questing mod — but it does not require
Tasked, and it can be installed on its own.

> **Status: 0.1.0 — skeleton.** It builds on both loaders, loads into the game, and logs
> a few lines on startup. It does nothing else yet. The build order lives in `plan.md`.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.80+ |

## Building

```cmd
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs`. Install the plain jar
(`armature-fabric-1.21.1-0.1.0.jar`) — the `-sources` and `-javadoc` jars are not mods.

## Deploying to a local test profile

Three tasks copy the freshly built jars straight into the Modrinth App profiles named in
`gradle.properties`:

```cmd
gradlew deployFabric     :: Fabric profile only
gradlew deployNeoForge   :: NeoForge profile only
gradlew deployAll        :: both
```

Each one deletes the previous copies from the profile before copying, so you never end up
with `armature-...jar` and `armature-... (1).jar` sitting side by side — Minecraft picks
whichever it likes, and the resulting bug hunt is never worth it.

If your profiles are named something else, edit these two lines in `gradle.properties`:

```properties
testModsDirFabric=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked Fabric/mods
testModsDirNeoForge=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked NeoForge/mods
```

## Layout

| Project | What goes there |
|---|---|
| `common/` | Compiled against vanilla only — the bulk of the code. Cannot see either loader. |
| `fabric/` | Fabric entry point and anything Fabric-specific. |
| `neoforge/` | NeoForge entry point and anything NeoForge-specific. |

`common/` cannot reference `fabric/` or `neoforge/`. That direction is enforced by the
build, not by convention.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template),
with the Forge subproject removed.

## Licence

MIT — see [LICENSE](LICENSE).
