# The API

Everything a mod's own code calls is under `api/`, reached from a handful of static entry points —
`ArmatureApi` for the platform and the registries, `ArmatureEvents`, `ArmatureNetwork`, and
`Teams.of(server)`. Nothing under `api/` *imports* `impl/`: the one reference is a class name resolved
once, which is what leaves the packages behind the surface free to change.

```java
ArmaturePlatform platform = ArmatureApi.platform();
ArmatureApi.registrar().forMod("mymod").register(BuiltInRegistries.ITEM, id, MyItem::new);
```

## The loader seam

This is the loader [[seam|seam]]: `ArmaturePlatform` is the handful of questions that genuinely
differ between Fabric and NeoForge — small on purpose, because each method here is one with no
vanilla equivalent:

| Method | Answers |
|---|---|
| `kind()`, `name()` | Which loader this is — `PlatformKind.FABRIC` or `NEOFORGE`, and the name a launcher shows. |
| `isModLoaded(modId)` | Whether a mod is loaded. Safe to ask about any id, including ones that do not exist. |
| `modVersion(modId)` | The version a loaded mod reports, as the string it reports. Empty when absent. |
| `isClient()` | Whether this is a client. On a dedicated server, `false`. |
| `isDevelopmentEnvironment()` | Whether this is a development environment rather than a player's install. |
| `gameDir()`, `configDir()`, `configDir(child)` | The instance folder, the config folder, and a folder inside config. |

> [!WARNING]
> Anything that touches `net.minecraft.client.*` must be unreachable behind `isClient()`. On a server
> the class is still loaded, and a server that loads a client class dies with a
> `NoClassDefFoundError` naming a class nobody wrote. This is the mistake the seam exists to make
> impossible, not merely unlikely.

A soft integration asks about another mod and keeps working when the answer is `false`:

```java
if (platform.isModLoaded("some_mod")) {
    // The whole path here must still be absent-safe.
}
```

## Registries

Two jobs, two types — and a registry rather than an enum wherever another mod might add a case.

**Game objects go through `Registrar`.** The loaders differ genuinely: Fabric registers immediately,
NeoForge forbids touching a registry during construction and wants entries queued on a per-mod bus.
Common code cannot tell:

```java
ArmatureApi.registrar()
        .forMod("mymod")
        .register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("mymod", "widget"), MyItem::new);
```

- **Call it while your mod is being constructed.** On NeoForge, registering later throws, because the
  registration window has closed.
- **The supplier, not the instance.** NeoForge may need to build the object later; do nothing inside
  the supplier but construct.
- **`forMod` is necessary, not fussy.** NeoForge keeps one queue per mod, so an entry's namespace and
  the queue it travels on have to agree; a mismatch is refused rather than left to fail at `/give`.
- **Duplicates are refused.** Vanilla *detects* a duplicate and then declines to act on it outside an
  IDE, so the second entry lands silently and surfaces later, usually when a client joins a server.

**Your own Java objects go in `SimpleRegistry<T>`** — the set of task types, of reward types, of
anything another mod can extend:

```java
public static final SimpleRegistry<MyThing> THINGS = SimpleRegistry.create("things");
THINGS.register(id, new MyThing(...));
```

It exists because this is the addon API: a sealed set can never be extended by anyone else, and every
saved file and network message is written in terms of the entry's identity, so the shape of that
identity cannot be changed later. Registering the same id twice is an exception, not a silent
replacement — two mods claiming one id is a bug worth a loud failure.

## Events

`ArmatureEvents` publishes eight server-thread moments. Each listener is a one-method interface, so
registering one is a lambda:

| Event | Listener receives |
|---|---|
| `ServerStarting`, `ServerStarted`, `ServerStopping` | `MinecraftServer` |
| `PlayerTick`, `PlayerJoin`, `PlayerLeave` | `ServerPlayer` |
| `EntityDeath` | `LivingEntity`, `DamageSource` |
| `CommandsRegister` | `CommandDispatcher`, `CommandBuildContext`, `Commands.CommandSelection` |

```java
ArmatureEvents.PLAYER_JOIN.register(player -> LOG.info("{} joined", player.getScoreboardName()));
ArmatureEvents.COMMANDS_REGISTER.register((dispatcher, context, selection) ->
        dispatcher.register(Commands.literal("mycommand").executes(MyCommand::run)));
```

`Event<L>` itself has `register`, `invoker`, `hasListeners` and `listenerCount`; a mod never has to
touch it, but a test can ask it whether anything attached.

## Networking

A payload is declared once, and each loader registers it at the moment it accepts one — Fabric would
be happy either way, NeoForge rejects a registration during construction and wants it inside an event
that fires later. So `ArmatureNetwork.register` **records** and `install` **performs**:

```java
ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
        MY_PAYLOAD_TYPE,
        MY_CODEC,
        ArmatureNetwork.Direction.TO_CLIENT,
        payload -> MyClientState.accept(payload),   // onClient
        null));                                     // onServer
```

`Direction` is `TO_CLIENT`, `TO_SERVER` or `BIDIRECTIONAL`, and the constructor refuses a registration
missing a handler for a direction it travels: a payload that arrives and does nothing is silent at
runtime and reads as a bug in whatever was supposed to fire. Send with
`ArmatureNetwork.sendToPlayer(player, payload)` and `sendToServer(payload)`.

## Client seams

Declaring a key and opening a screen are client work, but a mod's common code still has to be able to
name them. Two small seams keep the client classes out of the common path — the class that calls this
names no client type at all:

```java
ArmatureClient.registerKeyMapping(
        ResourceLocation.fromNamespaceAndPath("mymod", "panel"),
        InputConstants.KEY_P,
        "key.categories.mymod",
        () -> ArmatureClient.openScreen(PANEL_SCREEN_ID));

ArmatureScreens.register(PANEL_SCREEN_ID, MyPanelScreen::new);
```

The loader subprojects supply the backend; common code never mentions one.
