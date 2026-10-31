# The client toolkit

Armature's other half is a UI toolkit, and it is built on one decision: **every draw call goes through
one interface.** `GuiRenderer` knows about fills, text, item icons, player faces, blur and scissor
rectangles. The only game types it names are the item stack and the texture id a caller already
holds. `render/GuiGraphicsRenderer` is its 26.3 implementation, and no drawing call in the toolkit
names the graphics context: the controls are ordinary `AbstractWidget`s, for the input and narration
the game already gives them, and what they draw they draw through the seam.

That is what makes the toolkit testable at all: the layout engine, the text models and the shapes are
arithmetic over rectangles and strings, so they are asserted in milliseconds without a client, a font
or a window. It is also the version [[seam|seam]]: the 26.x port swaps the implementation and the toolkit
above it does not move.

```java
protected void renderContent(GuiRenderer r, int mouseX, int mouseY, float partialTick) {
    r.fill(x, y, right, bottom, ArmatureTheme.panel());
    r.text("Hello", x + 8, y + 6, ArmatureTheme.body());
    r.styledText(List.of(new GuiRenderer.StyledRun("bold", true, false, false)), x + 8, y + 22, ink);
    r.icon(stack, iconX, iconY, 16);
}
```

## Drawing, and the one thing to know about it

`GuiRenderer` has `fill`, `text`, `shadowedText` (at a scale), `styledText` (runs of
bold/italic/underline, each at a scale) with `styledWidth`, `centredText`, `textWidth`,
`lineHeight`, `icon(ItemStack, …)` and `face(UUID, …)` (each answering whether it drew),
`texture`/`scaled`/`sprite` stamps with `textureSize` for a file's own dimensions (`textureStamp`
when only the handle is wanted), `turned` (a
rotated region, closed like a clip), `blur`, and `clip(left, top, right, bottom)`,
which returns a `Scoped` that closes the clip again, so a scissor cannot be left open by a return
taken early. `clip` also takes a layout `Slot` or a `Viewport`, for the region a caller already holds.

The other half of the seam is `batched(...)`: a region of drawing that submits once instead of once
per fill, because the unmanaged screen path flushes per fill. It also has `flush()` for the caller
that has to draw something in the middle.

Two calls hand over many rectangles at once. `fillPlan(plan, x, y, argb)` takes a shape's whole
`Plans.Plan`, and `fillLattice(xs, columns, width, ys, rows, height, argb)` takes a repeating mark as its
columns against its rows. `ArmatureTheme.fillShape` and the canvas background patterns draw through them.
Their defaults are the fill loops they replace, so a test recorder still sees every rectangle.

On 26.3 the game renderer draws a plan as one quad over a mask kept on the GPU, and a lattice as one quad
per row over a one-row mask, in the same batch and order as the fills around them. The pixels are the same:
a mask is made from the plan's own rectangles, one texel per pixel, and sampled without filtering. A plan
whose rectangles overlap, a mask bigger than 128 pixels, or a full mask texture falls back to fills. Start
the game with `-Darmature.gpuMasks=false` to turn the GPU path off.

`hairline(x, y, route, argb)` draws a one-pixel line along a route, given flat as `x0, y0, x1, y1, ...`
from `(x, y)`. A segment takes one pixel per column or row along its longer axis, with the other axis
rounded half up, and each joint is drawn once (`Hairlines` holds the rule). The default draws those pixels
as fills. The caller promises the route never comes back over a pixel it has already drawn, apart from the
joints. If it does, those pixels are drawn twice, and that shows on a translucent colour. On 26.3 the game
renderer draws a diagonal segment as one quad through its own pipeline, and the fragment shader keeps the
same pixels with integer maths. A segment along an axis is one fill, and a segment of 2896 steps or more
stays on fills, because past that the float and integer rounding can disagree. Fills in the same run share
the line pipeline, so their order holds. Start the game with `-Darmature.gpuLines=false` to turn it off. A
pipeline that will not compile turns it off for the session with one warning in the log.

`iconsLast(draw)` runs `draw` and holds back every `icon`, `texture` and `sprite` it asks for until the end,
so the rectangles inside are one batch and the pictures come after them, in the order they were asked for.
Only use it when no rectangle drawn after a picture inside the region overlaps that picture, since the
rectangle would end up under it. Text, a face, a blur, a clip or a
`flush()` inside the region draws the held pictures first, so those keep their order. The default runs
`draw` as it is.

Every colour a widget asks for comes from `ArmatureTheme.current()`. A button does not know what a
theme is, it asks for `body` and gets whatever the current theme says. [[armature:toolkit/themes]] is
the page about that.

## The packages

| Package | What it holds | Page |
|---|---|---|
| `ui.kit` | Layout and scrolling, the text models, and the drawing vocabulary | [[armature:toolkit/layout]], [[armature:toolkit/text]] |
| `ui.shape` | `Shape`: an outline as spans per row, so drawing and hit-testing agree | [[armature:toolkit/shapes]] |
| `ui` (themes) | `Theme`, the theme files, `Look`, and the static palette | [[armature:toolkit/themes]] |
| widgets | `ArmatureButton`, the text-field [[widget|widgets]], `ArmatureScreen`, `ArmatureLive` | [[armature:toolkit/widgets]] |
| `ui.inspect` | The machinery behind a property editor, and `ui.party`'s roster model | [[armature:toolkit/panels]] |

The smaller pieces in `ui.kit`, each written once so a screen does not carry its own copy:

| | |
|---|---|
| `RoundedRect` | A rounded rectangle as a span table: the horizontal extent of each row. |
| `NineSlice` | One source image drawn into a box of any size without its corners stretching. |
| `Outline` | A collapsible tree of keyed nodes (each with a parent and a depth) and which rows that makes visible. |
| `Colour` | ARGB arithmetic: blending, alpha scaling, taking a colour apart. Eight digits, always. |
| `Easing`, `Tween`, `Motion` | The curve of an animation over time; one value moving towards a target; and whether this client animates at all. |
| `Hover` | Which of a set of things the pointer is over, eased, exactly one at a time. |
| `Insets` | Space around something: padding when inside, margin when outside. |
| `Measure` | The only thing a layout needs to know about a font. |
| `TextWrap`, `Codepoints`, `TextHistory` | Wrapping a paragraph to a column; what counts as one character to a caret; and a field's undo history. |
| `DeclaredPaths` | How a folder that declares its own children resolves them: one bare segment, resolved inward. |
