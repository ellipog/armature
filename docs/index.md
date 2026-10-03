# Armature documentation

Armature is the shared library under Tasked: a loader seam, a strict JSON reader, event, network and
team APIs, and a client UI toolkit — layout, scrolling, text models, shapes, themes, widgets and the
machinery behind a property editor — all drawn through one game-free renderer seam.

It is a standalone mod. It does not require Tasked; Tasked requires it, and nothing in Armature names
its first consumer, so a second mod can use the toolkit without inheriting a questing mod's
assumptions.

> [!NOTE]
> **Armature is at 0.1.0, and the API is real.** Everything these pages describe is built and in use —
> Tasked's quest book, its editor and its tools panel are what they are built on. 0.x is deliberate:
> the API may still move, and it freezes at 1.0.0 the moment Tasked v1 ships.

## What it needs

| | |
|---|---|
| Minecraft | 1.21.1, on Fabric or NeoForge |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.80+ |
| FTB Teams / Open Parties and Claims | optional — read as team sources when present, see [[armature:teams]] |

Fabric API is required on Fabric only. NeoForge needs nothing beyond NeoForge itself.

## Where to go

| Page | What it is |
|---|---|
| [[armature:api]] | The mod-facing API: the loader seam, registries, events, networking and the client seams |
| [[armature:data]] | The strict JSON reader, and the problem reports a mod's own file format wants |
| [[armature:teams]] | Teams, roles, and where a server's parties are read from |
| [[armature:toolkit]] | The client toolkit: the renderer seam, and a map of the rest |

## Using it as a library

A mod compiles against Armature's published artifact rather than its source tree, so the first step in
a consumer's build is to publish it:

```cmd
gradlew :common:publishToMavenLocal
```

That puts `dev.ellipog:armature-common-1.21.1:<version>` in the local Maven repository. The optional
team mods are `compileOnly` and are deliberately not in that POM — a consumer does not inherit a
dependency on a mod it may not run.

Installing it beside a consumer is a normal mod install: drop the jar in `mods/`, on whichever loader.
Both metadata files declare nothing beyond the loader itself, so there is nothing to install first.
