package dev.ellipog.armature.integration.emi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compatibility rule, pinned as a rule rather than as an outcome.
 *
 * <h2>The bug these tests exist for, and the second one they found</h2>
 *
 * <p>The first probe asked only whether a method named {@code setPosition(int, int)} existed. It did
 * — inherited from a generic supertype, returning the erased type — so the probe reported "healthy"
 * and no demotion ever fired. These fakes are that shape: a generic supertype whose method returns
 * the supertype, and an interface that demands the subtype.
 *
 * <p>The second version compared what {@code getMethod} returned against the interface's method —
 * but {@code getMethod} returns the <i>interface's own abstract method</i> when the class does not
 * implement it, so it compared the requirement with itself. What must be checked is dispatch: a
 * <b>concrete</b> method with the exact descriptor. {@link EmiCompatibility#servedBy} is that rule,
 * and these tests say no to the erased shape and yes to the covariant one.
 *
 * <h2>The shape that cannot be compiled here</h2>
 *
 * <p>The real break is a class compiled against an <i>older</i> interface and run against a newer
 * one; javac refuses to emit that from source (it would demand the bridge and add it). So the rule
 * and the gate are what is pinned here; the end-to-end probe was checked against the real EMI and
 * JEI jars on a scratch classpath, where it reports the installed pair broken, and on a client,
 * which is what found the second bug in the first place.
 */
class EmiCompatibilityTest {

    /** The generic supertype: its method's return erases to this interface. */
    private interface ReturnsBase {
        ReturnsBase setPosition(int x, int y);
    }

    /** The newer interface: a covariant declaration the inherited method does not satisfy. */
    private interface WantsWidget extends ReturnsBase {
        WantsWidget setPosition(int x, int y);
    }

    /** Compiled against the generic supertype only — the legacy shape. */
    private static final class LegacyShape implements ReturnsBase {
        @Override
        public ReturnsBase setPosition(int x, int y) {
            return this;
        }
    }

    /** The shape a class compiled against the newer interface has, bridge method included. */
    private static final class CurrentShape implements WantsWidget {
        @Override
        public WantsWidget setPosition(int x, int y) {
            return this;
        }
    }

    private static Method method(Class<?> type) throws NoSuchMethodException {
        return type.getMethod("setPosition", int.class, int.class);
    }

    @Test
    @DisplayName("a concrete method returning the erased supertype does not serve the interface's descriptor")
    void theErasedReturnDoesNotServeTheInterface() throws Exception {
        assertFalse(EmiCompatibility.servedBy(List.of(method(LegacyShape.class)), method(WantsWidget.class)),
                "the name and parameters match and the descriptor does not -- the break every " +
                        "earlier version of this probe missed, and it must demote EMI");
    }

    @Test
    @DisplayName("a concrete method returning the declared type does serve it")
    void theCovariantReturnServesTheInterface() throws Exception {
        assertTrue(EmiCompatibility.servedBy(List.of(method(CurrentShape.class)), method(WantsWidget.class)));
    }

    @Test
    @DisplayName("a class whose interfaces are all served passes the whole probe")
    void aServedClassPassesTheProbe() {
        assertTrue(EmiCompatibility.satisfiesSetPosition(LegacyShape.class),
                "its own requirement is the erased descriptor it implements");
        assertTrue(EmiCompatibility.satisfiesSetPosition(CurrentShape.class),
                "and a class built against the newer interface carries the bridge for both");
    }

    @Test
    @DisplayName("a class that cannot be looked at is trusted, never demoted")
    void anUnprobeableClassIsTrusted() {
        assertTrue(EmiCompatibility.satisfiesSetPosition(null));
        assertFalse(EmiCompatibility.servedBy(List.of(), methodOrNull()),
                "and with no candidate at all, nothing is served");
    }

    @Test
    @DisplayName("only EMI can be demoted")
    void onlyEmiCanBeDemoted() {
        assertFalse(EmiCompatibility.broken(id -> true, "jei"));
        assertFalse(EmiCompatibility.broken(id -> true, "roughlyenoughitems"));
    }

    @Test
    @DisplayName("nothing is demoted while the pair is not both installed")
    void theProbeOnlyRunsForThePair() {
        assertFalse(EmiCompatibility.broken(id -> false, "emi"),
                "EMI alone is never demoted, whatever the probe would say");
        assertFalse(EmiCompatibility.broken(id -> id.equals("emi"), "emi"),
                "EMI without JEI is never demoted either: the bridge this guards does not exist");
    }

    /** The required method from {@link WantsWidget}, without the checked exception in a lambda. */
    private static Method methodOrNull() {
        try {
            return method(WantsWidget.class);
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
