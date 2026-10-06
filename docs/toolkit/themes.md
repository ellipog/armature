# Themes

Every colour the toolkit draws with comes from one place. A button does not know what a [[theme|theme]]
is — it asks for the body ink and gets whatever the current theme says, which is why `ArmatureTheme` is
a static class rather than something passed through every call.

A `Theme` is a record of colour tokens, plus the corner radius, the motion duration and the easing
curve. Tokens are read as **fields** by code — a record has fields the compiler knows, so a theme is
checked — and addressed **by name** by anything outside code: a theme file, a chapter naming a palette.
`ThemeToken` groups the names: surfaces, text, progression, graph, rows and labels, scrollbar, tooltips
and controls. A token's name is what the code reads it for: a node's border is one of the four
`nodeEdge*` tokens, so a theme can give its borders a colour apart from its progress inks.

## Sixteen built-ins

`default`, `modern`, `tome`, `vanilla_plus`, `high_contrast`, `monochrome`, `paper`, `obsidian`,
`amethyst`, `copper`, `redstone`, `nether`, `end`, `deep_dark`, `terminal` and `neon` — `Themes.ALL`
for the list, `Themes.byName(name)` for one. `default` is `modern` with a square corner radius, which
is a theme defined by what it changes rather than a second palette to keep in step.

**Every built-in states every token**, and the guard in `Themes.set` enforces it at class
initialisation: an omitted token silently becomes `modern`'s value, and a token added to the registry
later would be inherited by all sixteen themes with nobody deciding it. A **file** theme is the
opposite by design — it is a diff, and everything it leaves out comes from the theme it names as its
`basedOn` (which defaults to `default`).

## Theme files

A theme can be written by hand, one file per theme, in the themes directory the mod hands `Look` —
the library never chooses one. A file is a **diff**: it says what it changes, and the rest comes
from the theme it names as its base.

```json
{
  "name": "my_pack",
  "basedOn": "modern",
  "colours": { "panel": "#2A2233", "canvas": "#1A1622", "available": "#C79BF0" },
  "cornerRadius": 6,
  "motion": 120,
  "easing": "QUAD_OUT"
}
```

`basedOn` may name a built-in or another file-loaded theme, so a pack can have a variant of its own.

The directory is read by `Look.load(path, themesDirectory)` — the call a mod makes once at startup — and
again after `Look.saveAsTheme` writes a file, so a theme shipped as a file is selectable from the first
frame and a theme saved in game is selectable without a restart. `ThemeFiles.reload(directory)` is the
explicit re-read, and `ThemeFiles.all()` is everything loaded.

> [!NOTE]
> Nothing in a theme file is fatal, and nothing is silent: an unreadable file, malformed JSON, an
> unknown `basedOn` or a misspelled colour is a warning naming the file and the field, and the client
> carries on with what it could read. `ThemeFiles.problems()` is where a mod can read them back.

## Canvas backgrounds

A theme's canvas is a colour (`canvas`) and, optionally, a pattern drawn over it. The pattern is
`canvasBackground` — a top-level object beside `colours`, in the same file and read with the same
tolerance:

```json
{
  "name": "blueprint",
  "basedOn": "modern",
  "canvasBackground": { "pattern": "grid_lines", "space": "graph", "spacing": 24 },
  "colours": { "canvasPattern": "#336FB4E8" }
}
```

- `pattern` — `none` (the default), `dot_grid`, `grid_lines`, `speckle`, `hatch` or `image`. The five
  flat kinds are drawn from arithmetic: no texture ships with them and none has to resolve.
- `space` — `graph` anchors the pattern to the content, so it pans and zooms with the graph;
  `screen` fixes it to the surface. `spacing` is content units in the first case and screen pixels in
  the second. Flat kinds only: an image is always screen-fixed and ignores both.
- `size` — the edge of a dot or speck and the thickness of a line or hatch step, 1..4 pixels.
- `density` — speckle only: one cell in this many carries a mark, 2..16.
- `direction` — hatch only: `slash` or `backslash`.
- `canvasPattern` — the ink, a colour token like any other, so it can be set under `colours` and is
  listed in the in-game colour panel. **Its alpha is the pattern's strength**: `#33` is a hint, `#80`
  is a blueprint. For an image the ink's RGB is ignored — the texture draws in its own colours and
  only the alpha comes through, so a dark `canvasPattern` cannot crush a coloured texture.

