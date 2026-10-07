# Armature documentation

Armature is a shared library mod: a loader seam, a strict JSON reader, event, network and team APIs,
and a client UI toolkit — layout, scrolling, text models, shapes, themes, widgets and the machinery
behind a property editor — all drawn through one game-free renderer [[seam|seam]].

It is a standalone mod, and nothing in it is specific to any one consumer — a mod can use the toolkit
without inheriting another mod's assumptions.

> [!NOTE]
> **Armature is at 0.1.3 — pre-1.0.** Everything these pages describe is built and in use, but the
> version is below 1.0 deliberately: a break in the API needs only a minor bump, so read the release
> notes before upgrading rather than assuming the surface is frozen.

## What it needs

| | |
|---|---|
| Minecraft | 1.21.1, on Fabric or NeoForge |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.80+ |
| FTB Teams / Open Parties and Claims | optional — read as team sources when present, see [[armature:api/teams]] |

Fabric API is required on Fabric only. NeoForge needs nothing beyond NeoForge itself.

## Where to go

| Page | What it is |
|---|---|
| [[armature:api]] | The mod-facing API: the loader seam, registries, events, networking and the client seams |
| [[armature:api/data]] | The strict JSON reader, and the problem reports a mod's own file format wants |
| [[armature:api/teams]] | Teams, roles, and where a server's parties are read from |
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
Neither metadata file names a third-party mod — the loader, and Fabric API on Fabric, is the whole of
what they ask for — so there is nothing else to install first.
