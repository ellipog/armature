package dev.ellipog.armature.api.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fan-out contract: order, the listener queries, and what a registration mid-fire does.
 *
 * <p>Why this exists beside the loader wiring tests: those assert <i>which</i> loader hook reaches
 * <i>which</i> event. This asserts the walk itself — that every listener runs once in order, that
 * a registration during a fire is safe and takes effect next fire, and that the guards the loaders
 * read ({@link #hasListeners}) agree with the list. The allocation half of the change (indexed
 * walks, no iterator per fire) holds by construction and is pinned by review, not by a test:
 * asserting "no allocation" needs a harness this suite does not have.
 */
class EventTest {

    /** A listener shape with no game in it, so this test needs no client and no server. */
    @FunctionalInterface
    private interface Probe {
        void onProbe(String value);
    }

    private static Event<Probe> event(List<String> seen) {
        return new Event<>(listeners -> value -> {
            for (int i = 0, n = listeners.size(); i < n; i++) {
                listeners.get(i).onProbe(value);
            }
        });
    }

    @Test
    @DisplayName("every listener runs once, in registration order")
    void firesInOrder() {
        List<String> seen = new ArrayList<>();
        Event<Probe> event = event(seen);
        event.register(value -> seen.add("first:" + value));
        event.register(value -> seen.add("second:" + value));

        event.invoker().onProbe("go");

        assertEquals(List.of("first:go", "second:go"), seen);
    }

    @Test
    @DisplayName("hasListeners and listenerCount agree with the registrations")
    void listenerQueries() {
        Event<Probe> event = event(new ArrayList<>());
        assertFalse(event.hasListeners());
        assertEquals(0, event.listenerCount());

        event.register(value -> {
        });
        assertTrue(event.hasListeners());
        assertEquals(1, event.listenerCount());
    }

    @Test
    @DisplayName("a null listener is refused, not stored")
    void nullRefused() {
        Event<Probe> event = event(new ArrayList<>());
        assertThrows(NullPointerException.class, () -> event.register(null));
        assertFalse(event.hasListeners());
    }

    @Test
    @DisplayName("registering mid-fire is safe and takes effect next fire, not this one")
    void registerDuringFire() {
        List<String> seen = new ArrayList<>();
        Event<Probe> event = event(seen);
        event.register(value -> {
            seen.add("first:" + value);
            if (event.listenerCount() == 1) {
                event.register(late -> seen.add("late:" + late));
            }
        });

        event.invoker().onProbe("one");
        assertEquals(List.of("first:one"), seen);

        event.invoker().onProbe("two");
        assertEquals(List.of("first:one", "first:two", "late:two"), seen);
    }

    @Test
    @DisplayName("the invoker is one stable object, not rebuilt per fire")
    void invokerStable() {
        Event<Probe> event = event(new ArrayList<>());
        assertSame(event.invoker(), event.invoker());
    }
}
