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
