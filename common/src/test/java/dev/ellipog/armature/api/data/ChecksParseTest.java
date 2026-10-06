package dev.ellipog.armature.api.data;

import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Checks#parse}, which exists so that a codec cannot throw out of a loader.
 *
 * <h2>Why this is worth a test of its own</h2>
 *
 * <p>Because the guarantee is invisible at every call site. A caller writes
 * {@code Checks.parse(codec, root)} and gets a {@code DataResult}, which looks exactly like
 * {@code codec.parse(...)} — the whole difference is that one of them cannot escape as a throwable.
 * Nothing in the signature says so, so the only thing that keeps the boundary real is this file.
 *
 * <p>And the failure it prevents is not local. A loader handles {@code DataResult} errors and has no
 * try/catch, so a codec that throws takes the entire load with it rather than the one file — which is
 * exactly what happened here, once, in a built-in codec. See the method's own javadoc.
 *
 * <p>The two shapes below are the two ways a real codec throws: an {@code xmap} or
 * {@code comapFlatMap} function that raises (DFU does not catch what those do — {@code Decoder.map}
 * has no exception table), and a hand-written codec whose {@code decode} raises. The second is the one
 * an addon is most likely to write.
 */
@DisplayName("Checks.parse")
class ChecksParseTest {

    @Test
    @DisplayName("a well-formed value still decodes, so the boundary is not a second parser")
    void aWellFormedValueStillDecodes() {
        DataResult<String> result = Checks.parse(Codec.STRING, new JsonPrimitive("hello"));

        assertEquals("hello", result.result().orElseThrow(),
                "the boundary must not change what a working codec does");
    }

    @Test
    @DisplayName("a value a codec rejects comes back as an error, unchanged")
    void aRejectedValueIsStillAnError() {
        DataResult<Integer> result = Checks.parse(Codec.INT, new JsonPrimitive("not a number"));

        assertTrue(result.error().isPresent(), "an ordinary rejection must still be an error");
    }

    @Test
    @DisplayName("an xmap function that throws becomes an error rather than escaping")
    void aThrowingXmapBecomesAnError() {
        // The mistake this project actually made: `Codec.STRING.xmap(Thing::parse, Thing::wire)` where
        // parse throws for a value it cannot read. DFU's Decoder.map does not catch it, so it left the
        // codec as a raw throwable -- and one malformed field took every file in the tree with it.
        Codec<Integer> explodes = Codec.STRING.xmap(
                text -> {
                    throw new IllegalStateException("the reader gave up on '" + text + "'");
                },
                value -> String.valueOf(value));

        DataResult<Integer> result = Checks.parse(explodes, new JsonPrimitive("anything"));

        assertTrue(result.error().isPresent(),
                "the throwable must have become a DataResult error, or this call would not have returned");
    }

    @Test
    @DisplayName("a comapFlatMap function that throws becomes an error too")
    void aThrowingComapFlatMapBecomesAnError() {
        // The other half of the same hazard, and the shape RegistryRef now uses deliberately: a
        // comapFlatMap is only safe while its function returns an error instead of raising.
        Codec<Integer> explodes = Codec.STRING.comapFlatMap(
                text -> {
                    throw new IllegalArgumentException("not a number: " + text);
                },
                value -> String.valueOf(value));

        assertTrue(Checks.parse(explodes, new JsonPrimitive("x")).error().isPresent());
    }

    @Test
    @DisplayName("a hand-written codec whose decode throws becomes an error")
    void aHandWrittenCodecThatThrowsBecomesAnError() {
        // What an addon registering its own task type is free to write. This is the case the boundary
        // exists for: the codec that throws is one nobody in this repository wrote.
        Codec<String> explodes = new Codec<>() {

            @Override
            public <T> DataResult<Pair<String, T>> decode(DynamicOps<T> ops, T input) {
                throw new UnsupportedOperationException("this addon's reader is broken");
            }

            @Override
            public <T> DataResult<T> encode(String input, DynamicOps<T> ops, T prefix) {
                return DataResult.success(prefix);
            }
        };

        DataResult<String> result = Checks.parse(explodes, new JsonPrimitive("x"));

        assertTrue(result.error().isPresent(),
                "an addon's throwing codec must be contained here, not left to the loader");
    }

    @Test
    @DisplayName("the exception is named in the message, so it is contained rather than swallowed")
    void theExceptionIsNamedInTheMessage() {
        // The difference between containing a fault and hiding one. A message that said only "this
        // value made the codec throw" would leave the author with no idea which codec, and the log with
        // no stack context at all -- so the exception's own text has to travel.
        Codec<Integer> explodes = Codec.STRING.xmap(
                text -> {
                    throw new IllegalStateException("the addon's reader exploded");
                },
                value -> String.valueOf(value));

        String message = Checks.parse(explodes, new JsonPrimitive("x")).error()
                .map(DataResult.Error::message)
                .orElseThrow();

        assertTrue(message.contains("the addon's reader exploded"),
                "the exception's own words must reach the author: " + message);
        assertTrue(message.contains("codec"),
                "and the message must say that the fault is the codec's rather than the file's: "
                        + message);
    }
}
