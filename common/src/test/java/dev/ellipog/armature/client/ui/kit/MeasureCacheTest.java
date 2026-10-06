package dev.ellipog.armature.client.ui.kit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The memoizing measure: what it answers, what it does not ask twice, and what makes it start again.
 *
 * <h2>Why the assertions are about the number of asks</h2>
 *
 * <p>Because a cache that did nothing and a cache that was not consulted produce identical answers, so
 * "it returned the right width" is not the property under test. What is under test is that the second
 * question was not asked — which is why the delegate here counts, and why the epoch case has to assert
 * that the question <i>was</i> asked again.
 *
 * <p>No client: a measure is two methods, and the counting delegate is a stand-in for a font in the
 * same way {@link Measure#monospace} is.
 */
@DisplayName("a measure that remembers what it has measured")
class MeasureCacheTest {

    /** A measure that counts what it is asked, per string. */
    private static final class Counting implements Measure {

        private final Map<String, Integer> asked = new HashMap<>();
        private final int perCharacter;

        Counting(int perCharacter) {
            this.perCharacter = perCharacter;
        }

        @Override
        public int width(String text) {
            asked.merge(text, 1, Integer::sum);
            return text.length() * perCharacter;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        int asks(String text) {
            return asked.getOrDefault(text, 0);
        }
    }

    /** The epoch, as a number a test can move. */
    private long epoch = 1L;

    private Measure cached(Counting delegate) {
        return Measure.cached(delegate, () -> epoch);
    }

    @Test
    @DisplayName("a string is measured once, however many times it is asked about")
    void aStringIsMeasuredOnce() {
        Counting delegate = new Counting(5);
        Measure measure = cached(delegate);

        // The shape of a truncation: the prefixes of one label, in increasing length, and then the same
        // question again on the next frame. The second pass must cost nothing.
        assertEquals(25, measure.width("three"));
        assertEquals(30, measure.width("three!"));
        assertEquals(25, measure.width("three"));
        assertEquals(30, measure.width("three!"));

        assertEquals(1, delegate.asks("three"));
        assertEquals(1, delegate.asks("three!"));
    }

    @Test
    @DisplayName("different strings are different entries")
    void differentStringsAreSeparate() {
        Counting delegate = new Counting(5);
        Measure measure = cached(delegate);

        assertEquals(10, measure.width("ab"));
        assertEquals(15, measure.width("abc"));

        assertEquals(1, delegate.asks("ab"));
        assertEquals(1, delegate.asks("abc"));
    }

    @Test
    @DisplayName("a new epoch forgets what the old one measured")
    void theEpochForgets() {
        Counting delegate = new Counting(5);
        Measure measure = cached(delegate);

        assertEquals(10, measure.width("ab"));
        // The player moved the text scale, or a resource reload replaced the font: the same string is a
        // different width now, and an answer held from before is a layout that no longer fits.
        epoch++;
        assertEquals(10, measure.width("ab"));

        assertEquals(2, delegate.asks("ab"), "the width was asked for again after the epoch moved");

        // And the memo is working again on the other side of the change.
        assertEquals(10, measure.width("ab"));
        assertEquals(2, delegate.asks("ab"));
    }

    @Test
    @DisplayName("a constant epoch caches for ever, which is what the caller supplies a real one to avoid")
    void aConstantEpochNeverForgets() {
        Counting delegate = new Counting(5);
        Measure measure = Measure.cached(delegate, () -> 7L);

        measure.width("ab");
        measure.width("ab");
        assertEquals(1, delegate.asks("ab"));
    }

    @Test
    @DisplayName("the line height is the delegate's, not a remembered one")
    void lineHeightIsForwarded() {
        assertEquals(9, cached(new Counting(5)).lineHeight());
    }

    @Test
    @DisplayName("a measure over a changing renderer remembers, and reads whichever renderer is in force")
    void cachedOverFollowsTheRenderer() {
        // What `ArmatureButton` and `ArmatureSlider` use, and the reason it is not `Measure.of`: a control
        // that truncates a label every frame has to keep its measure **across** frames for the memo to be
        // worth anything, and the renderer it measures with is whichever one is drawing — a screen redraws
        // it through its own, a modal band through another, a test through a recording one. So the measure
        // cannot close over a renderer; it reads the one in force.
        Counting first = new Counting(5);
        Counting second = new Counting(3);
        Counting[] inForce = { first };

        // The epoch a control passes: the font's, folded with the renderer's identity. The identity is not
        // decoration — this test failed without it, because `TextEpoch` describes the font and the text
        // scale and neither changes when a control is redrawn through a different renderer.
        Measure measure = Measure.cachedOver(text -> inForce[0].width(text),
                () -> inForce[0].lineHeight(),
                () -> epoch * 31L + System.identityHashCode(inForce[0]));

        // The same string twice is measured once, which is the whole of the memo.
        assertEquals(10, measure.width("ab"));
        assertEquals(10, measure.width("ab"));
        assertEquals(1, first.asks("ab"), "the second ask was answered from the memo");

        // The line height follows the renderer in force rather than being fixed at construction.
        assertEquals(9, measure.lineHeight());

        // A different renderer reads a different width for the same string — and the memo must not answer
        // the old one, or a control redrawn through another renderer draws its label at the wrong width.
        inForce[0] = second;
        assertEquals(6, measure.width("ab"), "the width is the renderer in force, not the one it was built with");
        assertEquals(1, second.asks("ab"), "and the new renderer was asked");

        // And the epoch still governs: a font reload forgets everything, whichever renderer is in force.
        epoch++;
        assertEquals(6, measure.width("ab"));
        assertEquals(2, second.asks("ab"), "the epoch moving asks again");
    }

    @Test
    @DisplayName("the memo holds a bounded number of strings, and starts again rather than growing")
    void theMemoIsBounded() {
        Counting delegate = new Counting(1);
        Measure measure = cached(delegate);

        // Four thousand distinct strings is past what one memo holds, so it empties itself. The property
        // being asserted is the bound, not the policy: what must not happen is a map that grows with
        // every distinct label a long session ever draws.
        String first = "s0";
        assertEquals(2, measure.width(first));
        for (int i = 1; i <= 5_000; i++) {
            measure.width("s" + i);
        }

        int before = delegate.asks(first);
        measure.width(first);
        assertEquals(before + 1, delegate.asks(first),
                "the earliest entries were dropped, so this one is asked for again");
    }
}
