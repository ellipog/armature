package dev.ellipog.armature.api.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A list of listeners, plus a single object that calls all of them.
 *
 * <p>Each event interface here is a one-method interface, so "call every listener" can be
 * written once as a lambda and wrapped in the interface's own type:
 *
 * <pre>{@code
 * public static final Event<PlayerJoin> PLAYER_JOIN = new Event<>(listeners ->
 *         player -> {
 *             for (PlayerJoin listener : listeners) {
 *                 listener.onPlayerJoin(player);
 *             }
 *         });
 * }</pre>
 *
 * <p>A caller then has no list to walk and no null checks to write:
 * {@code PLAYER_JOIN.invoker().onPlayerJoin(player)}. This is Fabric's arrangement, minus
 * the generated array class, which is a build-time trick we do not need at this scale.
 *
 * <p>Listeners are held in a copy-on-write list: registering during a fire is safe, and
 * firing is a plain array walk with no locking. Events here fire every tick, so that
 * matters.
 *
 * <p>Not thread-safe in the sense that listeners may run on whichever thread fired the
 * event. Server events are on the server thread; a listener that touches game state must
 * assume nothing about which thread reached it.
 */
public final class Event<L> {

    private final List<L> listeners = new CopyOnWriteArrayList<>();
    private final L invoker;

    /**
     * @param invokerFactory builds the "call everyone" object from the live listener list.
     *        It receives the actual list, not a copy, so it sees listeners registered after
     *        this constructor ran.
     */
    public Event(Function<List<L>, L> invokerFactory) {
        this.invoker = invokerFactory.apply(this.listeners);
    }

    /** Add a listener. Called once per mod, during construction — not per event. */
    public void register(L listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * The object that calls every listener. For loader implementations to fire the event.
     * A mod has no reason to call this, and doing so will not end well.
     */
    public L invoker() {
        return invoker;
    }

    /** Whether anything is listening — a cheap way to skip the work leading up to a fire. */
    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    /** How many listeners are attached. Diagnostics. */
    public int listenerCount() {
        return listeners.size();
    }
}
