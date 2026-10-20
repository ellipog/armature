package dev.ellipog.armature.api.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sending things between a client and a server, without either side naming a loader.
 *
 * <h2>Why the seam is small</h2>
 *
 * <p>Smaller than expected, and worth stating because it is the reason this is four methods rather
 * than forty. Both loaders take the <b>same</b> codec type for a play payload —
 * {@code StreamCodec<? super RegistryFriendlyByteBuf, T>} — because both ultimately hand it to the
 * same vanilla packet machinery. So a payload class is written once and shared; only registration and
 * sending differ.
 *
 * <h2>Direction, and why a registration declares it</h2>
 *
 * <p>Fabric registers server and client receivers through different classes on different sides;
 * NeoForge registers both through one call and needs to know which is which. Declaring the direction
 * lets each loader do the right thing without Armature guessing, and it means a payload that travels
 * one way cannot be registered as though it went both.
 *
 * <h2>Registration is a declaration, not a call</h2>
 *
 * <p>{@link #register} records; {@link #install} performs. That split is not tidiness — it is forced by
 * NeoForge, which rejects a payload registration during mod construction and wants them inside an
 * event that fires later. Fabric would be happy either way. A mod declares once, and each loader takes
 * the declaration at the moment it accepts one.
 */
public final class ArmatureNetwork {

    /** Which way a payload travels. */
    public enum Direction {
        /** Server to client only. */
        TO_CLIENT,
        /** Client to server only. */
        TO_SERVER,
        /** Both, for a payload used in both directions. */
        BIDIRECTIONAL;

        public boolean reachesClient() {
            return this == TO_CLIENT || this == BIDIRECTIONAL;
        }

        public boolean reachesServer() {
            return this == TO_SERVER || this == BIDIRECTIONAL;
        }
    }

    /**
     * A payload, its codec, where it goes, and what to do on arrival.
     *
     * <p>The two handlers are nullable, and which may be null follows from the direction. The
     * constructor enforces that, because a registration with no handler for a direction it travels is
     * a payload that arrives and does nothing — which is silent at runtime and reads as a bug in
     * whatever was supposed to have been triggered.
     *
     * @param <T> the payload type
     */
    public record Registration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Direction direction,
            Consumer<T> onClient,
            BiConsumer<T, ServerPlayer> onServer
    ) {

        public Registration {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(direction, "direction");

            if (onClient == null && onServer == null) {
                throw new IllegalArgumentException("the payload " + type.id()
                        + " has no handler on either side, so nothing would ever happen to it");
            }
            if (direction.reachesClient() && onClient == null) {
                throw new IllegalArgumentException("the payload " + type.id()
                        + " travels to the client but has no client handler");
            }
            if (direction.reachesServer() && onServer == null) {
                throw new IllegalArgumentException("the payload " + type.id()
                        + " travels to the server but has no server handler");
            }
        }
    }

    /**
     * Everything declared, in declaration order.
     *
     * <p>Declaration order matters for nothing functionally and everything for a log a human reads, so
     * it is preserved rather than sorted.
     */
    private static final List<Registration<?>> REGISTRATIONS = new ArrayList<>();

    /**
     * The same registrations by payload id, for {@link #byId}.
     *
     * <p>Kept alongside the list rather than scanned from it: the list is declaration order for the
     * log and the install pass, and the map is the lookup. First registration wins on a duplicate
     * id, which is what the linear scan answered before — duplicates are still accepted here
     * rather than refused, because refusing them would be a new behavior.
     */
    private static final Map<Identifier, Registration<?>> BY_ID = new LinkedHashMap<>();

    private static NetworkBackend backend;

    private ArmatureNetwork() {
    }

    /**
     * Declares a payload.
     *
     * <p>Call during mod construction on both sides, <b>before</b> {@link #install} — install
     * registers everything declared so far, and declaring first keeps the wiring legible. A
     * declare that lands after another mod's install is still honoured: entrypoint order
     * between mods is the loader's business (Fabric runs them unordered), so it forwards
     * straight to the installed backend instead of dying. The loader accepts payload types
     * throughout init; a declare after play starts is the backend's to refuse, not this
     * list's.
     */
    public static <T extends CustomPacketPayload> void register(Registration<T> registration) {
        Objects.requireNonNull(registration, "registration");
        REGISTRATIONS.add(registration);
        BY_ID.putIfAbsent(registration.type().id(), registration);
        if (backend != null) {
            installOne(backend, registration);
        }
    }

    /**
     * Hands over the loader's networking and registers everything declared so far.
     *
     * <p>Called once per side, by that loader's entry point. <b>Internal.</b>
     *
     * <h2>Idempotent, and that matters on a client</h2>
     *
     * <p>A client with an integrated server has two initialisers that could reasonably install — the
     * common one and the client one — and which runs first is the loader's business, not something to
     * depend on. Guarding here means either order works, and neither can cause a payload to be
     * registered twice. A double registration is not harmless: Fabric and NeoForge both refuse it, and
     * the message ("payload already registered") reads as a mod conflict rather than as a mod that
     * initialised twice.
     *
     * <p>The first backend wins. On any given JVM there is only one correct answer — a Fabric client
     * is a Fabric client whether the call came from the common or the client initialiser — so there is
     * nothing to arbitrate, only a duplicate to ignore.
     */
    public static void install(NetworkBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (ArmatureNetwork.backend != null) {
            return;
        }
        ArmatureNetwork.backend = backend;
        for (Registration<?> registration : REGISTRATIONS) {
            installOne(backend, registration);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void installOne(NetworkBackend backend, Registration<?> raw) {
        backend.register((Registration<T>) raw);
    }

    /** Whether a loader has installed its networking yet. */
    public static boolean isInstalled() {
        return backend != null;
    }

    /** How many payloads have been declared. For a log line that says the wiring happened. */
    public static int registeredCount() {
        return REGISTRATIONS.size();
    }

    /** Everything declared, for a loader to read. <b>Internal.</b> */
    public static List<Registration<?>> registrations() {
        return List.copyOf(REGISTRATIONS);
    }

    /**
     * Sends a payload to one player.
     *
     * <p>Server side. Silently does nothing if the loader has not installed its networking — which
     * happens on a client that never opened a world, and is not worth a crash over.
     *
     * <p>No rate limit, no dedup: the caller throttles. A send per tick per player will get that
     * player kicked for packet spam, so anything recurrent coalesces before it calls here — see
     * how a consumer's tree broadcast collapses to one per tick rather than one per edit.
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        NetworkBackend installed = backend;
        if (installed == null) {
            return;
        }
        installed.sendToPlayer(player, payload);
    }

    /**
     * Sends a payload to the server.
     *
     * <p>Client side. Does nothing where there is nothing to send to, so a common code path can call
     * this after its own side check without a second one.
     *
     * <p>Same throttling rule as {@link #sendToPlayer}: no rate limit here, so the caller coalesces.
     */
    public static void sendToServer(CustomPacketPayload payload) {
        NetworkBackend installed = backend;
        if (installed == null) {
            return;
        }
        installed.sendToServer(payload);
    }

    /**
     * Sends a payload to the players tracking an entity, and to the entity itself when it is a
     * player.
     *
     * <p>Server side. This is the "everyone who can see this" send, which is the right scope for
     * per-entity state: a dimension-wide broadcast sends every shape to everyone and is the fault
     * this method exists to avoid. {@code PlayerLookup.tracking} on Fabric excludes the player
     * themselves, so the self leg is added by the backend; NeoForge's one call includes it.
     *
     * <p>Silently does nothing when the loader has not installed its networking, as
     * {@link #sendToPlayer} does.
     */
    public static void sendToTracking(Entity entity, CustomPacketPayload payload) {
        NetworkBackend installed = backend;
        if (installed == null) {
            return;
        }
        installed.sendToTracking(entity, payload);
    }

    /**
     * Sends a payload to every player within {@code radius} of one point.
     *
     * <p>Server side. For a sound or a shake whose audible range is smaller than the dimension,
     * which is why the radius is the caller's: vanilla's own sound range rule is what decides it.
     */
    public static void sendNear(ServerLevel level, Vec3 at, double radius,
                                CustomPacketPayload payload) {
        NetworkBackend installed = backend;
        if (installed == null) {
            return;
        }
        installed.sendNear(level, at, radius, payload);
    }

    /** The payload declared under an id, if any. Diagnostics. */
    public static Optional<Registration<?>> byId(Identifier id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
