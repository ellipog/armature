package dev.ellipog.armature.integration.emi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compatibility rule, pinned as a rule rather than as an outcome.
 *
 * <h2>What is tested, and what deliberately is not</h2>
 *
 * <p>Whether the <i>installed</i> EMI is broken beside JEI depends on which EMI is installed — a
 * test JVM loads neither viewer, and the day a fixed EMI is on the classpath the probe would answer
 * differently. So the test pins the two halves that must not move: the shape of the question
 * ({@link EmiCompatibility#declaresSetPosition} — a class with the method is fine, one without is
 * broken, one that cannot be looked at is trusted), and the gate (only the EMI id can be demoted,
 * and only while the pair is present).
 */
class EmiCompatibilityTest {

    /** The shape of the real pair: an interface's method that a class may or may not carry. */
    private interface FakeWidget {
        void render();
    }

    private static final class WithPosition implements FakeWidget {

        @Override
        public void render() {
        }

        public void setPosition(int x, int y) {
        }
    }

    private static final class WithoutPosition implements FakeWidget {

        @Override
        public void render() {
        }
    }

    @Test
    @DisplayName("a widget class with the method is compatible")
    void aWidgetWithTheMethodIsFine() {
        assertTrue(EmiCompatibility.declaresSetPosition(WithPosition.class));
    }

    @Test
    @DisplayName("a widget class without it is the break that demotes EMI")
    void aWidgetWithoutItIsBroken() {
        assertFalse(EmiCompatibility.declaresSetPosition(WithoutPosition.class));
    }

    @Test
    @DisplayName("a class that cannot be looked at is trusted, never demoted")
    void anUnprobeableClassIsTrusted() {
        // Trust by default is the direction that matters: a probe that failed must not take a
        // working EMI away from a client.
        assertTrue(EmiCompatibility.declaresSetPosition(null));
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
}
