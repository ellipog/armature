package dev.ellipog.armature.api.teams;

import java.util.Optional;
import java.util.UUID;

/**
 * A {@link TeamManager} whose six mutators are real.
 *
 * <p>The whole of this interface is a redeclaration, and that is the point. {@code TeamManager} gives
 * every mutator a default that throws, so that a read-only source only has to answer three questions.
 * The cost of that convenience is that "this manager can write" is otherwise a claim in a comment:
 * a caller holding a {@code TeamManager} cannot tell a real implementation from one that will throw.
 *
 * <p>Redeclaring the six as abstract moves that claim to the compiler. A class that says it implements
 * this and forgets one does not build, and a caller that needs to write can ask for this type — or ask
 * {@link #managesMembership()} when all it has is the interface.
 *
 * <p>{@link #managesMembership()} is overridden here rather than left to each implementation for the
 * same reason: it is true of exactly this interface, so an implementation cannot get it wrong.
 *
 * <p>None of this says the writes will <i>succeed</i>. Every one of these can still legitimately
 * refuse — a kick by somebody without authority returns false, and a source whose model does not line
 * up may throw with its own message — but none of them refuses merely because the source cannot write
 * at all.
 */
public interface MutableTeamManager extends TeamManager {

    @Override
    Team create(String name, UUID owner);

    @Override
    boolean invite(UUID teamId, UUID player);

    @Override
    Optional<Team> acceptInvite(UUID player);

    @Override
    boolean leave(UUID player);

    @Override
    boolean kick(UUID actor, UUID target);

    @Override
    boolean disband(UUID actor, UUID teamId);

    /** True, by definition. See the interface comment for why this is not left to implementations. */
    @Override
    default boolean managesMembership() {
        return true;
    }
}
