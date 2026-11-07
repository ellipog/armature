package dev.ellipog.armature.client.render;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two signs a cache in this package is invalidated by: an object replaced, told apart by identity, and the
 * pack stack the game reads its resources through, which is the object a reload replaces.
 *
 * <h2>Why these</h2>
 *
 * <p>The texture-size cache used to be stamped with the identity hash of a {@code Resource}, which the game
 * builds afresh on every lookup, so the stamp moved every frame and the cache never hit. What it is stamped
 * with now is the pack stack, read once per ask without opening anything, and the stamp is a serial rather
 * than a hash. The serial's rules are held here, and so is the one fact about the game the stamp rests on:
 * that the stack is the same object until a reload and a different one after it.
 */
@DisplayName("Reload stamps")
class ReloadStampsTest {

    @Test
    @DisplayName("the same object reads the same serial, and a different one a new serial")
    void identityDecides() {
        GuiGraphicsRenderer.IdentityEpoch epoch = new GuiGraphicsRenderer.IdentityEpoch();
        Object first = new Object();
        long a = epoch.of(first);
        assertEquals(a, epoch.of(first), "asking again is not a change");

        // Equal, but not the same: a replaced object with the same contents is still a replacement.
        String one = new String("pack");
        String other = new String("pack");
        long b = epoch.of(one);
        assertNotEquals(a, b);
        long c = epoch.of(other);
        assertNotEquals(b, c, "equal contents are not the same object");
    }

    @Test
    @DisplayName("a serial is never handed out twice, even going back to an earlier object")
    void serialsNeverRepeat() {
        GuiGraphicsRenderer.IdentityEpoch epoch = new GuiGraphicsRenderer.IdentityEpoch();
        Object a = new Object();
        Object b = new Object();
        long first = epoch.of(a);
        long second = epoch.of(b);
        long third = epoch.of(a);
        assertTrue(first < second && second < third,
                "a stamp a cache holds from the first sighting must not match the third: "
                        + first + ", " + second + ", " + third);
    }

    @Test
    @DisplayName("the pack stack is read without a lookup, and is the same object while nothing reloads")
    void thePackStackIsReadInPlace() {
        ReloadableResourceManager manager = new ReloadableResourceManager(PackType.CLIENT_RESOURCES);
        Object stack = GuiGraphicsRenderer.PackStack.of(manager);
        assertNotNull(stack, "the field the stamp reads is where this version keeps it");
        assertSame(stack, GuiGraphicsRenderer.PackStack.of(manager), "nothing replaced it, so nothing moved");

        assertNull(GuiGraphicsRenderer.PackStack.of(null), "no manager, no stack");
        assertNull(GuiGraphicsRenderer.PackStack.of(new Object()), "a manager of another kind is not read");
    }

    @Test
    @DisplayName("a reload replaces the pack stack, so the stamp moves")
    void aReloadReplacesTheStack() {
        // The assumption the stamp rests on, read from ReloadableResourceManager.createReload: it builds a new
        // MultiPackResourceManager into the field before any listener runs. Held here with no listeners and no
        // packs, run on this thread.
        ReloadableResourceManager manager = new ReloadableResourceManager(PackType.CLIENT_RESOURCES);
        GuiGraphicsRenderer.IdentityEpoch stamps = new GuiGraphicsRenderer.IdentityEpoch();
        Object before = GuiGraphicsRenderer.PackStack.of(manager);
        long stamp = stamps.of(before);

        manager.createReload(Runnable::run, Runnable::run, CompletableFuture.completedFuture(Unit.INSTANCE),
                List.of());

        Object after = GuiGraphicsRenderer.PackStack.of(manager);
        assertNotNull(after);
        assertNotSame(before, after, "a reload is a new stack");
        assertNotEquals(stamp, stamps.of(after), "and so a new stamp, which is what re-reads a cached size");
    }
}