`spacing` is clamped to 6..256, and a pattern fades out rather than shimmering when its repeats fall
closer than a few screen pixels, or when drawing it would overrun the frame's fill budget. A pattern
is decoration; it never costs the frame.

An `image` is a texture the pack ships:

```json
"canvasBackground": { "pattern": "image", "texture": "mymod:textures/gui/parchment.png",
                      "fit": "tile", "tile": 32 }
```

- `texture` — canonicalised to `ns:textures/path.png`: lowercased, the namespace defaulted to
  `minecraft`, and a leading `textures/` and trailing `.png` accepted and folded away. The real pixel
  size is read from the file's own PNG header — 24 bytes, no decode, nothing to leak — so a 32x16
  texture keeps its 2:1 aspect when drawn.
- `fit` — `tile` repeats the texture at `tile` pixels wide (4..256, aspect-preserving), and the
  lattice steps by the drawn size, so rows and columns abut with no spacing knob and no seams.
  `cover` draws it once, scaled to fill the rectangle and centre-cropped to the rectangle's aspect.
- A texture that cannot be resolved draws nothing — the painter will not guess — and the in-game
  editor refuses an id that names no file, with the resolved path in the message.

A chapter can resurface its own canvas through its `themePatch`, exactly as it can override colours:

```json
{ "themePatch": { "canvasBackground": { "pattern": "speckle", "spacing": 18 } } }
```

Three built-ins carry a surface as part of what they are — `tome` a parchment speckle, `paper` a
one-pixel rule grid, `terminal` two-pixel phosphor dots — and a theme based on one of them inherits
it unless it names its own.

## `Look`: the instance a mod owns

The global palette is what is *drawn*; a `Look` is what a mod's player has *chosen*. It holds a theme
name, the motion toggle, a radius, and any per-token overrides, and it is what a settings screen
edits:

```java
Look look = new Look();              // this mod's instance
look.setRadius(8);
look.apply();                        // push the settings into the palette everything draws from
```

`settings()`, `main()`, `radius()` (0–12), `motion()`, `chosen()`, `serverDefault()`, `custom()`,
`clearRadius()`, `background()` and `setBackground(...)`. The `Settings` record it exposes is what a
consumer stores — on the server for a default, or wherever its own config lives. `Settings.patch()` is
the same settings as a `ThemePatch`, which is what makes "a chapter asks for a palette" and "a player
picked one" the same merge.

**Choosing a theme clears the per-token overrides, the radius and the canvas background with it** — a
theme choice means "use this look", so a picked palette is not tinted by edits made under the previous
one. The motion toggle and the text scale are untouched, because they are accessibility settings rather
than parts of a look. Edits worth keeping across a choice are saved first: `saveAsTheme` writes them
into a theme file, and the file is a theme like any other.

## `ThemePatch`, and the boundary it keeps

`applyTo(base)` makes a whole theme — a file, a derived built-in — and takes the name, radius, motion,
easing and canvas background as well as the colours, because a theme is those things. `tint(base)`
reskins a *region* and takes colours only.

That split is a decision rather than an oversight. A region that could change the corner radius of
every panel in a screen, or the duration of every transition, would be a data file reaching into the
screen's construction — and motion would silently do nothing anyway, because animation timing is
pushed to `Motion` once per client rather than per region. A value that cannot take effect is worse
than one that is refused.

## Scoping a palette to a region

`ArmatureTheme.scope(theme)` and `scopeOf(name)` return a `Scope` that is `AutoCloseable`, so a region
can be drawn in another palette and the old one restored on the way out — including on an early
return:

```java
try (var ignored = ArmatureTheme.scopeOf("nether")) {
    drawChapterHeader(r);
}
```

`current()`, `chrome()`, `themeName()`, `setCurrent(...)`, `selectByName(name)` and `resetCurrent()`
are the rest of the palette's surface, with `dim()`, `canvas()`, `recessed()` and `panel()` as the
shorthands a drawing call actually wants.
