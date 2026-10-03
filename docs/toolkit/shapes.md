# Shapes

A shape is **a list of spans per row** — for each row of a square, where it starts and where it ends:

```java
public interface Shape {
    int[] spansOf(int row, int size);       // pairs: start, end
}
```

That representation is the whole design, because drawing and hit-testing read the same spans: the fill
walks them, and `containsLocal(x, y, size)` asks whether a point is inside them. A click therefore
lands on exactly what was drawn, for every shape, without a second implementation that can disagree —
which is the bug the alternatives pay for, since FTB Quests' pixel-mask buffers hard-code their
shapes to a square grid.

The default `spans(row, size)` sanitises what `spansOf` returns — sorted, merged, clamped to the row —
so a shape cannot express a negative span or an unsorted pair even by accident. `containsLocal` is a
default method built on the same spans, and a shape that has a cheaper answer (a circle's squared
distance, say) may override it.

## The named shapes

Ten, each a single span formula or a sampled inside-test:

| Name | |
|---|---|
| `RECT` | The square. |
| `ROUNDED` | A rounded square; the radius is the parameter. |
| `CIRCLE` | |
| `HEXAGON`, `OCTAGON`, `PENTAGON` | Regular polygons, flat-top. |
| `DIAMOND` | |
| `TOME` | A book's cover: square with one corner cut back. |
| `GEAR` | A cog with teeth, point-sampled. |
| `HEART` | Point-sampled. |

## Building one

```java
Shape hexagon = Shapes.HEXAGON;
Shape card = Shapes.rounded(6);                 // a fixed radius
Shape proportional = Shapes.roundedProportional(4);
Shape turned = Shapes.rotated(Shapes.TOME, 45); // any shape, any angle
Shape custom = Shapes.sampled((x, y, size) -> x * x + y * y < r * r);
Shape named = Shapes.byName("gear", Shapes.RECT);
```

- `of(startOf, endOf)` and `ofSpans(spansOf)` build from a formula — the cheap path.
- `sampled(inside)` point-samples an inside-test, for a shape no one-line formula says (the gear and
  the heart). It is slower, which is exactly why it is a factory rather than the only path.
- `rounded(radius)` and `roundedProportional(divisor)` are the same shape at two ways of saying the
  radius.
- `rotated(base, degrees)` re-samples the outline rather than naming a second shape, so a turned node
  is drawn, clicked and fitted in its turned form.
- `byName(name, fallback)` is the lookup a file needs: `"shape": "gear"` resolves, and an unknown name
  falls back rather than failing to load.
