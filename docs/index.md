# Armature documentation

Armature is the library Tasked is built on: layout, themes, shapes, a graph canvas, and one seam
between the code and the game's renderer. It is a standalone mod — it does not require Tasked, and it
can be installed on its own.

> [!NOTE]
> **This section is short on purpose.** Armature is at 0.1.0 and does nothing yet, so there is nothing
> to document. Its manual is written as the API appears, not ahead of it — a page describing a class
> that does not exist is worse than a page that admits there is nothing there.

## What is planned

The library's own reference — layout, theming, the shape vocabulary, and the renderer seam — arrives
as those parts are built. The build order is in the
[Armature repository](https://github.com/ellipog/armature/blob/main/plan.md).

Until then, what Armature is *for* is best read from the plan: a UI toolkit that knows nothing about
quests, so that a second mod can use it without inheriting Tasked's assumptions.
