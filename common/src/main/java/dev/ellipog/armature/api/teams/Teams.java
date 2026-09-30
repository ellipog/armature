package dev.ellipog.armature.api.teams;

import net.minecraft.server.MinecraftServer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Teams, from the outside.
 *
 * <p>The whole surface is one call: {@link #of} hands back the {@link TeamManager} for a server,
 * and everything else hangs off that.
 *
 * <p>Reached through a static factory rather than through {@code ArmatureApi} because teams are
 * <b>per server</b>, and there can be more than one server in a process — a client with an
 * integrated server open, for instance. Anything cached in a static field would be the wrong
 * server's teams, which is the kind of bug that only appears in a dev environment with two worlds
 * open and is baffling when it does.
 *
 * <p>The manager is whichever source resolved for that server — Armature's own stored teams, or a
 * parties mod's if one is installed and has parties. See {@code TeamProviders} for how that is
 * decided; nothing here depends on the answer.
 *
 * <h2>Why this file resolves {@code impl} by name rather than importing it</h2>
 *
 * <p>This is the one place where the public API has to reach Armature's own implementation, and it
 * is the only such place in the project. The rule the split exists for — {@code api/} never imports
 * {@code impl/} — is why the reference is a string rather than an import, and it is worth being
 * plain about what that buys and what it costs.
 *
 * <p><b>What it buys.</b> There is no compile-time edge from the public surface to the internals, so
 * {@code impl/} stays genuinely free to change: renaming {@code StoredTeamManager}, splitting it,
 * deleting the whole stored source and replacing it with something else are all invisible here, and
 * a diff that violated the rule would show up as an import rather than as a runtime failure nobody
 * noticed until a release. The alternative — an import, which is what this file had — reads as
 * harmless and is not: it is the public surface naming a class that is allowed to change under it.
 *
 * <p><b>What it costs.</b> A broken class name is a startup failure rather than a compile error. It
 * is not a silent failure — {@link #RESOLVE} is bound once, in a static initialiser, and a
 * {@link ClassNotFoundException} there is an {@code ExceptionInInitializerError} naming the class —
 * but it is later than a compiler would have told you. That is a real cost and it is accepted
 * knowingly, because the thing being protected is the thing that lasts.
 *
 * <p>{@code check_layering.py} does not enforce this direction (it checks the other one, that
 * Armature never names a consumer). It is enforced by review, and by this comment explaining why the
 * odd-looking lines below are deliberate rather than a mistake to be tidied away.
 */
public final class Teams {

    /**
     * {@code TeamProviders.of(MinecraftServer)} — the resolver, bound once.
     *
     * <p>Bound in a static initialiser rather than looked up per call: this runs on every team
     * lookup, and a reflective lookup per call would be a real cost on the hot path for no gain.
     */
    private static final Method RESOLVE = bindResolver();

    private Teams() {
    }

    /**
     * The teams on this server.
     *
     * <p>Whatever that means here: Armature's own stored teams, or the parties a mod is already
     * providing. A caller does not choose and does not need to know — {@link TeamManager#name()}
     * will say, if a log line wants it.
     */
    public static TeamManager of(MinecraftServer server) {
        try {
            return (TeamManager) RESOLVE.invoke(null, server);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException("Armature could not reach its own team resolver. This is "
                    + "an access problem rather than a missing source, which means the resolver was "
                    + "found and could not be used.", e);
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // The resolver's own failure, not a reflection failure -- rethrown as-is so the stack
            // trace names the source that broke rather than a call through Method.invoke.
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Armature's team resolver failed.", cause);
        }
    }

    /**
     * The team {@code player} is in, real or solo.
     *
     * <p>Convenience for the common case, and the reason a caller never has to write
     * {@code Optional<Team>} plus a fallback.
     */
    public static Team teamOf(MinecraftServer server, UUID player) {
        return of(server).teamOf(player);
    }

    private static Method bindResolver() {
        try {
            Class<?> providers = Class.forName("dev.ellipog.armature.impl.teams.TeamProviders");
            return providers.getMethod("of", MinecraftServer.class);
        }
        catch (ClassNotFoundException | NoSuchMethodException e) {
            // Loud, and named. A no-op fallback here would present as "teams always empty on this
            // server", which reads as a game bug rather than as an Armature build problem.
            throw new ExceptionInInitializerError("Armature's team resolver is missing from this jar: "
                    + "dev.ellipog.armature.impl.teams.TeamProviders.of(MinecraftServer). Teams "
                    + "cannot be resolved without it. (" + e + ")");
        }
    }
}
