# Layout and scrolling

A screen that measures its rows once to draw them and again to hit-test them is a screen where the two
passes can disagree, and the disagreement is invisible until the day a row moves. The kit's answer is
one object that does both: you describe the rows, it lays them out, and it hands back where each one
went — so a click cannot land on a different row than the one that was drawn.

## `Stack` builds, `Layout` answers

```java
Layout layout = Stack.stack()
        .heading("Rewards")
        .paragraph("Claimed automatically when it completes.")
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
`moved(dx, dy)` shifts the whole answer without re-laying it out. `Measure` is the only thing the layout needs to know about a font.

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
`maxScrollY` and `scrollBy` are the scroll half. `zoomAt` zooms keeping the pointer's content point
still (answering whether the scale moved, so a caller at a limit skips the redraw),
`zoomAboutCentre` is the pointerless form, and `centreOn` frames a content box without clamping.

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
`scrollBy`/`scrollTo`, and the culling counts (`placed()`, `culled()`) round it out. Its bar is a `ScrollBar` — `bar()`, bound to
the strip just outside the viewport — and drawing it is one call:
`bar().draw(renderer, ArmatureScrollStyle.skin(), mouseX, mouseY, now)`.

> [!IMPORTANT]
> **Every scroll re-places the widgets, and that is not something a caller arranges.** `ScrollView` sets
> the bar's after-scroll hook to its own placement pass, so a wheel, a drag and a groove press all move
> the children along with the offset. It is worth knowing *why* it is wired inside the constructor rather
> than at a call site: the offset and the widgets are two things, and the first version of the bar moved
> only one of them. A list whose rows are drawn from `scrollY()` every frame hid it; the two views whose
> widgets are placed only during a rebuild did not, and showed a list scrolling under controls that stayed
> still. `put` also hides a widget until a placement gives it somewhere to be, so a control that has not
> been placed is never drawn at the origin.

## `ScrollBar`: the bar as a control

A scrollbar is not decoration: a bar beside a list is where the pointer goes when the list is long, and
a bar that cannot be dragged is a picture of a scrollbar. One class owns the whole of it, for every list
in a screen — the strip it is drawn in, the grip's geometry, the hit test, both gestures and the wheel:

```java
ScrollBar bar = new ScrollBar(viewport);          // one per list, kept across frames
bar.strip(x, y, width, height);                   // per frame, like Viewport.bounds
bar.pitch(rowHeight);                             // one row: what a notch moves, what a page keeps
...
if (bar.press(mouseX, mouseY, now)) { ... }       // grip → drag, groove → page and repeat
bar.dragTo(mouseY);                               // the inverse of the grip's own formula
bar.advance(now);                                 // the hold-repeat, once a frame
bar.release();
bar.wheel(wheelDelta);                            // a delta, in notches; the fraction is carried
bar.draw(renderer, ArmatureScrollStyle.skin(), mouseX, mouseY, now);
```

`thumb()`, `track()` and `hit()` are the geometry; `pageStep()` is one screen less a row. The four
colours come from a `ScrollBar.Skin` rather than from the theme, so the kit stays theme-free —
`ArmatureScrollStyle.skin()` builds it from the theme's two scrollbar tokens, deriving the hover and held
shades *away from the track's own luminance*, which is what makes a light theme darken its grip on hover
and a dark theme brighten it. The three states are distinguishable without a colour being chosen for
them: the resting grip is the theme's thumb colour, a hovered one a shade of it, and a held one a longer
shade plus a notch down its middle.

> [!TIP]
> The grip is held rather than widened. Every container that draws a bar reserved room for a three-pixel
> one, so a hover that grew it would draw over content in any layout whose arithmetic was not re-derived
> for it — and the state is legible without that.

> [!TIP]
> The viewport is the kit's rather than a canvas's on purpose: moving content under a fixed window and
> scrolling a list are the same mapping, and two implementations of it would drift apart. A graph
> canvas and a settings list use the same object.
