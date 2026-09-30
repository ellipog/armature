package dev.ellipog.armature.impl.teams;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamManager;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link TeamManager} that reads and cannot write — which is a legitimate source rather than a
 * broken one.
 *
 * <p>It answers the three questions every source has to answer and leaves the six mutators to their
 * defaults, so it is precisely the shape {@code TeamManager} was split out of the old concrete
 * manager to allow: a mod can expose parties that its own commands create and that nothing else may
 * change, and Armature reads them.
 *
 * <p>It exists here so the refusal can be asserted on. "Throws {@code UnsupportedOperationException},
 * naming the manager" is a promise the interface makes in a javadoc comment, and the point of the
 * default is that a <i>forgetting</i> implementation is impossible — but a caller that has to handle
 * the refusal needs to know what it actually says.
 */
final class ReadOnlyTeamManager implements TeamManager {

    private final String name;

    ReadOnlyTeamManager() {
        this("readonly");
    }

    ReadOnlyTeamManager(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Collection<Team> teams() {
        return List.of();
    }

    @Override
    public Optional<Team> byId(UUID id) {
        return Optional.empty();
    }
}
