package dev.ellipog.armature.client.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch's nesting, without a client: who owns the open batch, how deep it is, and what gets flushed
 * when. The regression it pins is a region on another owner inside an open one, which used to leave the
 * depth at -1 and batching off for the rest of the session.
 */
@DisplayName("Batched regions nest, and hand the outer region back")
class BatchNestingTest {

    private final BatchNesting<String> batch = new BatchNesting<>();
    private final List<String> flushed = new ArrayList<>();
    private final Consumer<String> flush = flushed::add;

    @Test
    @DisplayName("regions on one owner share one batch and flush once, at the outermost exit")
    void sameOwnerNests() {
        BatchNesting.Entry<String> outer = batch.enter("a", flush);
        BatchNesting.Entry<String> inner = batch.enter("a", flush);
        assertTrue(outer.fresh());
        assertFalse(inner.fresh());
        assertEquals(2, batch.depth());

        batch.exit(inner, flush);
        assertTrue(flushed.isEmpty());
        assertTrue(batch.batching("a"));

        batch.exit(outer, flush);
        assertEquals(List.of("a"), flushed);
        assertEquals(0, batch.depth());
        assertNull(batch.owner());
    }

    @Test
    @DisplayName("a region on another owner flushes the outer run, and the outer region gets it back")
    void otherOwnerRestoresOuter() {
        BatchNesting.Entry<String> outer = batch.enter("a", flush);
        BatchNesting.Entry<String> inner = batch.enter("b", flush);
        assertEquals(List.of("a"), flushed);
        assertTrue(inner.fresh());
        assertTrue(batch.batching("b"));
        assertFalse(batch.batching("a"));

        batch.exit(inner, flush);
        assertEquals(List.of("a", "b"), flushed);
        assertTrue(batch.batching("a"), "the outer region batches again after the inner one closes");
        assertEquals(1, batch.depth());

        batch.exit(outer, flush);
        assertEquals(List.of("a", "b", "a"), flushed);
        assertEquals(0, batch.depth());
    }

    @Test
    @DisplayName("after a cross-owner nesting, the next region batches as normal")
    void depthNeverGoesNegative() {
        BatchNesting.Entry<String> outer = batch.enter("a", flush);
        batch.exit(batch.enter("b", flush), flush);
        batch.exit(outer, flush);

        BatchNesting.Entry<String> next = batch.enter("a", flush);
        assertTrue(next.fresh());
        assertTrue(batch.batching("a"));
        batch.exit(next, flush);
        assertEquals(0, batch.depth());
    }
}
