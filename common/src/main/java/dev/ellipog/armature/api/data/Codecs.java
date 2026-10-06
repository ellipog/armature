package dev.ellipog.armature.api.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Codec helpers for the cases where the obvious version is a trap.
 */
public final class Codecs {

    private Codecs() {
    }

    /**
     * A codec for an enum, matched by lowercase name, whose error lists the valid names.
     *
     * <p>Worth a helper rather than {@code Codec.STRING.xmap(MyEnum::valueOf, ...)} for two
     * reasons. {@code valueOf} throws {@link IllegalArgumentException} on an unknown name, which
     * escapes the codec as a crash instead of a {@code DataResult} error a caller can report. And
     * Minecraft's own {@code StringRepresentable} machinery produces errors that do not say what
     * the options were — which, for someone editing a file by hand, is the only thing they want to
     * know.
     */
    public static <E extends Enum<E>> Codec<E> enumByName(Class<E> type) {
        Map<String, E> byName = new LinkedHashMap<>();
        for (E value : type.getEnumConstants()) {
            byName.put(value.name().toLowerCase(Locale.ROOT), value);
        }
        String valid = Arrays.stream(type.getEnumConstants())
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));

        return Codec.STRING.comapFlatMap(
                name -> {
                    E found = byName.get(name.toLowerCase(Locale.ROOT));
                    return found != null
                            ? DataResult.success(found)
                            : DataResult.error(() -> "'" + name + "' is not one of: " + valid);
                },
                value -> value.name().toLowerCase(Locale.ROOT));
    }

    /**
     * A codec for a whole number that <b>clamps</b> an out-of-range value instead of refusing the file.
     *
     * <h2>Why this rather than {@code Codec.intRange}</h2>
     *
     * <p>Because the two behave oppositely on the one input a versioned file format always eventually
     * meets: a number a later build considers out of range. {@code Codec.intRange} produces a
     * {@code DataResult} error, and an error anywhere in a document means the whole document is
     * refused — so one entry with an odd number in it costs the reader everything else in the file.
     * This reads the same value as the nearest legal one, which is the choice that keeps a document
     * loadable.
     *
     * <p>It is also the reading a lenient reader already gives such a value: a number that can still be
     * drawn is drawn, clamped, rather than reported as a fault. A strict codec beside a lenient reader
     * was two halves of one format disagreeing about one number.
     *
     * <p><b>Not for every number.</b> Clamping is right where an odd value is a presentation decision
     * — a coordinate, a size, a scale, a tick count. It is wrong where the number <i>is</i> the
     * meaning, and a caller that needs the document refused should keep {@code Codec.intRange}; the two
     * exist side by side on purpose. Bounds still belong to the record that owns the field, so the
     * codec and a validator cannot come to disagree about them.
     *
     * @param min the smallest value read as itself; anything below is read as this
     * @param max the largest value read as itself; anything above is read as this
     */
    public static Codec<Integer> clampedInt(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("clampedInt(" + min + ", " + max + "): min is above max");
        }
        return Codec.INT.flatXmap(
                value -> DataResult.success(Math.max(min, Math.min(max, value))),
                // Identity, so an in-range value encodes to exactly the bytes it always did. A
                // clamping codec that also rewrote legal values would be a format change.
                value -> DataResult.success(value));
    }

    /** The same, for a fraction: {@code iconScale} is the one field that is a share rather than a count. */
    public static Codec<Double> clampedDouble(double min, double max) {
        if (!(min <= max)) {
            throw new IllegalArgumentException("clampedDouble(" + min + ", " + max + "): min is above max");
        }
        return Codec.DOUBLE.flatXmap(
                value -> DataResult.success(Math.max(min, Math.min(max, value))),
                value -> DataResult.success(value));
    }

    /**
     * A codec for a raw JSON object, carried exactly as it was written.
     *
     * <p>For a field whose contents belong to another system — a theme patch is validated and parsed by
     * the toolkit that draws it, not by the model that carries it. The model's job there is to hold the
     * object and hand it on; decoding it strictly here would be a second parser, and the second parser
     * is the one that refuses a file the client could have read. The validator is where a patch's
     * contents are checked, on the side that can name the line.
     */
    public static Codec<JsonObject> jsonObject() {
        return Codec.PASSTHROUGH.comapFlatMap(
                dynamic -> {
                    JsonElement element = dynamic.convert(JsonOps.INSTANCE).getValue();
                    return element instanceof JsonObject object
                            ? DataResult.success(object)
                            : DataResult.error(() -> "expected an object");
                },
                object -> new Dynamic<>(JsonOps.INSTANCE, object));
    }
}
