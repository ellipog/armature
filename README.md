# Armature

The shared library and UI toolkit for Fabric and NeoForge — loader seam, strict JSON
reader, event, network, and team APIs, plus a game-free client toolkit.

Standalone: nothing in it is specific to any one consumer. Install it on its own or
beside any mod that needs it.

> **Before its first stable release.** The API may still break, so read the release
> notes before upgrading.

## Why Armature

- **[One game-free renderer seam](docs/toolkit/index.md).** Every draw call goes through `GuiRenderer`, so UI is unit-tested in milliseconds instead of eyeballed in screenshots.
- **[Layout, text, and shapes without a client](docs/toolkit/index.md).** Headless-tested arithmetic over rectangles and strings — pan, zoom, wrapping, hit-testing that agrees with drawing by construction.
- **[Strict JSON with did-you-mean](docs/api/data.md).** Line and column per path, presence and type checks with suggestions, and problem reports a mod's own format can reuse.
- **[Teams resolve in order](docs/api/teams.md).** `Teams.of(server)` reads an explicit provider, then FTB Teams, then Open Parties and Claims, then the built-in stored fallback.
- **[Per-mod appearance](docs/toolkit/themes.md).** Each mod owns its `Look`, so two mods never share one player's theme file.

## Using it

Drop the jar in `mods/`, on either loader. Fabric also needs Fabric API. See the
Modrinth page for the supported Minecraft version.

For mod developers: compile against the published artifact (see the releases page —
it follows the shape `dev.ellipog:armature-common-<minecraft-line>:<release>` on
`https://maven.ellipog.dev`), declared `compileOnly` since Armature is a separate jar
at runtime.

Details live in the manual:

- [Manual front door](docs/index.md)
- [Mod-facing API](docs/api/index.md) — platform, registries, events, networking
- [Strict JSON reader](docs/api/data.md)
- [Teams](docs/api/teams.md) — where a server's parties are read from
- [Client toolkit](docs/toolkit/index.md) — renderer seam, layout, text, shapes, themes, widgets

## Building

```cmd
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs`. Tests run headless as
part of the build.

## Licence

MIT — see [LICENSE](LICENSE).
