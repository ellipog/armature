# Shapes

A [[shape|shape]] is **a list of spans per row** — for each row of a square, where it starts and where it
ends:

```java
public interface Shape {
    int[] spansOf(int row, int size);       // pairs: start, end
}
```

That representation is the whole design, because drawing and hit-testing read the same spans: the fill
walks them, and `containsLocal(x, y, size)` asks whether a point is inside them. A click therefore
lands on exactly what was drawn, for every shape, without a second implementation that can disagree —
which is the bug the alternatives pay for, since precomputed pixel-mask approaches hard-code their
shapes to a square grid.

The default `spans(row, size)` sanitises what `spansOf` returns — sorted, merged, clamped to the row —
so a shape cannot express a negative span or an unsorted pair even by accident. `containsLocal` is a
default method built on the same spans, and a shape that has a cheaper answer may override it.

## One definition each, in the unit square

Every built-in is written as a point test over the node's width and height as a single unit:

```java
public interface Unit {
    boolean isInside(double u, double v, int size);   // u, v in [-0.5, 0.5]
}
```

`Shapes.unit(unit)` samples that into the span table, so the definition is one piece of arithmetic
rather than one per row. Two things follow that are worth knowing:

- **A turn is exact.** `rotated` rotates the definition itself, so nothing is re-sampled
  twice and a curved shape turned by 41 degrees is the curve at 41 degrees rather than a rotated
  raster.
- **The definition must answer false outside the square.** The turn's fit measurement asks about points
  beyond it, and a test that said true at any distance would have no reach to measure.

A few shapes are drawn on the pixel grid rather than at the pixel centre — the rounded rectangle, the
diamond, the octagon, the hexagon and the tome — because their arithmetic has always used the row's own
grid line. `gridV` moves the sampler's centre half a pixel toward the nearer edge for those, and below
four pixels it does not move it at all, where a row's edge is a quarter of the node away and stops
meaning anything.

## The named shapes

| Name | |
|---|---|
| `RECT` | The square. |
| `ROUNDED` | A rounded square; the radius is the parameter — `rounded(6)` fixed, `roundedProportional(4)` a quarter of the size. |
| `CIRCLE` | |
| `DIAMOND` | A square turned 45 degrees, fitted: points at the middle of each edge. |
| `HEXAGON` | A true flat-topped hexagon: a horizontal edge top and bottom, straight flanks, points at the sides. Regular, so it keeps a few rows of air above and below. |
| `OCTAGON` | A regular octagon: equal straight chamfers on all four corners. |
| `PENTAGON` | A regular pentagon, point up: five equal sides, a flat base, its widest row above the middle. |
| `GEAR` | A cog with eight wide-rooted trapezoidal teeth (six on the smallest nodes) round a large hub. |
| `HEART` | The classic curve, fitted whole — its own proportions, not stretched to fill the square. |
| `TOME` | A book: a flat spine notched at its head and tail, and a rounded fore-edge. |
| `STAR` | A four-point star: tips at the top, right, bottom and left, and curved sides pinching in between them. |

## Building one

```java
Shape hexagon = Shapes.HEXAGON;
Shape card = Shapes.rounded(6);                 // a fixed radius
Shape proportional = Shapes.roundedProportional(4);
Shape turned = Shapes.rotated(Shapes.TOME, 45); // any shape, any angle
Shape custom = Shapes.unit((u, v, size) -> u * u + v * v <= 0.25);
Shape named = Shapes.byName("gear", Shapes.RECT);
```

- `unit(unit)` is the way to write a shape: one continuous definition, sampleable and turnable exactly.
- `sampled(inside)` is the older factory for a point test in **pixels**; it adapts to `unit` internally,
  and exists so a caller with row-and-pixel arithmetic does not have to learn the unit square.
- `of(startOf, endOf)` and `ofSpans(spansOf)` build a shape from a row's formula. Such a shape has no
  unit definition, so its turn falls back to sampling its own raster — rigid and fitted, but re-stepped.
- `rounded(radius)` and `roundedProportional(divisor)` are the same shape at two ways of saying the
  radius.
- `rotated(base, degrees)` turns the shape's own definition and then fits it to the node with **one**
  uniform scale — measured by casting 512 rays from the centre and bisecting each to the boundary, so a
  circle is left exactly alone and a square at 45 degrees becomes the largest diamond the node holds.
  Nothing is ever stretched, and a turn can only make a node smaller, never larger: a turned node is
  drawn, clicked and fitted in its turned form inside its own square.
- `byName(name, fallback)` is the lookup a file needs: `"shape": "gear"` resolves, and an unknown name
  falls back rather than failing to load.

## The layers, and why they are derived

A node is a stack of outlines, not one: a ring one pixel outside the panel's outline, the panel itself,
a fill one pixel inside it, and a wash and a stand-in block inside that. Every one of those inner and
outer outlines is a **transform of the panel's own table** — never the shape function asked for another
size:

```java
Shape panel = Shapes.GEAR;
Shape fill   = panel.inner();     // the panel's table one pixel in,  for a box two smaller
Shape ring   = panel.outer();     // the panel's table one pixel out, for a box two larger
Shape block  = panel.inner(8);    // the same, eight pixels in, for a stand-in block
```

The transform is the point. A second sampling is a second silhouette, and wherever a feature that
depends on the size steps — the gear's tooth count at 32 pixels, a rounded rectangle's integer radius
every four, a tome's notch every eight — the two disagree: a 31-pixel gear's ring left 76 pixels of its
own panel uncovered and a 33-pixel gear's fill reached outside its outline. Derived layers cannot: a
transform cannot introduce a feature the panel does not have, so the ring closes and the fill stays
inside at every size, turned or not.

The offsets are part of the contract. An inner layer is drawn at `(x + by, y + by)` in a box
`2 * by` smaller and a ring at `(x - by, y - by)` in a box `2 * by` larger; `Outlines.eroded` and
`Outlines.dilated` bake that in, and `ArmatureTheme.shapePanel` uses them for its own fill, so a caller
only has to hand it the panel's spans.

## Fitting an icon

Three methods answer "where does the item go", and they are one decision rather than three:

- `maxInset(size)` is the largest inset whose centred square lies inside the shape — derived from the
  spans, so it cannot disagree with what is drawn.
- `iconBox(nodeX, nodeY, size, scale)` returns `{x, y, box}`: `scale` is a fraction of the **node**,
  capped by what the outline can host at the anchored position, centred, and then moved by the anchor.
- `iconAnchor(size)` is that anchor: a vector in the shape's own frame that moves the item two to three
  percent of the node toward the shape's visual centre of mass, for the shapes whose mass is not their
  middle (the heart, the pentagon and the tome). It turns with the shape, so a heart at 180 degrees
  still carries its item above the outline.
