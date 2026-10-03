# Layout and scrolling

A screen that measures its rows once to draw them and again to hit-test them is a screen where the two
passes can disagree, and the disagreement is invisible until the day a row moves. The kit's answer is
one object that does both: you describe the rows, it lays them out, and it hands back where each one
went — so a click cannot land on a different row than the one that was drawn.

## `Stack` builds, `Layout` answers

```java
Layout layout = Stack.stack()
        .heading("Rewards")
        .paragraph("Claimed automatically when the quest completes.")
        .divider(6, 6)
        .row("claim-all", 20)
        .build(width, measure);

Slot row = layout.at(mouseX, mouseY);          // null when nothing is there
if (row != null && row.key().equals("claim-all")) { ... }
```

`Stack` is a builder: `gap(height)`, `divider(spaceAbove, spaceBelow)`, `heading(label)`,
`paragraph(prose)`, `paragraph(key, prose)`, `text(key, text, align)`, `row(key, height[, insets])`,
`block(key, width, height, align[, insets])`, `append(other)`. Each element carries a **key** — and
that is the design: a hit test that returned coordinates would make the screen match on coordinates,
so moving a row by changing a metric elsewhere would leave a test that still compiles and now names
the wrong row. The key moves with the row.

`Layout` is the result: `slots()`, `height()`, `isEmpty()`, `at(x, y)`, `slot(key)`, `visibleIn(top,
bottom)`, and `visibleInViewport(viewportTop, viewportBottom, scrollOffset)` for a scrolled list.
`Measure` is the only thing the layout needs to know about a font.

## `Viewport`: one transform, not four copies

A pan-and-zoom surface normally carries a `panX`, a `panY` and a `zoom`, and then writes the same
three-line conversion at every site that needs it — once to draw a node, once to hit-test one, once to
work out what a scroll moved. Four copies of one transform, and the copies drift: the one that
hit-tests is the one nobody re-reads. So the transform lives in one object:

```java
Viewport viewport = Viewport.of(0.25F, 3F).bounds(left, top, width, height);

viewport.panBy(dx, dy);
viewport.setScale(next);                 // returns whether it changed, clamped
int screenX = viewport.screenX(contentX);
float contentX = viewport.contentX(mouseX);
```

`viewport.fixed()` is the no-zoom variant a scrolling list wants; `dragTo(screenX, screenY, grabX,
grabY)` pans so a grabbed content point stays under the pointer; `setContentSize`, `scrollY`,
`maxScrollY` and `scrollBy` are the scroll half.

## `ScrollView`: widgets inside a scrolled region

A hand-drawn row cannot take focus, cannot be narrated, cannot be reached by tab, and has no hover
state unless the screen reimplements one per row. All of that is already solved by a widget — so the
`ScrollView` owns the widgets and pays the repositioning cost once, when the content scrolls, rather
than the screen doing it per row:

```java
ScrollView view = ScrollView.of(viewport).put("done", doneButton);
...
view.apply(layout, contentWidth);        // repositions every widget to its slot
```

`put(key, widget[, shape])` names a widget into the layout; `apply(layout, contentWidth)` moves them
after a layout pass and clamps the scroll against the new height — which is why a rebuild under a
scrolled reader leaves the column where it was. `get(key)`, `size()`, `clear()`, `placedSlot(key)`,
and the culling counts (`placed()`, `culled()`) round it out. It draws its own scrollbar with
`drawScrollbar(renderer, trackColour, thumbColour)`, and exposes the track and thumb slots plus
`beginThumbDrag`/`dragThumbTo`/`endThumbDrag` for a screen that wants to drive it directly.

> [!TIP]
> The viewport is the kit's rather than a canvas's on purpose: moving content under a fixed window and
> scrolling a list are the same mapping, and two implementations of it would drift apart. A graph
> canvas and a settings list use the same object.
