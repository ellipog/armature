package dev.ellipog.armature.api.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Late declarations forward: entrypoint order between mods is the loader's
 * business, so a declare that lands after another mod's install must still
 * register.
 *
 * <p>Installs a recording backend, permanently for this JVM: no other test
 * reads the network seam, and statics do not reset, so this test owns that
 * fact openly rather than pretending isolation it cannot have.
 */
class ArmatureNetworkTest {

    record ProbePayload() implements CustomPacketPayload {
        static final CustomPacketPayload.Type<ProbePayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                        "armature-test", "probe"));

        @Override
        public CustomPacketPayload.Type<ProbePayload> type() {
            return TYPE;
        }
    }

    static final class RecordingBackend implements NetworkBackend {
        final List<Identifier> registered = new ArrayList<>();
        int trackingSends;
        int nearSends;

        @Override
        public <T extends CustomPacketPayload> void register(
                ArmatureNetwork.Registration<T> registration) {
            registered.add(registration.type().id());
        }

        @Override
        public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        }

        @Override
        public void sendToTracking(Entity entity, CustomPacketPayload payload) {
            trackingSends++;
        }

        @Override
        public void sendNear(ServerLevel level, Vec3 at, double radius,
                             CustomPacketPayload payload) {
            nearSends++;
        }

        @Override
        public void sendToServer(CustomPacketPayload payload) {
        }
    }

    /**
     * Installed once for the class, because {@link ArmatureNetwork#install} keeps the first
     * backend: a second install is a no-op, so a per-test backend would leave the routing tests
     * recording into an object nobody reads.
     */
    private static final RecordingBackend BACKEND = new RecordingBackend();

    @BeforeAll
    static void installBackend() {
        ArmatureNetwork.install(BACKEND);
    }

    private static ArmatureNetwork.Registration<ProbePayload> registration(Identifier id) {
        StreamCodec<RegistryFriendlyByteBuf, ProbePayload> codec =
                new StreamCodec<>() {
                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, ProbePayload value) {
                    }

                    @Override
                    public ProbePayload decode(RegistryFriendlyByteBuf buf) {
                        return new ProbePayload();
                    }
                };
        return new ArmatureNetwork.Registration<>(
                new CustomPacketPayload.Type<>(id), codec,
                ArmatureNetwork.Direction.BIDIRECTIONAL, probe -> {
                }, (probe, player) -> {
                });
    }

    @Test
    void lateDeclarationStillRegisters() {
        int before = BACKEND.registered.size();

        ArmatureNetwork.register(registration(Identifier.fromNamespaceAndPath(
                "armature-test", "late-" + System.nanoTime())));

        assertTrue(BACKEND.registered.size() > before,
                "a declare after install must reach the installed backend");
    }

    @Test
    void scopedSendsReachTheBackend() {
        int trackingBefore = BACKEND.trackingSends;
        int nearBefore = BACKEND.nearSends;

        ArmatureNetwork.sendToTracking(null, new ProbePayload());
        ArmatureNetwork.sendNear(null, Vec3.ZERO, 16.0, new ProbePayload());

        assertEquals(trackingBefore + 1, BACKEND.trackingSends,
                "a tracking send must reach the loader backend");
        assertEquals(nearBefore + 1, BACKEND.nearSends,
                "a nearby send must reach the loader backend");
    }
}
