package dev.ellipog.armature.api.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

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
}
