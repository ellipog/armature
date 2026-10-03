# The client toolkit

Armature's other half is a UI toolkit, and it is built on one decision: **everything draws through a
game-free interface.** `GuiRenderer` knows about fills, text, item icons, player faces, blur and
scissor rectangles — and nothing about Minecraft. `render/GuiGraphicsRenderer` is its 1.21.1
implementation, and no widget in the toolkit names a game class.

That is what makes the toolkit testable at all: the layout engine, the text models and the shapes are
arithmetic over rectangles and strings, so they are asserted in milliseconds without a client, a font
or a window. It is also the version seam — the 26.x port swaps the implementation and the toolkit
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

`GuiRenderer` has `fill`, `text`, `styledText` (runs of bold/italic/underline at a scale), `textWidth`,
`lineHeight`, `icon(ItemStack, …)`, `face(UUID, …)`, `blur`, and `clip(left, top, right, bottom)` —
which returns a `Scoped` that closes the clip again, so a scissor cannot be left open by a return
taken early.

The other half of the seam is `batched(...)`: a region of drawing that submits once instead of once
per fill, because the unmanaged screen path flushes per fill. It also has `flush()` for the caller
that has to draw something in the middle.

Every colour a widget asks for comes from `ArmatureTheme.current()` — a button does not know what a
theme is, it asks for `body` and gets whatever the current theme says. [[armature:toolkit/themes]] is
the page about that.

## The packages

| Package | What it holds | Page |
|---|---|---|
| `ui.kit` | Layout and scrolling, the text models, and the drawing vocabulary | [[armature:toolkit/layout]], [[armature:toolkit/text]] |
| `ui.shape` | `Shape`: an outline as spans per row, so drawing and hit-testing agree | [[armature:toolkit/shapes]] |
| `ui` (themes) | `Theme`, the theme files, `Look`, and the static palette | [[armature:toolkit/themes]] |
| widgets | `ArmatureButton`, the text-field widgets, `ArmatureScreen`, `ArmatureLive` | [[armature:toolkit/widgets]] |
| `ui.inspect` | The machinery behind a property editor, and `ui.party`'s roster model | [[armature:toolkit/panels]] |

The smaller pieces in `ui.kit`, each written once so a screen does not carry its own copy:

| | |
|---|---|
| `RoundedRect` | A rounded rectangle as a span table — the horizontal extent of each row. |
| `NineSlice` | One source image drawn into a box of any size without its corners stretching. |
| `Outline` | A collapsible tree of keyed nodes — each with a parent and a depth — and which rows that makes visible. |
| `Colour` | ARGB arithmetic: blending, alpha scaling, taking a colour apart. Eight digits, always. |
| `Easing`, `Tween`, `Motion` | The curve of an animation over time; one value moving towards a target; and whether this client animates at all. |
| `Hover` | Which of a set of things the pointer is over, eased — exactly one at a time. |
| `Insets` | Space around something: padding when inside, margin when outside. |
| `Measure` | The only thing a layout needs to know about a font. |
| `TextWrap`, `Codepoints`, `TextHistory` | Wrapping a paragraph to a column; what counts as one character to a caret; and a field's undo history. |
| `DeclaredPaths` | How a folder that declares its own children resolves them — one bare segment, resolved inward. |
