package dev.ellipog.armature.api.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The loader's networking, reduced to the three things Armature needs.
 *
 * <p>Deliberately tiny, because there is barely a difference to abstract: both loaders take the same
 * codec type and both deliver payloads on the game thread. What differs is <b>how a payload is
 * registered</b> — Fabric needs a format registration plus a per-side receiver registration, and
 * refuses none of it; NeoForge wants one call inside an event that fires after construction. That,
 * and only that, is what this bridges.
 *
 * <p>Implemented once per loader, in that loader's subproject. Nothing in {@code common/} may
 * reference an implementation; reach it through {@link ArmatureNetwork}.
 */
public interface NetworkBackend {

    /**
     * Registers one payload with the loader.
     *
     * <p>An implementation may do the work immediately (Fabric's format half) or record it for later
     * (NeoForge, whose registrar does not exist yet). Both are correct; which one happens is exactly
     * the difference the caller must not have to know.
     */
    <T extends CustomPacketPayload> void register(ArmatureNetwork.Registration<T> registration);

    /** Sends a payload to one player. Only ever called on the server. */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Sends a payload to the server. Only ever called on a client. */
    void sendToServer(CustomPacketPayload payload);
}
