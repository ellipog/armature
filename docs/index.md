# Armature documentation

Armature is the library Tasked is built on: the loader seam, the data and validation helpers, the
event, network and team APIs, and a UI toolkit — layout, themes, shapes, text models, widgets and a
property inspector — all drawn through one game-free renderer seam. It is a standalone mod: it does
not require Tasked, and it can be installed on its own.

> [!NOTE]
> **Armature is at 0.1.0, and the API is real.** The packages above are built and in use — Tasked's
> quest book, its editor and its tools panel are what they are built on. 0.x is the promise that the
> API may still move; it freezes at 1.0.0 the moment Tasked v1 ships.

## Where to start

| Page | What it is |
|---|---|
| [The README](https://github.com/ellipog/armature#readme) | What is in the library, package by package, and how to build, publish and deploy it |

The library's own reference pages are written as each part settles; the README is the map in the
meantime. What Armature is *for* is a UI toolkit that knows nothing about quests, so a second mod can
use it without inheriting Tasked's assumptions.
