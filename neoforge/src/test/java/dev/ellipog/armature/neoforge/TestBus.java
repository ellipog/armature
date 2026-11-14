package dev.ellipog.armature.neoforge;

import net.neoforged.neoforge.common.NeoForge;

/**
 * The game bus, started, with Armature's forwarders on it once per test JVM.
 *
 * <p>The bus is built shut down, and a post to it calls nobody until the loader starts it, so a test
 * that forgets to start it passes without any listener running. And {@link NeoForgeEvents#attach} must
 * run once: two test classes each attaching would forward every event twice.
 */
final class TestBus {

    private static boolean attached;

    private TestBus() {
    }

    static synchronized void attachOnce() {
        NeoForge.EVENT_BUS.start();
        if (!attached) {
            NeoForgeEvents.attach();
            attached = true;
        }
    }
}
