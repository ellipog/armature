# Themes

Every colour the toolkit draws with comes from one place. A button does not know what a theme is — it
asks for the body ink and gets whatever the current theme says, which is why `ArmatureTheme` is a
static class rather than something passed through every call.

A `Theme` is a record of colour tokens, plus the corner radius, the motion duration and the easing
curve. Tokens are read as **fields** by code — a record has fields the compiler knows, so a theme is
checked — and addressed **by name** by anything outside code: a theme file, a chapter naming a palette.
`ThemeToken` groups the names: surfaces, text, progression, graph, rows and labels, scrollbar, tooltips
and controls.

## Sixteen built-ins

`default`, `modern`, `tome`, `vanilla_plus`, `high_contrast`, `monochrome`, `paper`, `obsidian`,
`amethyst`, `copper`, `redstone`, `nether`, `end`, `deep_dark`, `terminal` and `neon` — `Themes.ALL`
for the list, `Themes.byName(name)` for one. `default` is `modern` with a square corner radius, which
is a theme defined by what it changes rather than a second palette to keep in step.

## Theme files

A theme can be written by hand, one file per theme, in `config/armature/themes/`. A file is a **diff**:
it says what it changes, and the rest comes from the theme it names as its base.

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

> [!NOTE]
> Nothing in a theme file is fatal, and nothing is silent: an unreadable file, malformed JSON, an
> unknown `basedOn` or a misspelled colour is a warning naming the file and the field, and the client
> carries on with what it could read. `ThemeFiles.problems()` is where a mod can read them back.

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
`clearRadius()`. The `Settings` record it exposes is what a consumer stores — on the server for a
default, or wherever its own config lives. `Settings.patch()` is the same settings as a `ThemePatch`,
which is what makes "a chapter asks for a palette" and "a player picked one" the same merge.

## `ThemePatch`, and the boundary it keeps

`applyTo(base)` makes a whole theme — a file, a derived built-in — and takes the name, radius, motion
and easing as well as the colours, because a theme is those things. `tint(base)` reskins a *region*
and takes colours only.

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
