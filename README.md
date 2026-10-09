# Armature

The shared library and UI toolkit for Fabric and NeoForge — loader seam, strict JSON
reader, event, network, and team APIs, plus a game-free client toolkit.

Standalone: nothing in it is specific to any one consumer. Install it on its own or
beside any mod that needs it.

> **Before its first stable release.** The API may still break, so read the release
> notes before upgrading.

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
